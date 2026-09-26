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
 *  - GET /api/c/spire/content     → 已发布的 Spire 工坊内容 spire_published
 *                                   （未发布返回空数组 + 空 charAccess/assets/maps）。
 *
 * charAccess（角色授权白名单 {组码:[角色 id...]}）随发布快照一起透出，C 端角色选择页据此按登录用户
 * 所属组做前置筛选；匿名/未配置的组 fail-open（不筛选）。
 *
 * assets（素材槽位 {槽位 key: 素材路径}）与 maps（地图方案 {defaultId, packs:[...]}）同样随发布快照透出：
 *  - assets 缺失/空 → C 端所有节点与连线走内置默认素材；
 *  - maps 缺失/空或对应幕不存在 → C 端回落本地随机生成（保证"没配置也能玩"）。
 * 两者的 fail-open 判定都在 C 端做，后端只负责原样透传，不在这里兜底成默认值。
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
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put("cards", m.getOrDefault("cards", List.of()));
            out.put("characters", m.getOrDefault("characters", List.of()));
            out.put("skills", m.getOrDefault("skills", List.of()));
            Object ca = m.get("charAccess");
            out.put("charAccess", ca instanceof Map ? ca : Map.of());
            Object as = m.get("assets");
            out.put("assets", as instanceof Map ? as : Map.of());
            Object mp = m.get("maps");
            out.put("maps", mp instanceof Map ? mp : Map.of("packs", List.of()));
            return ResponseEntity.ok(out);
        }
        out.put("cards", List.of());
        out.put("characters", List.of());
        out.put("skills", List.of());
        out.put("charAccess", Map.of());
        out.put("assets", Map.of());
        out.put("maps", Map.of("packs", List.of()));
        return ResponseEntity.ok(out);
    }
}
