package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.dao.Db;
import com.notelab.common.JsonUtil;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 界面配置：GET|POST /api/ui-config（30s 缓存，保存后失效，逻辑与 Python 版一致） */
@RestController
@RequestMapping("/api/ui-config")
public class UiConfigController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", UiConfigService.getConfig());
        body.put("defaults", UiConfigService.defaultConfig());
        return ResponseEntity.ok(body);
    }

    /** 原始字符串入参：非法 JSON 需返回 400 {"error":"请求体不是合法 JSON"}（与 Python 一致） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        JsonNode bodyNode;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            bodyNode = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        // cfg = body.get("config", body)
        JsonNode cfgNode = bodyNode.has("config") ? bodyNode.get("config") : bodyNode;
        if (!cfgNode.isObject()) {
            return ResponseEntity.status(400).body(Map.of("error", "config 必须是对象"));
        }
        Map<String, Object> defaults = UiConfigService.defaultConfig();
        // 本次要覆盖的键（其余顶层键，如 spire/loot 工坊内容，由 updateAll 保留）
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("background", cfgNode.has("background")
                ? JsonUtil.MAPPER.convertValue(cfgNode.get("background"), LinkedHashMap.class)
                : defaults.get("background"));
        patch.put("menus", cfgNode.has("menus")
                ? JsonUtil.MAPPER.convertValue(cfgNode.get("menus"), LinkedHashMap.class)
                : defaults.get("menus"));
        // 游戏登录管理配置：仅当入参携带 game_access 时覆盖（其余情况保留库内已有值）
        if (cfgNode.has("game_access")) {
            patch.put("game_access", JsonUtil.MAPPER.convertValue(cfgNode.get("game_access"), LinkedHashMap.class));
        }
        try {
            UiConfigService.updateAll(patch);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
