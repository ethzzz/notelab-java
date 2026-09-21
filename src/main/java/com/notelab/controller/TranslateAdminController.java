package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.dao.TranslateDao;
import com.notelab.scheduler.TranslateScheduler;
import com.notelab.service.PermService;
import com.notelab.service.RateLimit;
import com.notelab.service.TranslateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B 端「翻译句子库」管理：前缀 /api/admin/translate，AuthUtil（B 端 users）鉴权。
 * RBAC：页面权限码 page:/translate（超管天然放行，其余角色需在 /perm 分配）；
 * API 路由由 PermService 启动自动登记进 perm_routes。
 * 句子组状态机：draft 草稿 → queued 已入队（等 0 点激活）→ used 已激活；
 * 也支持 PUT 手动指定 activated_date 强制发布。
 * 句子入库三通道：手动加句 / 批量导入（中文句末标点+换行切分）/ LLM 批量生成（分阶）。
 */
@RestController
@RequestMapping("/api/admin/translate")
public class TranslateAdminController {

    /** 页面路由（RBAC 权限码 page:/translate） */
    private static final String PAGE_PATH = "/translate";
    private static final Set<String> VALID_STATUS = Set.of("draft", "queued", "used");
    private static final Set<String> VALID_SOURCE = Set.of("manual", "import", "llm");
    private static final int MAX_TITLE_LEN = 120;
    private static final int MAX_ZH_LEN = 255;
    private static final int MAX_REF_LEN = 500;
    private static final int MAX_IMPORT_CHARS = 20000;
    private static final int MAX_GEN_PER_TIER = 10;

    public static class GroupReq {
        public String title;
        public String scenario;
        public String note;
        public String source;
    }

    public static class SentenceReq {
        public String zh_text;
        public Integer tier;
        public String ref_en;
        public Integer sort_order;
    }

    public static class ImportReq {
        public String text;
        public Integer tier;
    }

    public static class GenerateReq {
        public String scenario;
        public String prompt;
        public Counts counts;
    }

