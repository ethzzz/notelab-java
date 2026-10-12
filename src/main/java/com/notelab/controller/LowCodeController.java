package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.dao.LowCodeDao;
import com.notelab.service.EventRecorder;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 低代码平台：设计稿的持久化 + 表单运行时的提交记录。
 *
 * <h3>两套存储，别混</h3>
 * <ul>
 *   <li><b>设计稿（配置）</b>：{@code ui_config} 的 {@code lowcode} 键，四个方案列表
 *       {@code forms / flows / models / pages}，整包覆盖写。体量有限、改动低频，
 *       与 {@code dev_notes}、{@code spire} 同一套路（见 {@link DevNoteController}）。</li>
 *   <li><b>提交记录（运行数据）</b>：表 {@code lowcode_records}（见 {@link LowCodeDao}）。
 *       提交会随时间无限增长，且「删一条提交」不该重写整份设计稿 —— 所以不进 ui_config。</li>
 * </ul>
 *
 * <h3>方案体的净化策略</h3>
 * 字段结构（表单字段的 span / rules / visibleWhen / 子表单…）会持续演进，写死白名单就要跟着改两处，
 * 且漏一个字段就是「存了刷新就没了」。所以这里改成**结构净化**：递归裁剪
 * 深度 ≤ {@link #MAX_DEPTH}、数组 ≤ {@link #MAX_ARRAY}、字符串 ≤ {@link #MAX_STR}、键名 ≤ 64 字符，
 * 只留 JSON 原生类型。方案级三个元字段（id/name/updated_at）仍走精确净化，因为前端靠它们做选择器。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/lowcode")
public class LowCodeController {

    /** ui_config 里的键 */
    static final String KEY = "lowcode";
    /** 四类方案；新增一类要同时改这里与前端 PLAN_KINDS */
    static final List<String> KINDS = List.of("forms", "flows", "models", "pages");

    static final int MAX_PLANS_PER_KIND = 100;
    static final int MAX_DEPTH = 10;
    static final int MAX_ARRAY = 500;
    static final int MAX_STR = 20_000;
    static final int MAX_KEYS = 200;
    static final int MAX_LOWCODE_CHARS = 4_000_000;

    // ---- 对外发布（2026-10-11）----

    /** 公开链接片段：小写字母/数字/连字符，3~32 位，必须字母或数字开头。它既是 URL 也是查表键，必须严格 */
    static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{2,31}$");
    /** 单个 IP 在窗口内允许的匿名提交次数 */
    static final int PUBLIC_MAX_PER_WINDOW = 20;
    static final long PUBLIC_WINDOW_MS = 10 * 60_000L;

    /** 单次导出上限：CSV 会整份进内存，且要按设计稿字段展开列，过大既慢又难用 */
    static final int EXPORT_MAX_ROWS = 5000;

    // ==================================================================================
    // 设计稿：GET|POST /api/lowcode
    // ==================================================================================

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(lowcodeOf(UiConfigService.getConfig()));
    }

    /** 整包覆盖写：前端提交四类方案的完整列表（缺的类别按空列表处理）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        if (!node.isObject()) return ResponseEntity.status(400).body(Map.of("error", "body 必须是对象"));

        // ⚠️ 只认 KINDS 里登记的四类；其余顶层键静默丢弃（与 dev_notes 一致的白名单口径）
        Map<String, Object> doc = new LinkedHashMap<>();
        for (String kind : KINDS) doc.put(kind, sanitizePlans(node.get(kind)));

        // 发布链接必须唯一：两个表单共用 slug 时，/f/<slug> 该打开哪个是不确定的。
        // 这里显式报错而不是「后者覆盖前者」——静默挑一个会让运营以为发布成功、实际打开的是别人的表单。
        String dup = duplicateSlug(doc.get("forms"));
        if (dup != null) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "发布链接重复：" + dup + "（请给其中一个表单换个链接名）"));
        }

        String json = JsonUtil.write(doc);
        if (json.length() > MAX_LOWCODE_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "内容过大（>" + (MAX_LOWCODE_CHARS / 1_000_000) + "MB），精简方案后再保存"));
        }
        try {
            UiConfigService.update(KEY, doc);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** lowcode 出口：库中无该键 → 四类都是空列表（不懒 seed，低代码不该有内置设计稿） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> lowcodeOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String kind : KINDS) out.put(kind, new ArrayList<>());
        Object o = cfg.get(KEY);
        if (o instanceof Map) {
            for (String kind : KINDS) {
                Object v = ((Map<String, Object>) o).get(kind);
                if (v instanceof List) out.put(kind, v);
            }
        }
        return out;
    }

    // ==================================================================================
    // 运行时：提交记录
    // ==================================================================================

    /** 提交一条表单数据。data 原样存 JSON 字符串（结构是设计稿定义的，后端不做业务校验）。 */
    @PostMapping("/records")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        if (!node.isObject()) return ResponseEntity.status(400).body(Map.of("error", "body 必须是对象"));

        String formId = JsonSanitizer.str(node, "form_id", 64);
        String formName = JsonSanitizer.str(node, "form_name", 128);
        if (formId.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "form_id 不能为空"));
        }
        JsonNode data = node.get("data");
        String dataJson = data == null ? "{}" : JsonUtil.write(clean(data, 0));
        if (dataJson.length() > MAX_STR) {
            return ResponseEntity.status(400).body(Map.of("error", "提交内容过大"));
        }
        long uid = 0;
        Object idObj = user.get("id");
        if (idObj instanceof Number n) uid = n.longValue();
        String uname = String.valueOf(user.getOrDefault("username", ""));
        try {
            LowCodeDao.insertRecord(formId, formName, dataJson, uid, uname);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 提交记录列表。form_id 为空 = 全部表单；limit 默认 50、上限 200；source 可筛 admin/public。 */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> records(@RequestParam(required = false) String form_id,
                                                       @RequestParam(required = false) String limit,
                                                       @RequestParam(required = false) String source,
                                                       HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int n = LowCodeDao.DEFAULT_LIMIT;
        if (limit != null && !limit.isBlank()) {
            try { n = Integer.parseInt(limit.trim()); } catch (NumberFormatException ignored) { /* 用默认 */ }
        }
        n = Math.max(1, Math.min(LowCodeDao.MAX_LIMIT, n));
        String fid = form_id == null ? "" : form_id.trim();
        String src = source == null ? "" : source.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("items", LowCodeDao.listRecords(fid, src, n));
            body.put("total", LowCodeDao.countRecords(fid));
            // 公开提交数单独给：运营只关心外部收回来的数据，不该被后台试填污染统计
            body.put("public_total", fid.isEmpty() ? LowCodeDao.countRecords("", LowCodeDao.SRC_PUBLIC)
                    : LowCodeDao.countRecords(fid, LowCodeDao.SRC_PUBLIC));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "查询失败：" + e));
        }
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/records/{id}")
    public ResponseEntity<Map<String, Object>> deleteRecord(@PathVariable long id,
                                                            HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int n;
        try {
            n = LowCodeDao.deleteRecord(id);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "删除失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true, "deleted", n));
    }

    // ==================================================================================
    // 对外发布：/api/lowcode/public/{slug}
    // ==================================================================================

    /**
     * 取一个**已发布**表单的定义，匿名可读（无 B 端会话、无权限码）。
     *
     * <p>⚠️ 只吐渲染必需的字段，不吐整份方案体：方案里可能有流程/页面等与填写无关的内容，
     * 而且将来若往方案里加内部标注（谁改的、草稿备注），不该顺手泄露给外部。
     */
    @GetMapping("/public/{slug}")
    public ResponseEntity<Map<String, Object>> publicForm(@PathVariable String slug) {
        if (slug == null || !SLUG_RE.matcher(slug).matches()) {
            return ResponseEntity.status(404).body(Map.of("error", "表单不存在"));
        }
        Map<String, Object> form = findPublishedForm(slug);
        if (form == null) {
            return ResponseEntity.status(404).body(Map.of("error", "表单不存在或已停止收集"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("form_id", s(form.get("id")));
        body.put("name", s(form.get("name")));
        body.put("intro", s(form.get("intro")));
        body.put("submit_text", s(form.get("submitText")).isEmpty() ? "提交" : s(form.get("submitText")));
        body.put("fields", form.get("fields") == null ? List.of() : form.get("fields"));
        // 「每人限填一次」：客户端据此在成功页隐藏「再填一份」。真正的拦截在服务端（见 publicSubmit）
        body.put("once", truthy(form.get("once")));
        return ResponseEntity.ok(body);
    }

    /**
     * 匿名提交一份表单。
     *
     * <p>⚠️ <b>服务端必须自己校验一遍</b>：这是无会话端点，任何人都能直接 curl，
     * 前端那套 required/正则只是体验层，绕过它只要一行命令。校验规则与 B 端
     * {@code _lib/core.ts} 的 {@code validateField} 一一对应，那边的改动要同步到这里
     * （反过来不成立：前端可以先于后端支持新规则，因为这里是最终裁决者）。
     */
    @PostMapping("/public/{slug}/submit")
    public ResponseEntity<Map<String, Object>> publicSubmit(@PathVariable String slug,
                                                            @RequestBody(required = false) String raw,
                                                            HttpServletRequest request) {
        if (slug == null || !SLUG_RE.matcher(slug).matches()) {
            return ResponseEntity.status(404).body(Map.of("error", "表单不存在"));
        }
        Map<String, Object> form = findPublishedForm(slug);
        if (form == null) {
            return ResponseEntity.status(404).body(Map.of("error", "表单不存在或已停止收集"));
        }

        // ⚠️ 只存哈希（HmacSHA256 + ANALYTICS_IP_SALT，与埋点同口径）：表单内容可能是报名信息，
        // 再配一份 IP 明文就是可直接定位到人的画像。判重与节流都只用它，够用且不可逆推。
        String ipHash = EventRecorder.ipHash(request);
        String fid = s(form.get("id"));
        if (LowCodeDao.countRecent(fid, ipHash, System.currentTimeMillis() - PUBLIC_WINDOW_MS)
                >= PUBLIC_MAX_PER_WINDOW) {
            return ResponseEntity.status(429).body(Map.of("error", "提交太频繁，请稍后再试"));
        }
        // 「每人限填一次」：先判重再解析 body —— 已经交过的人不该因为格式问题看到一堆字段报错
        if (truthy(form.get("once")) && LowCodeDao.hasSubmitted(fid, ipHash)) {
            return ResponseEntity.status(409).body(Map.of("error", "这个表单每人限填一次，你已经提交过了"));
        }

        // 请求体 = {"data": {字段id: 值}}。刻意包一层 data 而不是裸字段对象：
        // 将来要加验证码 token / 来源标记时不用改结构，裸对象则只能往业务字段里塞保留键。
        Map<String, Object> data;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isObject()) return ResponseEntity.status(400).body(Map.of("error", "body 必须是对象"));
            Object cleaned = clean(node.get("data"), 0);
            if (!(cleaned instanceof Map)) return ResponseEntity.status(400).body(Map.of("error", "data 必须是对象"));
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) cleaned;
            data = m;
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }

        Map<String, String> errs = validateSubmission(form.get("fields"), data);
        if (!errs.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "校验未通过", "errors", errs));
        }

        String dataJson = JsonUtil.write(data);
        if (dataJson.length() > MAX_STR) {
            return ResponseEntity.status(400).body(Map.of("error", "提交内容过大"));
        }
        long rid;
        try {
            // 匿名提交没有用户：created_by 记 0、名字留空（B 端列表显示「匿名」）
            rid = LowCodeDao.insertRecord(fid, s(form.get("name")), dataJson,
                    0L, "", LowCodeDao.SRC_PUBLIC, ipHash);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        // 回 id：填表人没有账号，这个编号是他唯一的凭据（客服核对、自己改口都靠它）
        return ResponseEntity.ok(Map.of("ok", true, "id", rid));
    }

    /** 在已发布的表单里按 slug 查。未发布 / slug 不匹配 / 结构异常一律当「不存在」，不区分原因（避免探测） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findPublishedForm(String slug) {
        Object o = UiConfigService.getConfig().get(KEY);
        if (!(o instanceof Map)) return null;
        Object forms = ((Map<String, Object>) o).get("forms");
        if (!(forms instanceof List)) return null;
        for (Object it : (List<Object>) forms) {
            if (!(it instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) it;
            if (!slug.equals(s(m.get("slug")))) continue;
            if (!truthy(m.get("published"))) continue;
            return m;
        }
        return null;
    }

    /**
     * 服务端校验：返回 {@code 字段id -> 错误文案}，空 map 表示通过。
     * 规则与 B 端 {@code validateField} 对齐：必填 → 类型 → 长度 → 正则；条件隐藏的字段跳过。
     */
    static Map<String, String> validateSubmission(Object fieldsNode, Map<String, Object> data) {
        Map<String, String> errs = new LinkedHashMap<>();
        if (!(fieldsNode instanceof List<?> list)) return errs;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) o;
            String id = s(f.get("id"));
            if (id.isEmpty()) continue;
            if (!visible(f.get("visibleWhen"), data)) continue;
            String e = validateOne(f, data.get(id));
            if (e != null && !e.isEmpty()) errs.put(id, e);
        }
        return errs;
    }

    /** 条件显隐（与 B 端 isVisible 对齐）：不满足则本字段不参与校验 */
    static boolean visible(Object when, Map<String, Object> data) {
        if (!(when instanceof Map)) return true;
        @SuppressWarnings("unchecked") Map<String, Object> w = (Map<String, Object>) when;
        String ref = s(w.get("field"));
        if (ref.isEmpty()) return true;
        Object v = data.get(ref);
        boolean empty = v == null || (v instanceof String str && str.isEmpty())
                || (v instanceof List<?> l && l.isEmpty()) || (v instanceof Map<?, ?> mp && mp.isEmpty());
        String op = s(w.get("op"));
        String target = s(w.get("value"));
        String cur = norm(v);
        return switch (op) {
            case "empty" -> empty;
            case "notEmpty" -> !empty;
            case "eq" -> cur.equals(target);
            case "ne" -> !cur.equals(target);
            case "contains" -> cur.contains(target);
            default -> true;
        };
    }

    /** 单字段校验，通过返回空串 */
    @SuppressWarnings("unchecked")
    static String validateOne(Map<String, Object> f, Object value) {
        Object rulesObj = f.get("rules");
        Map<String, Object> r = rulesObj instanceof Map ? (Map<String, Object>) rulesObj : Map.of();
        // ⚠️ custom 与 msg 必须分开：custom 是设计者自己写的文案，可能为空；
        // 为空时要回落到**具体**文案（"至少 2 个字符"）而不是笼统的"格式不正确" ——
        // 否则用户只知道错了不知道错在哪，等于把规则白配了。
        String custom = s(r.get("message"));
        String msg = custom.isEmpty() ? "格式不正确" : custom;
        boolean required = truthy(f.get("required"));
        if (required && isEmptyVal(value)) return "此项为必填";

        String type = s(f.get("type"));
        if ("subform".equals(type)) {
            // 子表单：行内子字段逐个校验，只把**第一条**错误挂在子表单字段上
            // （公开页的逐行错误展示成本太高，先给可定位的提示，不改数据）
            if (!(value instanceof List<?> rows)) return "";
            int i = 0;
            for (Object row : rows) {
                i++;
                if (!(row instanceof Map)) continue;
                for (Object co : asList(f.get("children"))) {
                    if (!(co instanceof Map)) continue;
                    Map<String, Object> cf = (Map<String, Object>) co;
                    String cid = s(cf.get("id"));
                    if (cid.isEmpty()) continue;
                    String ce = validateOne(cf, ((Map<String, Object>) row).get(cid));
                    if (ce != null && !ce.isEmpty()) {
                        return "第 " + i + " 行「" + s(cf.get("label")) + "」" + ce;
                    }
                }
            }
            return "";
        }
        if (value == null || s(value).isEmpty()) return "";

        if ("number".equals(type)) {
            double d;
            try { d = Double.parseDouble(String.valueOf(value).trim()); } catch (Exception e) { return "必须是数字"; }
            Double min = dbl(r.get("min"));
            Double max = dbl(r.get("max"));
            if (min != null && d < min) return custom.isEmpty() ? "不能小于 " + norm(min) : custom;
            if (max != null && d > max) return custom.isEmpty() ? "不能大于 " + norm(max) : custom;
            return "";
        }
        String str = String.valueOf(value);
        Double minLen = dbl(r.get("minLen"));
        Double maxLen = dbl(r.get("maxLen"));
        if (minLen != null && str.length() < minLen) return custom.isEmpty() ? "至少 " + minLen.intValue() + " 个字符" : custom;
        if (maxLen != null && str.length() > maxLen) return custom.isEmpty() ? "最多 " + maxLen.intValue() + " 个字符" : custom;
        String pattern = s(r.get("pattern"));
        if (!pattern.isEmpty()) {
            try {
                if (!Pattern.compile(pattern).matcher(str).matches()) return msg;
            } catch (Exception ignored) { /* 正则非法就跳过，不阻塞提交（与前端一致） */ }
        }
        return "";
    }

    // ==================================================================================
    // 导出：GET /api/lowcode/records/export
    // ==================================================================================

    /**
     * 把某个表单的提交记录导出成 CSV。
     *
     * <p>为什么必须存在：记录列表一次只显示 50 条、且只能一条条点「详情」看 JSON。
     * 表单真对外收起来（几百份报名）时，那个界面是废的 —— 数据进了库却拿不出来，
     * 等于功能做完了但用不起来。
     *
     * <h3>表头从设计稿取，不是从数据里猜</h3>
     * 每条记录的 {@code data} 是各自的 JSON，键集可能不同（条件显隐会让某些字段缺席）。
     * 若按「出现过的键的并集」列表头，列顺序会随数据漂移，两次导出的表没法对比。
     * 所以列 = 设计稿里字段的定义顺序，缺失的留空 —— 列稳定才是能进 Excel 的表。
     *
     * <p>⚠️ <b>UTF-8 BOM 不能省</b>：Excel（尤其中文 Windows）不带 BOM 会按本地编码解，
     * 中文表头与内容全变乱码。加 BOM 是一行代码，不加就是「导出功能坏了」的工单。
     */
    @GetMapping("/records/export")
    public ResponseEntity<byte[]> exportRecords(@RequestParam(required = false) String form_id,
                                                @RequestParam(required = false) String source,
                                                HttpServletRequest request) {
        // 不是 JSON 端点，所以不复用 AuthUtil.unauth()（那个返回 Map）。导出带全部提交内容，必须鉴权。
        if (AuthUtil.user(request) == null) return ResponseEntity.status(401).body(new byte[0]);
        String fid = form_id == null ? "" : form_id.trim();
        String src = source == null ? "" : source.trim();

        List<Map<String, Object>> rows;
        try {
            rows = LowCodeDao.listRecordsAsc(fid, src, EXPORT_MAX_ROWS);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(("查询失败：" + e).getBytes(StandardCharsets.UTF_8));
        }

        // 设计稿里的字段定义（列）；找不到对应表单就退化成「只导元数据 + 一列 JSON」
        List<Map<String, Object>> defs = fieldDefsOf(fid);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');   // BOM
        csvRow(sb, headerOf(defs));
        for (Map<String, Object> r : rows) {
            Map<String, Object> d = parseData(s(r.get("data")));
            List<String> cells = new ArrayList<>();
            cells.add(s(r.get("id")));
            cells.add(s(r.get("created_at")));
            cells.add("public".equals(s(r.get("source"))) ? "公开" : "内部");
            cells.add(s(r.get("created_by_name")));
            for (Map<String, Object> f : defs) cells.add(csvValue(d.get(s(f.get("id")))));
            if (defs.isEmpty()) cells.add(s(r.get("data")));
            csvRow(sb, cells);
        }

        String name = (fid.isEmpty() ? "all" : fid).replaceAll("[^A-Za-z0-9_-]", "_");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        h.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"lowcode-" + name + ".csv\"");
        return new ResponseEntity<>(sb.toString().getBytes(StandardCharsets.UTF_8), h, 200);
    }

    private static List<String> headerOf(List<Map<String, Object>> defs) {
        List<String> h = new ArrayList<>(List.of("编号", "提交时间", "来源", "提交人"));
        for (Map<String, Object> f : defs) h.add(s(f.get("label")).isEmpty() ? s(f.get("id")) : s(f.get("label")));
        if (defs.isEmpty()) h.add("内容 JSON");
        return h;
    }

    /** 设计稿里某表单的字段定义（按定义顺序）。找不到 / 结构异常 → 空列表（调用方退化） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldDefsOf(String formId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (formId.isEmpty()) return out;
        Object o = UiConfigService.getConfig().get(KEY);
        if (!(o instanceof Map)) return out;
        Object forms = ((Map<String, Object>) o).get("forms");
        if (!(forms instanceof List)) return out;
        for (Object it : (List<Object>) forms) {
            if (!(it instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) it;
            if (!formId.equals(s(m.get("id")))) continue;
            for (Object f : asList(m.get("fields"))) {
                if (f instanceof Map) out.add((Map<String, Object>) f);
            }
            return out;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseData(String json) {
        try {
            Object o = JsonUtil.parse(json == null || json.isEmpty() ? "{}" : json);
            Object c = clean((JsonNode) o, 0);
            return c instanceof Map ? (Map<String, Object>) c : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 单元格取值：数组/对象转 JSON，null 留空（子表单是一组行，塞进一格只能是 JSON） */
    private static String csvValue(Object v) {
        if (v == null) return "";
        if (v instanceof List || v instanceof Map) return JsonUtil.write(v);
        return s(v);
    }

    private static void csvRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escapeCsv(cells.get(i)));
        }
        sb.append("\r\n");   // CRLF：Excel 对纯 LF 的换行处理不一致
    }

    /** 含逗号/引号/换行必须加引号，且内部引号翻倍 —— 否则整行会串列 */
    static String escapeCsv(String v) {
        if (v == null) return "";
        boolean need = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        String out = v.replace("\"", "\"\"");
        return need ? "\"" + out + "\"" : out;
    }

    // ==================================================================================
    // 净化
    // ==================================================================================

    // ---- 小工具（发布相关，放这里避免与上面的净化逻辑交叉） ----

    static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 宽类型真值判定：Boolean / Number / "1" / "true"（不同驱动与 JSON 反序列化结果不一致） */
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String t = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "1".equals(t) || "true".equals(t) || "yes".equals(t);
    }

    static boolean isEmptyVal(Object v) {
        if (v == null) return true;
        if (v instanceof String str) return str.isEmpty();
        if (v instanceof List<?> l) return l.isEmpty();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    /** 数字归一化：3.0 → "3"，与前端 String(v) 的口径对齐（否则 eq 比较 3 与 "3" 会不等） */
    static String norm(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isInfinite(d) && d == Math.rint(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    static Double dbl(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** 表单列表里重复的 slug（空 slug 不参与）。返回 null 表示无重复 */
    static String duplicateSlug(Object forms) {
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : asList(forms)) {
            if (!(o instanceof Map)) continue;
            String slug = s(((Map<String, Object>) o).get("slug"));
            if (slug.isEmpty()) continue;
            if (!seen.add(slug)) return slug;
        }
        return null;
    }

    /**
     * 方案列表：每项保留 id / name / updated_at（精确净化），其余键递归结构净化。
     * id 重复或为空的项直接丢 —— 前端选择器靠 id 定位，重复 id 会让「切换方案」选错。
     */
    static List<Map<String, Object>> sanitizePlans(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode p : node) {
            if (out.size() >= MAX_PLANS_PER_KIND) break;
            if (p == null || !p.isObject()) continue;
            String id = JsonSanitizer.str(p, "id", 64);
            if (id.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", JsonSanitizer.str(p, "name", 100));
            m.put("updated_at", JsonSanitizer.str(p, "updated_at", 32));
            // 其余键（fields / nodes / table / modelId / list / form …）按结构净化原样保留
            int keys = 0;
            var it = p.fields();
            while (it.hasNext()) {
                var e = it.next();
                String k = e.getKey();
                if (k == null || k.isEmpty() || k.length() > 64) continue;
                if (k.equals("id") || k.equals("name") || k.equals("updated_at")) continue;
                if (keys++ >= MAX_KEYS) break;
                Object v = clean(e.getValue(), 0);
                if (v != null) m.put(k, v);
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 递归结构净化：只留 JSON 原生类型，越界的数组/对象/字符串截断而不是整包拒绝
     * （配到一半是正常中间态，不该让用户白干 —— 与摸金「给警告而不是拒绝保存」同一取舍）。
     *
     * @return 净化后的值；null 表示该节点被丢弃（null / 超深度）
     */
    static Object clean(JsonNode n, int depth) {
        if (n == null || n.isNull()) return null;
        if (depth > MAX_DEPTH) return null;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.isIntegralNumber() ? (Object) n.asLong() : (Object) n.asDouble();
        if (n.isTextual()) return JsonSanitizer.truncate(n.asText(), MAX_STR);
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode c : n) {
                if (out.size() >= MAX_ARRAY) break;
                Object v = clean(c, depth + 1);
                if (v != null) out.add(v);
            }
            return out;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            int keys = 0;
            var it = n.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (keys++ >= MAX_KEYS) break;
                String k = e.getKey();
                if (k == null || k.isEmpty() || k.length() > 64) continue;
                Object v = clean(e.getValue(), depth + 1);
                if (v != null) out.put(k, v);
            }
            return out;
        }
        return null;
    }
}
