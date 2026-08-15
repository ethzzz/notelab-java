package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notelab.common.AppConfig;
import com.notelab.dao.Db;
import com.notelab.common.JsonUtil;
import com.notelab.infra.QwenClient;
import com.notelab.service.RateLimit;
import com.notelab.service.TrpgService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PostConstruct;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.notelab.dao.TrpgDao;

/** TRPG 跑团：AI 剧本生成 + 剧本游玩 */
@RestController
@RequestMapping("/api/trpg")
public class TrpgController {

    public static class ScenarioReq {
        public String title;
        public String background;
        public String characters;
        public String places;
        public String event;
        public String style;
        public String scale;
    }

    public static class ChooseReq {
        public String choice_id;
    }

    /** 后台生成线程池：生成耗时长（1-3 分钟），异步执行避免长 HTTP 连接被代理层超时断开 */
    private static final ExecutorService GEN_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "trpg-gen");
        t.setDaemon(true);
        return t;
    });

    /** 启动时把上次进程遗留的 running 任务标记为中断 */
    @PostConstruct
    void abortStaleTasks() {
        try { TrpgDao.abortStaleTrpgGenTasks(); } catch (Exception ignored) {}
    }

    // ================= 剧本 =================

    @GetMapping("/scenarios")
    public ResponseEntity<?> list(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        return ResponseEntity.ok(Map.of("scenarios", TrpgDao.listTrpgScenarios(AuthUtil.userId(user))));
    }

    /**
     * 生成剧本（异步任务）：立即返回 task_id，后台线程调模型生成，前端轮询 /scenarios/tasks/{id}。
     * 彻底规避 1-3 分钟长请求被 Next 代理层（默认 ~30s）超时断开导致的 500。
     */
    @PostMapping("/scenarios")
    public ResponseEntity<?> generate(@RequestBody(required = false) ScenarioReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "未配置 QWEN_API_KEY"));
        if (req == null || (isBlank(req.background) && isBlank(req.event) && isBlank(req.characters))) {
            return ResponseEntity.status(422).body(Map.of("error", "请至少填写背景、人物或核心事件之一"));
        }
        String ip = AuthUtil.clientIp(request);
        if (!RateLimit.rateOk("trpg-gen:" + ip, 6, 300)) {
            return ResponseEntity.status(429).body(Map.of("error", "生成过于频繁，请 5 分钟后再试"));
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("title", nz(req.title));
        cfg.put("background", nz(req.background));
        cfg.put("characters", nz(req.characters));
        cfg.put("places", nz(req.places));
        cfg.put("event", nz(req.event));
        cfg.put("style", nz(req.style));
        cfg.put("scale", nz(req.scale));
        long taskId = TrpgDao.createTrpgGenTask(AuthUtil.userId(user), JsonUtil.write(cfg));
        long userId = AuthUtil.userId(user);
        GEN_POOL.submit(() -> runGenTask(taskId, userId, cfg, key));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", taskId);
        out.put("state", "running");
        return ResponseEntity.accepted().body(out);
    }

    /** 后台执行：调模型 → 解析修复 → 入库 → 更新任务状态；任何异常都落到任务 error 状态 */
    private static void runGenTask(long taskId, long userId, Map<String, Object> cfg, String key) {
        try {
            StringBuilder full = new StringBuilder();
            // 流式调用：token 持续到达避免上游空闲超时；关闭思考链提速；总超时 300s
            String upstreamErr = QwenClient.streamChat(AppConfig.qwenModel(), TrpgService.buildGenMessages(cfg),
                    key, 300, true, full::append);
            if (upstreamErr != null) {
                TrpgDao.failTrpgGenTask(taskId, upstreamErr);
                return;
            }
            ObjectNode sc = TrpgService.normalize(TrpgService.parseScenario(full.toString()));
            String title = sc.path("title").asText("").trim();
            String cfgTitle = String.valueOf(cfg.getOrDefault("title", "")).trim();
            if (title.isEmpty()) title = cfgTitle.isEmpty() ? "未命名剧本" : cfgTitle;
            long id = TrpgDao.createTrpgScenario(userId, title, String.valueOf(cfg.getOrDefault("style", "")),
                    sc.path("intro").asText(""), JsonUtil.write(cfg), JsonUtil.write(sc));
            TrpgDao.finishTrpgGenTask(taskId, id);
        } catch (QwenClient.ModelHttpException e) {
            TrpgDao.failTrpgGenTask(taskId, e.messageFull());
        } catch (java.net.http.HttpTimeoutException e) {
            TrpgDao.failTrpgGenTask(taskId, "模型生成超时，请重试");
        } catch (IllegalArgumentException e) {
            TrpgDao.failTrpgGenTask(taskId, "模型输出解析失败：" + e.getMessage());
        } catch (Exception e) {
            TrpgDao.failTrpgGenTask(taskId, "生成失败：" + e);
        }
    }

    /** 轮询生成任务：running / done（附剧本） / error（附原因） */
    @GetMapping("/scenarios/tasks/{id}")
    public ResponseEntity<?> genTask(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> task = TrpgDao.getTrpgGenTask(id);
        if (task == null || ((Number) task.get("user_id")).longValue() != AuthUtil.userId(user)) {
            return ResponseEntity.status(404).body(Map.of("error", "任务不存在"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", id);
        String state = String.valueOf(task.get("state"));
        out.put("state", state);
        if ("done".equals(state) && task.get("scenario_id") != null) {
            long sid = ((Number) task.get("scenario_id")).longValue();
            Map<String, Object> row = TrpgDao.getTrpgScenario(sid);
            if (row != null) {
                try {
                    ObjectNode sc = (ObjectNode) JsonUtil.parse(String.valueOf(row.get("scenario_json")));
                    out.put("id", sid);
                    out.put("scenario", TrpgService.toMap(sc));
                } catch (Exception e) {
                    out.put("state", "error");
                    out.put("error", "剧本数据损坏");
                }
            }
        } else if ("error".equals(state)) {
            out.put("error", String.valueOf(task.getOrDefault("error", "生成失败")));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/scenarios/{id}")
    public ResponseEntity<?> detail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> row = TrpgDao.getTrpgScenario(id);
        if (row == null || !owned(row, user)) return ResponseEntity.status(404).body(Map.of("error", "剧本不存在"));
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

    @DeleteMapping("/scenarios/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> row = TrpgDao.getTrpgScenario(id);
        if (row == null || !owned(row, user)) return ResponseEntity.status(404).body(Map.of("error", "剧本不存在"));
        TrpgDao.deleteTrpgScenario(id, AuthUtil.userId(user));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 游玩 =================

    @PostMapping("/scenarios/{id}/play")
    public ResponseEntity<?> startPlay(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> row = TrpgDao.getTrpgScenario(id);
        if (row == null || !owned(row, user)) return ResponseEntity.status(404).body(Map.of("error", "剧本不存在"));
        try {
            ObjectNode sc = (ObjectNode) JsonUtil.parse(String.valueOf(row.get("scenario_json")));
            long pid = TrpgDao.createTrpgPlay(id, AuthUtil.userId(user), TrpgService.startNodeId(sc));
            return ResponseEntity.ok(playPayload(TrpgDao.getTrpgPlay(pid), sc));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "开局失败"));
        }
    }

    @GetMapping("/plays")
    public ResponseEntity<?> listPlays(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        return ResponseEntity.ok(Map.of("plays", TrpgDao.listTrpgPlays(AuthUtil.userId(user))));
    }

    @GetMapping("/plays/{id}")
    public ResponseEntity<?> playDetail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> play = TrpgDao.getTrpgPlay(id);
        if (play == null || !ownedPlay(play, user)) return ResponseEntity.status(404).body(Map.of("error", "对局不存在"));
        return scenarioOfPlay(play)
                .map(sc -> ResponseEntity.ok((Object) playPayload(play, sc)))
                .orElseGet(() -> ResponseEntity.status(500).body(Map.of("error", "剧本数据损坏")));
    }

    @PostMapping("/plays/{id}/choose")
    public ResponseEntity<?> choose(@PathVariable long id, @RequestBody(required = false) ChooseReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> play = TrpgDao.getTrpgPlay(id);
        if (play == null || !ownedPlay(play, user)) return ResponseEntity.status(404).body(Map.of("error", "对局不存在"));
        if ("ended".equals(play.get("state"))) return ResponseEntity.status(400).body(Map.of("error", "该对局已结束"));
        if (req == null || isBlank(req.choice_id)) return ResponseEntity.status(422).body(Map.of("error", "choice_id 必填"));
        java.util.Optional<ObjectNode> optSc = scenarioOfPlay(play);
        if (optSc.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "剧本数据损坏"));
        ObjectNode sc = optSc.get();
        ObjectNode cur = TrpgService.findNode(sc, String.valueOf(play.get("current_node")));
        if (cur == null) return ResponseEntity.status(500).body(Map.of("error", "当前场景损坏"));

        ObjectNode choice = null;
        for (JsonNode cj : cur.path("choices")) {
            if (req.choice_id.equals(cj.path("id").asText(""))) { choice = (ObjectNode) cj; break; }
        }
        if (choice == null) return ResponseEntity.status(400).body(Map.of("error", "选项不存在"));

        // 掷骰判定（服务端权威掷骰）
        Map<String, Object> diceResult = null;
        String nextId;
        if (choice.has("dice")) {
            int target = choice.path("dice").path("target").asInt(50);
            int roll = TrpgService.rollD100();
            boolean success = roll <= target;
            diceResult = new LinkedHashMap<>();
            diceResult.put("roll", roll);
            diceResult.put("target", target);
            diceResult.put("success", success);
            nextId = success ? choice.path("next").asText() : choice.path("fail_next").asText(choice.path("next").asText());
        } else {
            nextId = choice.path("next").asText();
        }
        ObjectNode next = TrpgService.findNode(sc, nextId);
        if (next == null) next = TrpgService.findNode(sc, TrpgService.startNodeId(sc));

        // 追加冒险日志
        List<Map<String, Object>> history = new ArrayList<>();
        try {
            JsonNode h = JsonUtil.parse(String.valueOf(play.get("history_json")));
            if (h.isArray()) for (JsonNode e : h) history.add(JsonUtil.MAPPER.convertValue(e, LinkedHashMap.class));
        } catch (Exception ignored) {}
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("node_title", cur.path("title").asText(""));
        entry.put("choice", choice.path("text").asText(""));
        if (diceResult != null) entry.put("dice", diceResult);
        history.add(entry);

        boolean ended = next.path("ending").asBoolean(false);
        int steps = ((Number) play.get("steps")).intValue() + 1;
        TrpgDao.updateTrpgPlay(id, next.path("id").asText(), ended ? "ended" : "playing",
                ended ? next.path("title").asText("") : "", steps, JsonUtil.write(history));
        return ResponseEntity.ok(playPayload(TrpgDao.getTrpgPlay(id), sc));
    }

    @DeleteMapping("/plays/{id}")
    public ResponseEntity<?> deletePlay(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> play = TrpgDao.getTrpgPlay(id);
        if (play == null || !ownedPlay(play, user)) return ResponseEntity.status(404).body(Map.of("error", "对局不存在"));
        TrpgDao.deleteTrpgPlay(id, AuthUtil.userId(user));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 工具 =================

    private static java.util.Optional<ObjectNode> scenarioOfPlay(Map<String, Object> play) {
        try {
            Map<String, Object> row = TrpgDao.getTrpgScenario(((Number) play.get("scenario_id")).longValue());
            return java.util.Optional.of((ObjectNode) JsonUtil.parse(String.valueOf(row.get("scenario_json"))));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /** 对局响应：当前节点全文 + 历史 + 状态 */
    private static Map<String, Object> playPayload(Map<String, Object> play, ObjectNode sc) {
        ObjectNode node = TrpgService.findNode(sc, String.valueOf(play.get("current_node")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("play_id", play.get("id"));
        out.put("scenario_id", play.get("scenario_id"));
        out.put("scenario_title", play.get("scenario_title"));
        out.put("state", play.get("state"));
        out.put("steps", play.get("steps"));
        out.put("ending_title", play.get("ending_title"));
        out.put("node", node == null ? null : TrpgService.toMap(node));
        try {
            out.put("history", JsonUtil.parse(String.valueOf(play.get("history_json"))));
        } catch (Exception e) {
            out.put("history", List.of());
        }
        return out;
    }

    private static boolean owned(Map<String, Object> row, Map<String, Object> user) {
        Object uid = row.get("user_id");
        return uid != null && ((Number) uid).longValue() == AuthUtil.userId(user);
    }

    private static boolean ownedPlay(Map<String, Object> play, Map<String, Object> user) {
        Object uid = play.get("user_id");
        return uid != null && ((Number) uid).longValue() == AuthUtil.userId(user);
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}