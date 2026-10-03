package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.GameSaveDao;
import com.notelab.service.EventRecorder;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端游戏：登录管理配置 + 用户存档。
 *
 * <p>路由前缀 {@code /api/c/game} 属 C 端体系，不参与 B 端接口门禁（PermGuard 对 /api/c/ 豁免）。
 * <ul>
 *   <li>{@code GET /api/c/game/access}：<b>匿名</b>读取「哪些游戏需要登录才能玩」(ui_config.game_access)。
 *       C 端游戏页据此决定是否挂登录墙；缺省视为游客可玩。</li>
 *   <li>{@code GET/POST /api/c/game/save}：<b>仅已登录 C 端用户</b>（CAuthUtil 守卫）。
 *       按 (user_id, game_code) 读写各游戏自有 JSON 存档，登录态数据落 MySQL，
 *       游客态由各游戏自行存 localStorage（用户决策：两者独立、不合并）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/c/game")
public class GameSaveController {

    /** 匿名：返回 game_access 配置（Map<gameCode, {requireLogin}>） */
    @GetMapping("/access")
    public ResponseEntity<Map<String, Object>> access() {
        Map<String, Object> cfg = UiConfigService.getConfig();
        Object ga = cfg.get("game_access");
        Map<String, Object> gameAccess = (ga instanceof Map<?, ?>) ? castMap(ga) : Map.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_access", gameAccess);
        return ResponseEntity.ok(body);
    }

    /** 登录用户：读取自己的某游戏存档 */
    @GetMapping("/save")
    public ResponseEntity<Map<String, Object>> load(@RequestParam("game") String game,
                                                    HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> row = GameSaveDao.get(CAuthUtil.userId(user), game);
        Object data = (row == null) ? null : row.get("data_json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data == null ? null : data);
        return ResponseEntity.ok(body);
    }

    /** 登录用户：保存某游戏存档（data 为各游戏自有 JSON 对象） */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        if (!node.isObject() || !node.has("game")
                || node.get("game").asText().isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "game 必填且不能为空"));
        }
        String game = node.get("game").asText();
        if (game.length() > 32) {
            return ResponseEntity.status(400).body(Map.of("error", "game 过长"));
        }
        // data 任意 JSON（对象/数组/字符串），原样落库
        JsonNode dataNode = node.has("data") ? node.get("data") : JsonUtil.MAPPER.createObjectNode();
        String dataJson = dataNode.isNull() ? "{}" : dataNode.toString();
        GameSaveDao.upsert(CAuthUtil.userId(user), game, dataJson);
        // 服务端旁路埋点（PRD-P0 §4.3）：存档写入是「真在玩」的强信号，客户端拿不到可靠口径
        EventRecorder.record("c", "game_save", CAuthUtil.userId(user), request,
                Map.of("game_code", game));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
