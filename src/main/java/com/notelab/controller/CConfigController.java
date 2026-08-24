package com.notelab.controller;

import com.notelab.service.UiConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端配置（B/C 拆分阶段2）：匿名开放（C 端外壳首屏与登录页在登录前就要用），不走 CAuthUtil。
 *  - GET /api/c/config/background → ui_config 的 background 节点（为空时回退 UiConfigService 默认 background）；
 *  - GET /api/c/spire/content     → 已发布的 Spire 工坊内容 spire_published（未发布返回空三数组）。
 */
@RestController
@RequestMapping("/api/c")
public class CConfigController {

    @GetMapping("/config/background")
    public ResponseEntity<Map<String, Object>> background() {
        Map<String, Object> cfg = UiConfigService.getConfig();
        Object bg = cfg.get("background");
        if (!(bg instanceof Map)) {
            bg = UiConfigService.defaultConfig().get("background");
        }
        return ResponseEntity.ok(Map.of("background", bg));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/spire/content")
    public ResponseEntity<Map<String, Object>> spireContent() {
        Map<String, Object> cfg = UiConfigService.getConfig();
        Object o = cfg.get("spire_published");
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cards", m.getOrDefault("cards", List.of()));
            out.put("characters", m.getOrDefault("characters", List.of()));
            out.put("skills", m.getOrDefault("skills", List.of()));
            return ResponseEntity.ok(out);
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("cards", List.of());
        empty.put("characters", List.of());
        empty.put("skills", List.of());
        return ResponseEntity.ok(empty);
    }
}