    public static class Counts {
        public Integer t1;
        public Integer t2;
        public Integer t3;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "无「翻译句子库」页面权限"));
    }

    /** 页面权限校验：超管天然放行，其余角色需持有 page:/translate */
    private static boolean allowed(Map<String, Object> me) {
        return PermService.hasPageRoute(me, PAGE_PATH);
    }

    // ================= 句子组 =================

    /** 组列表（含句子数） */
    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> listGroups(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups", TranslateDao.listGroups());
        body.put("tiers", TranslateService.tiers());
        body.put("queued", TranslateDao.countQueued());
        body.put("today", LocalDate.now().toString());
        return ResponseEntity.ok(body);
    }

    /** 新建组（status=draft） */
    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody(required = false) GroupReq req,
                                                           HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        if (req == null || req.title == null || req.title.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
        }
        String title = req.title.trim();
        if (title.length() > MAX_TITLE_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "标题最多 " + MAX_TITLE_LEN + " 字"));
        }
        String source = VALID_SOURCE.contains(nz(req.source)) ? req.source.trim() : "manual";
        Long createdBy = me.get("id") instanceof Number n ? n.longValue() : null;
        long id = TranslateDao.createGroup(title, source, trimTo(req.scenario, 60), trimTo(req.note, 255), createdBy);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        body.put("group", TranslateDao.getGroup(id));
        return ResponseEntity.ok(body);
    }

    /** 组详情 + 分阶句子列表 */
    @GetMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> groupDetail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        Map<String, Object> group = TranslateDao.getGroup(id);
        if (group == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        List<Map<String, Object>> sentences = TranslateDao.listSentences(id);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        counts.put(1, 0);
        counts.put(2, 0);
        counts.put(3, 0);
        for (Map<String, Object> s : sentences) {
            Object t = s.get("tier");
            if (t instanceof Number n) counts.merge(n.intValue(), 1, Integer::sum);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", group);
        body.put("sentences", sentences);
        body.put("tier_counts", counts);
        body.put("tiers", TranslateService.tiers());
        return ResponseEntity.ok(body);
    }

    /**
     * 改元信息 / 改状态（draft↔queued）/ 手动设 activated_date 强制发布。
     * body 用 Map 以区分「字段缺省」与「显式置空」：activated_date 传空串/null 表示清空（退回草稿）。
     */
    @PutMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> updateGroup(@PathVariable long id,
                                                           @RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        if (TranslateDao.getGroup(id) == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        Map<String, Object> req = body == null ? Map.of() : body;

        String title = null;
        if (req.containsKey("title")) {
            String t = str(req.get("title"));
            if (t.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "标题不能为空"));
            if (t.length() > MAX_TITLE_LEN) {
                return ResponseEntity.status(400).body(Map.of("error", "标题最多 " + MAX_TITLE_LEN + " 字"));
            }
            title = t;
        }
        String scenario = req.containsKey("scenario") ? trimTo(str(req.get("scenario")), 60) : null;
        String note = req.containsKey("note") ? trimTo(str(req.get("note")), 255) : null;
        String status = null;
        if (req.containsKey("status") && !str(req.get("status")).isEmpty()) {
            String s = str(req.get("status"));
            if (!VALID_STATUS.contains(s)) {
                return ResponseEntity.status(400).body(Map.of("error", "status 仅支持 draft/queued/used"));
            }
            status = s;
        }
        LocalDate activatedDate = null;
        boolean clearDate = false;
        if (req.containsKey("activated_date")) {
            String raw = str(req.get("activated_date"));
            if (raw.isEmpty()) {
                clearDate = true;
            } else {
                try {
                    activatedDate = LocalDate.parse(raw);
                } catch (DateTimeParseException e) {
                    return ResponseEntity.status(400).body(Map.of("error", "activated_date 格式应为 YYYY-MM-DD"));
                }
            }
        }
        // 未显式给 status 时：设日期 = 强制发布(used)，清日期 = 退回草稿(draft)
        if (status == null && activatedDate != null) status = "used";
        if (status == null && clearDate) status = "draft";

        TranslateDao.updateGroupFields(id, title, status, scenario, note, activatedDate);
        if (clearDate) TranslateDao.clearActivatedDate(id);
        return ResponseEntity.ok(Map.of("ok", true, "group", TranslateDao.getGroup(id)));
    }

    /** 删组（句子由外键 ON DELETE CASCADE 级联删） */
    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        if (TranslateDao.getGroup(id) == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        TranslateDao.deleteGroup(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 入队：置 status='queued'，等 0 点定时任务激活 */
    @PostMapping("/groups/{id}/queue")
    public ResponseEntity<Map<String, Object>> queueGroup(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        Map<String, Object> group = TranslateDao.getGroup(id);
        if (group == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        if (TranslateDao.countSentences(id) == 0) {
            return ResponseEntity.status(400).body(Map.of("error", "句子组为空，请先添加句子再入队"));
        }
        if ("used".equals(group.get("status"))) {
            return ResponseEntity.status(400).body(Map.of("error", "该组已激活，无需入队"));
        }
        TranslateDao.updateGroupFields(id, null, "queued", null, null, null);
        return ResponseEntity.ok(Map.of("ok", true, "group", TranslateDao.getGroup(id),
                "queued", TranslateDao.countQueued()));
    }

    /** 手动补跑激活（与 0 点定时任务同一逻辑，幂等）：当天已激活则不重复 */
    @PostMapping("/activate-today")
    public ResponseEntity<Map<String, Object>> activateToday(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        Map<String, Object> r = TranslateScheduler.activateToday(LocalDate.now());
        return ResponseEntity.ok(r);
    }

    // ================= 句子 =================

    /** 手动加句 */
    @PostMapping("/groups/{id}/sentences")
    public ResponseEntity<Map<String, Object>> addSentence(@PathVariable long id,
                                                           @RequestBody(required = false) SentenceReq req,
                                                           HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        if (TranslateDao.getGroup(id) == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        ResponseEntity<Map<String, Object>> bad = validateSentence(req);
        if (bad != null) return bad;
        String zh = req.zh_text.trim();
        long sid = TranslateDao.createSentence(id, req.tier, zh, nullIfBlank(req.ref_en), req.sort_order);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", sid);
        body.put("sentence", TranslateDao.getSentence(sid));
        if (zh.length() > TranslateService.MAX_ZH_LEN) {
            body.put("warning", "中文原句超过 " + TranslateService.MAX_ZH_LEN + " 字，已入库但偏长");
        }
        return ResponseEntity.ok(body);
    }

    /** 改单句（zh_text / ref_en / tier / sort_order，缺省字段不改） */
    @PutMapping("/sentences/{sid}")
    public ResponseEntity<Map<String, Object>> updateSentence(@PathVariable long sid,
                                                              @RequestBody(required = false) SentenceReq req,
                                                              HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        Map<String, Object> row = TranslateDao.getSentence(sid);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "句子不存在"));
        if (req == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不能为空"));
        String zh = req.zh_text == null ? null : req.zh_text.trim();
        if (zh != null && (zh.isEmpty() || zh.length() > MAX_ZH_LEN)) {
            return ResponseEntity.status(400).body(Map.of("error", "中文原句需为 1-" + MAX_ZH_LEN + " 字"));
        }
        String ref = req.ref_en == null ? null : trimTo(req.ref_en, MAX_REF_LEN);
        Integer tier = req.tier;
        if (tier != null && (tier < 1 || tier > 3)) {
            return ResponseEntity.status(400).body(Map.of("error", "tier 仅支持 1/2/3"));
        }
        if (zh == null && ref == null && tier == null && req.sort_order == null) {
            return ResponseEntity.status(400).body(Map.of("error", "没有需要更新的字段"));
        }
        TranslateDao.updateSentenceFields(sid, zh, ref, tier, req.sort_order);
        return ResponseEntity.ok(Map.of("ok", true, "sentence", TranslateDao.getSentence(sid)));
    }

    /** 删单句 */
    @DeleteMapping("/sentences/{sid}")
    public ResponseEntity<Map<String, Object>> deleteSentence(@PathVariable long sid, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        if (TranslateDao.getSentence(sid) == null) {
            return ResponseEntity.status(404).body(Map.of("error", "句子不存在"));
        }
        TranslateDao.deleteSentence(sid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 批量导入：按中文句末标点（。！？；…!?;）与换行切分成一句一句，
     * trim、去空、去重（文本内重复 + 组内已存在）、过滤过短（<4 字）；超长仅提示仍入库。
     * 返回 {imported, skipped, long_count, sentences}。
     */
    @PostMapping("/groups/{id}/import")
    public ResponseEntity<Map<String, Object>> importSentences(@PathVariable long id,
                                                               @RequestBody(required = false) ImportReq req,
                                                               HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        if (TranslateDao.getGroup(id) == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        if (req == null || req.text == null || req.text.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "导入文本不能为空"));
        }
        if (req.text.length() > MAX_IMPORT_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error", "单次导入文本最多 " + MAX_IMPORT_CHARS + " 字"));
        }
        int tier = req.tier == null ? 1 : req.tier;
        if (tier < 1 || tier > 3) return ResponseEntity.status(400).body(Map.of("error", "tier 仅支持 1/2/3"));

        TranslateService.SplitResult split = TranslateService.splitZhText(req.text);
        int skipped = split.skipped;
        boolean emptyBefore = TranslateDao.countSentences(id) == 0;
        List<Map<String, Object>> inserted = new ArrayList<>();
        int longCount = 0;
        for (String zhRaw : split.sentences) {
            String zh = zhRaw.length() > MAX_ZH_LEN ? zhRaw.substring(0, MAX_ZH_LEN) : zhRaw;
            if (TranslateDao.sentenceExists(id, zh)) {
                skipped++;
                continue;
            }
            long sid = TranslateDao.createSentence(id, tier, zh, null, null);
            if (zh.length() > TranslateService.MAX_ZH_LEN) longCount++;
            Map<String, Object> row = TranslateDao.getSentence(sid);
            if (row != null) inserted.add(row);
        }
        if (emptyBefore && !inserted.isEmpty()) setSourceIfManual(id, "import");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("imported", inserted.size());
        body.put("skipped", skipped);
        body.put("long_count", longCount);
        body.put("sentences", inserted);
        if (longCount > 0) {
            body.put("warning", "有 " + longCount + " 句超过 " + TranslateService.MAX_ZH_LEN + " 字，已入库但偏长");
        }
        return ResponseEntity.ok(body);
    }

    /**
     * LLM 批量生成：{scenario, prompt, counts:{t1,t2,t3}} → 分阶中文句（10-50 字，可附参考英文）。
     * 生成结果直接入库；失败优雅降级为明确 error（不冒泡 500）。
     * 限流：同 IP 6 次 / 300s（对齐 trpg 生成，避免刷模型配额）。
     */
    @PostMapping("/groups/{id}/generate")
    public ResponseEntity<Map<String, Object>> generate(@PathVariable long id,
                                                        @RequestBody(required = false) GenerateReq req,
                                                        HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!allowed(me)) return forbidden();
        Map<String, Object> group = TranslateDao.getGroup(id);
        if (group == null) return ResponseEntity.status(404).body(Map.of("error", "句子组不存在"));
        if (req == null) return ResponseEntity.status(400).body(Map.of("error", "请求体不能为空"));
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        counts.put(1, normCount(req.counts == null ? null : req.counts.t1));
        counts.put(2, normCount(req.counts == null ? null : req.counts.t2));
        counts.put(3, normCount(req.counts == null ? null : req.counts.t3));
        int total = counts.get(1) + counts.get(2) + counts.get(3);
        if (total == 0) return ResponseEntity.status(400).body(Map.of("error", "请至少设置一个阶梯的生成数量"));
        String ip = AuthUtil.clientIp(request);
        if (!RateLimit.rateOk("entr-gen:" + ip, 6, 300)) {
            return ResponseEntity.status(429).body(Map.of("error", "生成过于频繁，请 5 分钟后再试"));
        }
        boolean emptyBefore = TranslateDao.countSentences(id) == 0;
        String scenario = trimTo(req.scenario, 60);
        String prompt = trimTo(req.prompt, 500);

        List<TranslateService.Generated> items;
        try {
            items = TranslateService.generate(scenario, prompt, counts);
        } catch (TranslateService.GenerateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "生成失败，请重试"));
        }

        List<Map<String, Object>> inserted = new ArrayList<>();
        int skipped = 0;
        // 按阶梯顺序入库，sort_order 自动追加到该阶梯末尾
        Set<String> seen = new LinkedHashSet<>();
        for (TranslateService.Generated g : items) {
            String zh = g.zhText.length() > MAX_ZH_LEN ? g.zhText.substring(0, MAX_ZH_LEN) : g.zhText;
            if (!seen.add(zh) || TranslateDao.sentenceExists(id, zh)) {
                skipped++;
                continue;
            }
            long sid = TranslateDao.createSentence(id, g.tier, zh, g.refEn == null || g.refEn.isBlank() ? null : g.refEn, null);
            Map<String, Object> row = TranslateDao.getSentence(sid);
            if (row != null) inserted.add(row);
        }
        if (emptyBefore && !inserted.isEmpty()) {
            setSourceIfManual(id, "llm");
            if (!scenario.isEmpty()) {
                TranslateDao.updateGroupFields(id, null, null, scenario, null, null);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("generated", inserted.size());
        body.put("skipped", skipped);
        body.put("requested", total);
        body.put("sentences", inserted);
        body.put("model", AppConfig.qwenModel());
        if (inserted.size() < total) {
            body.put("warning", "模型实际产出 " + inserted.size() + " 句（请求 " + total + " 句），可再次生成补足");
        }
        return ResponseEntity.ok(body);
    }

    // ================= 校验/工具 =================

    private static ResponseEntity<Map<String, Object>> validateSentence(SentenceReq req) {
        if (req == null || req.zh_text == null || req.zh_text.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "中文原句不能为空"));
        }
        String zh = req.zh_text.trim();
        if (zh.length() > MAX_ZH_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "中文原句最多 " + MAX_ZH_LEN + " 字"));
        }
        if (req.tier == null || req.tier < 1 || req.tier > 3) {
            return ResponseEntity.status(400).body(Map.of("error", "tier 仅支持 1/2/3"));
        }
        if (req.ref_en != null && req.ref_en.trim().length() > MAX_REF_LEN) {
            return ResponseEntity.status(400).body(Map.of("error", "参考译文最多 " + MAX_REF_LEN + " 字"));
        }
        return null;
    }

    /** 组来源仍为 manual 时改写为 import/llm（首次批量填充时定型，后续手动加句不覆盖） */
    private static void setSourceIfManual(long groupId, String source) {
        Map<String, Object> g = TranslateDao.getGroup(groupId);
        if (g != null && "manual".equals(g.get("source"))) {
            TranslateDao.setGroupSource(groupId, source);
        }
    }

    private static int normCount(Integer v) {
        if (v == null || v < 0) return 0;
        return Math.min(v, MAX_GEN_PER_TIER);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trimTo(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String nullIfBlank(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= MAX_REF_LEN ? t : t.substring(0, MAX_REF_LEN);
    }

}
