package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.UiConfigDao;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 爬塔尖塔内容工坊：GET|POST /api/spire-content。
 * 自定义卡/角色/技能模板存 ui_config JSON 的 "spire" 键（{cards:[], characters:[], skills:[]}），不新建表；
 * 前端引擎在加载时做净化与注册，这里只做结构与体积校验。
 *
 * 角色授权（spire 第 4 键 charAccess）：{组码: [可选角色 id...]}，C 端角色选择页按登录用户所属
 * c_user_groups.code 做前置筛选；缺失或该组无键时 fail-open（不筛选）。GET 额外回带 baseCharacters
 * 常量（镜像 C 端引擎 BASE_CHARACTERS），供 B 端授权界面展示内置角色。
 */
@RestController
@RequestMapping("/api/spire-content")
public class SpireContentController {

    /**
     * 内置基础角色清单（只读镜像）：内容 = notelab-c/lib/spire-engine.ts 的 BASE_CHARACTERS
     * （id / name / icon）。B 端「角色授权」界面要展示"全量可选角色池 = 基础角色 + 工坊自定义角色"，
     * 但 B 端仓库没有引擎代码，故由后端提供这份常量。
     * ⚠️ 新增/改名内置角色时必须同步此处，否则 B 端授权界面看不到该角色（无法勾进白名单）。
     */
    private static final List<Map<String, Object>> BASE_CHARACTERS = List.of(
            Map.of("id", "blade", "name", "刃影", "icon", "🥷"),
            Map.of("id", "guard", "name", "铁壁守卫", "icon", "🛡️"),
            Map.of("id", "mage", "name", "秘法编织者", "icon", "🔮"),
            Map.of("id", "wuzhuge", "name", "武诸葛", "icon", "🪶"));

