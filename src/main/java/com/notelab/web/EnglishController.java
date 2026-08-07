package com.notelab.web;

import com.notelab.AppConfig;
import com.notelab.Db;
import com.notelab.JsonUtil;
import com.notelab.QwenClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 英语学习：scenarios / conversations / messages / delete / chat。
 * 注意：/api/english/chat 在 Python 版是普通 JSON 响应（非流式），此处保持一致。
 * 提示词照抄 Python 版。
 */
@RestController
@RequestMapping("/api/english")
public class EnglishController {

    public static final List<Map<String, String>> SCENARIOS = List.of(
            sc("daily", "日常问候", "Daily Greetings", "casual daily greeting and small talk"),
            sc("restaurant", "餐厅点餐", "Ordering Food", "ordering food at a restaurant; you play the waiter"),
            sc("travel", "机场旅行", "Travel & Airport", "at the airport or while traveling; you play an airport staff member"),
            sc("interview", "求职面试", "Job Interview", "a job interview; you play the interviewer"),
            sc("shopping", "购物", "Shopping", "shopping at a store; you play the shop assistant"),
            sc("doctor", "看医生", "Seeing a Doctor", "at a clinic; you play the doctor"),
            sc("hotel", "酒店预订", "Hotel Booking", "checking into a hotel; you play the front desk clerk"),
            sc("free", "自由对话", "Free Talk", "free talk on any topic"));

    /** 场景条目：键序与 Python 版 ENGLISH_SCENARIOS 完全一致（id/name/en/desc） */
    private static Map<String, String> sc(String id, String name, String en, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("en", en);
        m.put("desc", desc);
        return m;
    }

    private static final Map<String, Map<String, String>> SCENARIO_MAP = new LinkedHashMap<>();

    static {
        for (Map<String, String> s : SCENARIOS) SCENARIO_MAP.put(s.get("id"), s);
    }

    public static class EnConvCreateReq {
        public String scenario = "free";
    }

    public static class EnChatReq {
        public Long conversation_id;
        public String message;
    }

    private static String scenarioName(String sid) {
        Map<String, String> s = SCENARIO_MAP.get(sid);
        return s != null ? s.get("name") : "自由对话";
    }

