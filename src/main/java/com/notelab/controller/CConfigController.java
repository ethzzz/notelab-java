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
            out.put("enemies", m.getOrDefault("enemies", List.of()));
            out.put("balance", m.getOrDefault("balance", Map.of()));
            out.put("mapRules", m.getOrDefault("mapRules", Map.of()));
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
        out.put("enemies", List.of());
        out.put("balance", Map.of());
        out.put("mapRules", Map.of());
        out.put("charAccess", Map.of());
        out.put("assets", Map.of());
        out.put("maps", Map.of("packs", List.of()));
        return ResponseEntity.ok(out);
    }

    /**
     * 摸金行动（Loot Raid）：GET /api/c/loot/content → 已发布的 loot_published 快照。
     * 六切片（rarities / items / containers / tables / maps / balance）未发布时**各回空**，
     * C 端据此回落内置默认包。
     *
     * <p>⚠️ rarities 是 2026-10-06 新增的第六切片：档位（种类/颜色/每格基准价）由后台配置，
     * 漏透传的话 C 端会一直用内置五档 —— 后台加的档位在前台"存在但不生效"，且不报错。
     *
     * <p>与 spire 同一口径：后端只负责原样透传，fail-open 的判定在 C 端（lib/loot-content.ts），
     * 不在这里兜底成默认值 —— 否则"未发布"与"发布了一套空内容"在 C 端看来无法区分。
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/loot/content")
    public ResponseEntity<Map<String, Object>> lootContent() {
        Map<String, Object> cfg = UiConfigService.getConfig();
        Object o = cfg.get("loot_published");
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put("rarities", m.getOrDefault("rarities", List.of()));
            out.put("items", m.getOrDefault("items", List.of()));
            out.put("containers", m.getOrDefault("containers", List.of()));
            out.put("tables", m.getOrDefault("tables", List.of()));
            out.put("maps", m.getOrDefault("maps", List.of()));
            out.put("balance", m.getOrDefault("balance", Map.of()));
            return ResponseEntity.ok(out);
        }
        out.put("rarities", List.of());
        out.put("items", List.of());
        out.put("containers", List.of());
        out.put("tables", List.of());
        out.put("maps", List.of());
        out.put("balance", Map.of());
        return ResponseEntity.ok(out);
    }
}
