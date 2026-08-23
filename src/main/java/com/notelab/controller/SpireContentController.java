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
 */
@RestController
@RequestMapping("/api/spire-content")
public class SpireContentController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(spireOf(UiConfigService.getConfig()));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spireOf(Map<String, Object> cfg) {
        Object o = cfg.get("spire");
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cards", m.getOrDefault("cards", List.of()));
            out.put("characters", m.getOrDefault("characters", List.of()));
            out.put("skills", m.getOrDefault("skills", List.of()));
            return out;
        }
        return Map.of("cards", List.of(), "characters", List.of(), "skills", List.of());
    }
}