    /**
     * spire JSON 体积上限（字符）。原来的 200KB 是按「只有卡/角色」估的；
     * 地图方案是自包含的整图节点表（一套 3 幕约 12KB），把上限提到 1MB 才够存几套。
     * 库里是 mediumtext（16MB），1MB 距上限很远；再大就该走对象存储而不是配置表。
     */
    private static final int MAX_SPIRE_CHARS = 1_000_000;
    /** 地图方案套数上限 */
    private static final int MAX_PACKS = 20;
    /** 单套方案幕数上限（本项目 3 幕，留点余量） */
    private static final int MAX_ACTS = 8;
    /** 单幕节点数上限（16 层 × 最多 ~6 列 ≈ 100，留足余量） */
    private static final int MAX_NODES = 4000;
    /** 素材路径长度上限（只是个 URL/相对路径，防呆） */
    private static final int MAX_ASSET_PATH = 500;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> body = spireOf(UiConfigService.getConfig());
        // 只读常量，供 B 端授权界面展示内置角色；不落库（publish 快照走 spireOf，不含该键）
        body.put("baseCharacters", BASE_CHARACTERS);
        return ResponseEntity.ok(body);
    }

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
        Map<String, Object> spire = new LinkedHashMap<>();
        spire.put("cards", node.get("cards") != null && node.get("cards").isArray()
                ? JsonUtil.MAPPER.convertValue(node.get("cards"), List.class) : List.of());
        spire.put("characters", node.get("characters") != null && node.get("characters").isArray()
                ? JsonUtil.MAPPER.convertValue(node.get("characters"), List.class) : List.of());
        spire.put("skills", node.get("skills") != null && node.get("skills").isArray()
                ? JsonUtil.MAPPER.convertValue(node.get("skills"), List.class) : List.of());
        // 角色授权白名单：{组码: [角色 id...]}，类型净化后与三键一起写库
        spire.put("charAccess", sanitizeCharAccess(node.get("charAccess")));
        // 素材资源槽位：{槽位 key: C 端素材路径}；未配置的槽位 C 端回落内置默认
        spire.put("assets", sanitizeAssets(node.get("assets")));
        // 地图方案：{defaultId, packs:[{id,name,params,acts:[{act,layers,nodes}]}]}；无方案时 C 端本地生成
        spire.put("maps", sanitizeMaps(node.get("maps")));
        String spireJson = JsonUtil.write(spire);
        if (spireJson.length() > MAX_SPIRE_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "自定义内容过大（>" + (MAX_SPIRE_CHARS / 1000) + "KB，多半是地图方案存太多，删几套再保存）"));
        }
        // 合并进现有 ui_config（保留 background/menus 等其它键）
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.put("spire", spire);
        try {
            UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * B/C 拆分阶段2：发布——把当前 spire 工坊内容整体快照写入顶层键 spire_published
     * （合并写，保留 background/menus/spire 等其它键，写法同 save）。
     */
    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.put("spire_published", spireOf(cfg));
        try {
            UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** B/C 拆分阶段2：下架——删除 spire_published 键（合并写，保留其它键） */
    @PostMapping("/unpublish")
    public ResponseEntity<Map<String, Object>> unpublish(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.remove("spire_published");
        try {
            UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** spire 出口（GET 与 publish 快照共用）：cards / characters / skills / charAccess / assets / maps */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> spireOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", List.of());
        out.put("characters", List.of());
        out.put("skills", List.of());
        out.put("charAccess", new LinkedHashMap<String, List<String>>());
        out.put("assets", new LinkedHashMap<String, String>());
        out.put("maps", Map.of("packs", List.of()));
        Object o = cfg.get("spire");
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put("cards", m.getOrDefault("cards", List.of()));
            out.put("characters", m.getOrDefault("characters", List.of()));
            out.put("skills", m.getOrDefault("skills", List.of()));
            Object ca = m.get("charAccess");
            if (ca instanceof Map) out.put("charAccess", ca);
            Object as = m.get("assets");
            if (as instanceof Map) out.put("assets", as);
            Object mp = m.get("maps");
            if (mp instanceof Map) out.put("maps", mp);
        }
        return out;
    }

    /**
     * charAccess 类型净化：非对象→{}；值非数组→[]；数组元素只保留非空字符串（trim 去重）。
     * 键为 C 端用户组码（c_user_groups.code），值是该组可选择的角色 id 白名单。
     */
    private static Map<String, List<String>> sanitizeCharAccess(JsonNode node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            String code = e.getKey() == null ? "" : e.getKey().trim();
            if (code.isEmpty()) return;
            List<String> ids = new java.util.ArrayList<>();
            JsonNode v = e.getValue();
            if (v != null && v.isArray()) {
                for (JsonNode it : v) {
                    if (it == null || !it.isTextual()) continue;
                    String id = it.asText().trim();
                    if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
                }
            }
            out.put(code, ids);
        });
        return out;
    }

    /**
     * 素材槽位净化：只保留 {非空字符串键: 非空字符串值}，值 trim、超长的丢弃。
     * 空值直接**丢掉而不是存空串** —— 语义上「未配置 = 回落内置默认」，
     * 存空串会让文档里堆一堆无意义键，也让 B 端的「文档指纹」在保存前后不一致。
     *
     * <p>值形如 `/games/spire/art/icon-normal.png`：**带 C 端 basePath 前缀**，
     * 由 B 端从素材清单接口拿到的 url 原样写入，后端不校验其可解析性（C 端会做 fail-open）。
     */
    private static Map<String, String> sanitizeAssets(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (k.isEmpty() || k.length() > 120) return;
            JsonNode v = e.getValue();
            if (v == null || !v.isTextual()) return;
            String val = v.asText().trim();
            if (val.isEmpty() || val.length() > MAX_ASSET_PATH) return;
            out.put(k, val);
        });
        return out;
    }

    /**
     * 地图方案净化：结构不对的**幕**丢弃，没有可用幕的**方案**丢弃。
     *
     * <p>刻意做得比 C 端轻：地图语义（不交叉 / 无死路 / 唯一 BOSS …）的裁决权在生成器与 C 端加载器，
     * 后端只保证「形状合法 + 体积可控」，避免后端变成第二套地图规则真相源。
     */
    private static Map<String, Object> sanitizeMaps(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> packs = new java.util.ArrayList<>();
        if (node == null || !node.isObject()) {
            out.put("defaultId", "");
            out.put("packs", packs);
            return out;
        }
        JsonNode arr = node.get("packs");
        if (arr != null && arr.isArray()) {
            for (JsonNode p : arr) {
                if (packs.size() >= MAX_PACKS) break;
                if (p == null || !p.isObject()) continue;
                Map<String, Object> pack = new LinkedHashMap<>();
                pack.put("id", str(p, "id"));
                pack.put("name", str(p, "name"));
                pack.put("createdAt", str(p, "createdAt"));
                JsonNode params = p.get("params");
                if (params != null && params.isObject()) {
                    pack.put("params", JsonUtil.MAPPER.convertValue(params, Map.class));
                }
                List<Object> acts = new java.util.ArrayList<>();
                JsonNode actsNode = p.get("acts");
                if (actsNode != null && actsNode.isArray()) {
                    for (JsonNode a : actsNode) {
                        if (acts.size() >= MAX_ACTS) break;
                        Map<String, Object> act = sanitizeAct(a);
                        if (act != null) acts.add(act);
                    }
                }
                if (acts.isEmpty()) continue;   // 没有一幕可用 → 整个方案丢掉
                pack.put("acts", acts);
                packs.add(pack);
            }
        }
        out.put("defaultId", str(node, "defaultId"));
        out.put("packs", packs);
        return out;
    }

    /** 单幕净化：nodes 必须是数组且每个元素形状合法（id/row/col/type/next）；不合法返回 null */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeAct(JsonNode a) {
        if (a == null || !a.isObject()) return null;
        JsonNode nodes = a.get("nodes");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty() || nodes.size() > MAX_NODES) return null;
        for (JsonNode n : nodes) {
            if (n == null || !n.isObject()) return null;
            JsonNode id = n.get("id"), row = n.get("row"), col = n.get("col"), type = n.get("type"), next = n.get("next");
            if (id == null || !id.isTextual() || id.asText().isEmpty()) return null;
            if (row == null || !row.isNumber() || col == null || !col.isNumber()) return null;
            if (type == null || !type.isTextual()) return null;
            if (next == null || !next.isArray()) return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("act", a.get("act") != null && a.get("act").isNumber() ? a.get("act").asInt() : 1);
        out.put("layers", a.get("layers") != null && a.get("layers").isNumber() ? a.get("layers").asInt() : nodes.size());
        out.put("nodes", JsonUtil.MAPPER.convertValue(nodes, List.class));
        return out;
    }

    /** 取字符串字段，缺省为空串（避免 null 污染 JSON） */
    private static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }
}