    @GetMapping("/scenarios")
    public ResponseEntity<Map<String, Object>> scenarios(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        return ResponseEntity.ok(Map.of("scenarios", SCENARIOS));
    }

    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : Db.enListConversations(AuthUtil.userId(user))) {
            out.add(enConvRow(c));
        }
        return ResponseEntity.ok(Map.of("conversations", out));
    }

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) EnConvCreateReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (AppConfig.qwenKey().isEmpty()) {
            return ResponseEntity.status(500).body(Map.of("error", "未配置 QWEN_API_KEY"));
        }
        String scenario = (req != null && req.scenario != null && SCENARIO_MAP.containsKey(req.scenario))
                ? req.scenario : "free";
        Map<String, String> sc = SCENARIO_MAP.get(scenario);
        long cid = Db.enCreateConversation(AuthUtil.userId(user), sc.get("name"), scenario);
        String opening;
        try {
            List<Map<String, String>> openingMsgs = List.of(
                    Map.of("role", "system", "content", "You are an English conversation tutor starting a role-play with a learner."),
                    Map.of("role", "user", "content", "Scenario: " + sc.get("en") + " (" + sc.get("desc")
                            + "). Say ONE short opening line in English to start the conversation. Output only that one line."));
            opening = enGenerate(openingMsgs).trim();
            // Python: .strip().strip('"')
            while (opening.startsWith("\"") || opening.endsWith("\"")) {
                if (opening.startsWith("\"")) opening = opening.substring(1);
                if (opening.endsWith("\"")) opening = opening.substring(0, opening.length() - 1);
            }
            opening = opening.trim();
        } catch (Exception e) {
            opening = "Hello! Let's practice: " + sc.get("en") + ". You can start.";
        }
        Db.enAddMessage(cid, "assistant", opening, null, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", cid);
        body.put("title", sc.get("name"));
        body.put("scenario", scenario);
        body.put("opening", opening);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/conversations/{cid}/messages")
    public ResponseEntity<Map<String, Object>> messages(@PathVariable long cid, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> conv = Db.enGetConversation(cid, AuthUtil.userId(user));
        if (conv == null) return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Map<String, Object> m : Db.enListMessages(cid)) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.get("role"));
            mm.put("content", m.get("content"));
            mm.put("correction", m.get("correction"));
            mm.put("error_note", m.get("error_note"));
            msgs.add(mm);
        }
        Map<String, Object> convOut = new LinkedHashMap<>();
        convOut.put("id", conv.get("id"));
        convOut.put("title", conv.get("title"));
        convOut.put("scenario", conv.get("scenario"));
        convOut.put("scenario_name", scenarioName(String.valueOf(conv.get("scenario"))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation", convOut);
        body.put("messages", msgs);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/conversations/{cid}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long cid, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Db.enDeleteConversation(cid, AuthUtil.userId(user));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody EnChatReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (AppConfig.qwenKey().isEmpty()) {
            return ResponseEntity.status(500).body(Map.of("error", "未配置 QWEN_API_KEY"));
        }
        if (req.conversation_id == null) {
            return ResponseEntity.status(422).body(Map.of("error", "conversation_id 必填"));
        }
        Map<String, Object> conv = Db.enGetConversation(req.conversation_id, AuthUtil.userId(user));
        if (conv == null) return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        String message = req.message == null ? "" : req.message.trim();
        if (message.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "消息不能为空"));
        Map<String, String> sc = SCENARIO_MAP.getOrDefault(String.valueOf(conv.get("scenario")), SCENARIO_MAP.get("free"));

        List<Map<String, Object>> history = Db.enListMessages(((Number) conv.get("id")).longValue());
        List<Map<String, Object>> last10 = history.subList(Math.max(0, history.size() - 10), history.size());
        StringBuilder histText = new StringBuilder();
        for (int i = 0; i < last10.size(); i++) {
            if (i > 0) histText.append("\n");
            histText.append(last10.get(i).get("role")).append(": ").append(last10.get(i).get("content"));
        }

        String sysPrompt = "You are a friendly English conversation tutor. The learner is practicing English in the scenario: "
                + sc.get("en") + " (" + sc.get("desc") + "). You play your role in the scenario and chat in English. "
                + "After each learner message you must check the grammar and vocabulary of the learner's sentence, "
                + "give a corrected version, briefly note any errors, and reply conversationally in English.";
        String userPrompt = "Conversation so far:\n" + histText + "\n\n"
                + "The learner now says: \"" + message + "\"\n\n"
                + "Respond ONLY with a JSON object (no extra text) with these keys:\n"
                + "{\"corrected\": \"<corrected version of the learner's sentence; same as the original if already correct>\", "
                + "\"error_note\": \"<a brief explanation of the errors in Chinese; say 表达正确，无需修改 if there are none>\", "
                + "\"reply\": \"<your 1-3 sentence conversational reply in English, staying in the scenario>\"}";

        String raw;
        try {
            raw = enGenerate(List.of(
                    Map.of("role", "system", "content", sysPrompt),
                    Map.of("role", "user", "content", userPrompt)));
        } catch (QwenClient.ModelHttpException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.messageShort()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
        }

        String corrected = message;
        String errorNote = "";
        String reply = raw;
        try {
            String s = raw.trim();
            int i = s.indexOf('{');
            int j = s.lastIndexOf('}');
            if (i != -1 && j != -1 && j > i) {
                JsonNode obj = JsonUtil.parse(s.substring(i, j + 1));
                String c = obj.path("corrected").asText(message).trim();
                corrected = c.isEmpty() ? message : c;
                errorNote = obj.path("error_note").asText("").trim();
                String r = obj.path("reply").asText("").trim();
                reply = r.isEmpty() ? raw : r;
            }
        } catch (Exception ignored) {
        }

        long cid = ((Number) conv.get("id")).longValue();
        Db.enAddMessage(cid, "user", message, corrected, errorNote);
        Db.enAddMessage(cid, "assistant", reply, null, null);
        Db.enTouch(cid);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corrected", corrected);
        body.put("error_note", errorNote);
        body.put("reply", reply);
        return ResponseEntity.ok(body);
    }

    /** _en_generate：非流式调用（90s 超时），错误语义与 Python 一致 */
    private static String enGenerate(List<Map<String, String>> messages) throws Exception {
        return QwenClient.complete(AppConfig.qwenModel(), messages, AppConfig.qwenKey(), 90);
    }

    /** _en_conv_row */
    private static Map<String, Object> enConvRow(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("title", c.get("title"));
        m.put("scenario", c.get("scenario"));
        m.put("scenario_name", scenarioName(String.valueOf(c.get("scenario"))));
        m.put("updated_at", String.valueOf(c.get("updated_at")));
        return m;
    }
}
