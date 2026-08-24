package com.notelab.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.TrpgDao;
import com.notelab.service.TrpgService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端 TRPG 游玩（B/C 拆分阶段2）：前缀 /api/c/trpg，全部经 CAuthUtil（notelab_c_session）。
 * 数据域隔离：仅可开局 published=1 的剧本；新存档 scope='c'、user_id=C 端 uid；
 * 存档查询/操作一律 scope='c' AND user_id=当前 C 用户，不属于自己的存档 404。
 * 已开局后剧本被下架：存量存档可继续游玩（不中断玩家），仅新开局受限。
 * 引擎逻辑复用 TrpgService（与 B 端同一套），响应结构与 B 端同风格（RowUtil）。
 */
@RestController
@RequestMapping("/api/c/trpg")
public class CTrpgController {

    public static class ChooseReq {
        public String choice_id;
    }

    // ================= 剧本（仅已发布） =================

    @GetMapping("/scenarios")
    public ResponseEntity<?> list(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        return ResponseEntity.ok(Map.of("scenarios", TrpgDao.listPublishedTrpgScenarios()));
    }

    @GetMapping("/scenarios/{id}")
    public ResponseEntity<?> detail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> row = TrpgDao.getTrpgScenario(id);
        if (row == null || !isPublished(row)) return ResponseEntity.status(404).body(Map.of("error", "剧本不存在"));
        try {
            ObjectNode sc = (ObjectNode) JsonUtil.parse(String.valueOf(row.get("scenario_json")));
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.remove("scenario_json");
            out.remove("config_json");
            out.put("scenario", TrpgService.toMap(sc));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "剧本数据损坏"));
        }
    }

    @PostMapping("/scenarios/{id}/play")
    public ResponseEntity<?> startPlay(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> row = TrpgDao.getTrpgScenario(id);
        if (row == null || !isPublished(row)) return ResponseEntity.status(404).body(Map.of("error", "剧本不存在"));
        try {
            ObjectNode sc = (ObjectNode) JsonUtil.parse(String.valueOf(row.get("scenario_json")));
            long pid = TrpgDao.createTrpgPlay(id, CAuthUtil.userId(user), TrpgService.startNodeId(sc), "c");
            return ResponseEntity.ok(TrpgService.playPayload(TrpgDao.getTrpgPlay(pid), sc));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "开局失败"));
        }
    }

    // ================= 存档（仅本人 scope='c'） =================

    @GetMapping("/plays")
    public ResponseEntity<?> listPlays(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        return ResponseEntity.ok(Map.of("plays", TrpgDao.listTrpgPlays("c", CAuthUtil.userId(user))));
    }

    @GetMapping("/plays/{id}")
    public ResponseEntity<?> playDetail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> play = ownedCPlay(id, user);
        if (play == null) return ResponseEntity.status(404).body(Map.of("error", "对局不存在"));
        return TrpgService.scenarioOfPlay(play)
                .map(sc -> ResponseEntity.ok((Object) TrpgService.playPayload(play, sc)))
                .orElseGet(() -> ResponseEntity.status(500).body(Map.of("error", "剧本数据损坏")));
    }

    @PostMapping("/plays/{id}/choose")
    public ResponseEntity<?> choose(@PathVariable long id, @RequestBody(required = false) ChooseReq req, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> play = ownedCPlay(id, user);
        if (play == null) return ResponseEntity.status(404).body(Map.of("error", "对局不存在"));
        java.util.Optional<ObjectNode> optSc = TrpgService.scenarioOfPlay(play);
        if (optSc.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "剧本数据损坏"));
        try {
            return ResponseEntity.ok(TrpgService.applyChoice(play, optSc.get(), req == null ? null : req.choice_id));
        } catch (TrpgService.ChooseException e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/plays/{id}")
    public ResponseEntity<?> deletePlay(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> play = ownedCPlay(id, user);
        if (play == null) return ResponseEntity.status(404).body(Map.of("error", "对局不存在"));
        TrpgDao.deleteTrpgPlay(id, CAuthUtil.userId(user));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 工具 =================

    /** 存档归属校验：scope='c' 且属于当前 C 用户；不满足返回 null（对外 404）。剧本下架不影响存量存档。 */
    private static Map<String, Object> ownedCPlay(long id, Map<String, Object> user) {
        Map<String, Object> play = TrpgDao.getTrpgPlay(id);
        if (play == null || !"c".equals(play.get("scope"))) return null;
        Object uid = play.get("user_id");
        if (uid == null || ((Number) uid).longValue() != CAuthUtil.userId(user)) return null;
        return play;
    }

    private static boolean isPublished(Map<String, Object> row) {
        Object p = row.get("published");
        return p != null && ((Number) p).intValue() == 1;
    }
}
