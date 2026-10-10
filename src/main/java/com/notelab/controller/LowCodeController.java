package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonSanitizer;
import com.notelab.common.JsonUtil;
import com.notelab.dao.LowCodeDao;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 提交记录列表。form_id 为空 = 全部表单；limit 默认 50、上限 200。 */
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> records(@RequestParam(required = false) String form_id,
                                                       @RequestParam(required = false) String limit,
                                                       HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int n = LowCodeDao.DEFAULT_LIMIT;
        if (limit != null && !limit.isBlank()) {
            try { n = Integer.parseInt(limit.trim()); } catch (NumberFormatException ignored) { /* 用默认 */ }
        }
        n = Math.max(1, Math.min(LowCodeDao.MAX_LIMIT, n));
        String fid = form_id == null ? "" : form_id.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("items", LowCodeDao.listRecords(fid, n));
            body.put("total", LowCodeDao.countRecords(fid));
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
    // 净化
    // ==================================================================================

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
