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
        String spireJson = JsonUtil.write(spire);
        if (spireJson.length() > 200_000) return ResponseEntity.status(400).body(Map.of("error", "自定义内容过大（>200KB）"));
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

    /** spire 四键出口（GET 与 publish 快照共用）：cards / characters / skills / charAccess */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> spireOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", List.of());
        out.put("characters", List.of());
        out.put("skills", List.of());
        out.put("charAccess", new LinkedHashMap<String, List<String>>());
        Object o = cfg.get("spire");
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put("cards", m.getOrDefault("cards", List.of()));
            out.put("characters", m.getOrDefault("characters", List.of()));
            out.put("skills", m.getOrDefault("skills", List.of()));
            Object ca = m.get("charAccess");
            if (ca instanceof Map) out.put("charAccess", ca);
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
}
