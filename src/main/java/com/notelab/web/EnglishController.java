package com.notelab.web;

import com.notelab.AppConfig;
import com.notelab.Db;
import com.notelab.JsonUtil;
import com.notelab.QwenClient;
import com.notelab.SseUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
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
    public void chat(@RequestBody EnChatReq req, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) { ChatController.jsonError(response, 401, "请先登录"); return; }
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) { ChatController.jsonError(response, 500, "未配置 QWEN_API_KEY"); return; }
        if (req.conversation_id == null) { ChatController.jsonError(response, 422, "conversation_id 必填"); return; }
        Map<String, Object> conv = Db.enGetConversation(req.conversation_id, AuthUtil.userId(user));
        if (conv == null) { ChatController.jsonError(response, 404, "会话不存在"); return; }
        String message = req.message == null ? "" : req.message.trim();
        if (message.isEmpty()) { ChatController.jsonError(response, 400, "消息不能为空"); return; }
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
        // 键序 reply 在最前：流式输出时回复可以最先开始打字机展示，修正信息随后补上
        String userPrompt = "Conversation so far:\n" + histText + "\n\n"
                + "The learner now says: \"" + message + "\"\n\n"
                + "Respond ONLY with a JSON object (no extra text) with keys in exactly this order:\n"
                + "{\"reply\": \"<your 1-3 sentence conversational reply in English, staying in the scenario>\", "
                + "\"corrected\": \"<corrected version of the learner's sentence; same as the original if already correct>\", "
                + "\"error_note\": \"<a brief explanation of the errors in Chinese; say 表达正确，无需修改 if there are none>\"}";

        SseUtil.prepare(response);
        OutputStream out = response.getOutputStream();
        EnStreamParser parser = new EnStreamParser(out, message);
        boolean ok = false;
        try {
            String upstreamErr = QwenClient.streamChat(AppConfig.qwenModel(),
                    List.of(Map.of("role", "system", "content", sysPrompt),
                            Map.of("role", "user", "content", userPrompt)),
                    key, 90, parser::onDelta);
            if (upstreamErr != null) {
                parser.sendError(upstreamErr);
            } else {
                parser.finish();
                ok = true;
            }
        } catch (Exception e) {
            parser.sendError("请求模型出错：" + (e.getCause() != null ? e.getCause().toString() : e.toString()));
        }
        // 与旧契约一致：模型成功才把本轮消息入库
        if (ok) {
            long cid = ((Number) conv.get("id")).longValue();
            Db.enAddMessage(cid, "user", message, parser.corrected, parser.errorNote);
            if (parser.reply.length() > 0) {
                Db.enAddMessage(cid, "assistant", parser.reply.toString(), null, null);
            }
            Db.enTouch(cid);
        }
        try {
            SseUtil.send(out, Map.of("done", true));
        } catch (IOException ignored) {
        }
    }

    /**
     * 英语对话流式解析器：模型按 JSON 输出（键序 reply → corrected → error_note）。
     * 检测到 "reply" 字段开始即进入流式阶段，对 reply 值做增量 JSON 反转义并以 delta 事件持续推送（打字机效果）；
     * 流结束后解析 reply 之后的 corrected / error_note 并发出 correction 事件。
     * 若始终未定位 reply 字段，退化为整体解析一次性发出。
     */
    static final class EnStreamParser {
        private final OutputStream out;
        private final String originalMessage;
        private final StringBuilder raw = new StringBuilder();
        final StringBuilder reply = new StringBuilder();
        String corrected;
        String errorNote = "";
        private int pos = -1;
        private int suffixStart = -1;
        private boolean inReply = false;
        private boolean replyDone = false;
        private boolean correctionSent = false;
        private boolean escape = false;
        private boolean collectingUnicode = false;
        private final StringBuilder unicode = new StringBuilder();

        EnStreamParser(OutputStream out, String originalMessage) {
            this.out = out;
            this.originalMessage = originalMessage;
            this.corrected = originalMessage;
        }

        void onDelta(String delta) {
            raw.append(delta);
            try {
                if (!inReply) {
                    int start = findReplyValueStart();
                    if (start < 0) return;
                    inReply = true;
                    pos = start;
                }
                drain();
            } catch (IOException ignored) {
            }
        }

        /** 返回 reply 值开引号之后的字符索引；缓冲区尚不足以判定时返回 -1 */
        private int findReplyValueStart() {
            int k = 0;
            while (true) {
                k = raw.indexOf("\"reply\"", k);
                if (k < 0) return -1;
                int i = k + 7;
                while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
                if (i >= raw.length()) return -1;
                if (raw.charAt(i) != ':') { k = k + 7; continue; }
                i++;
                while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
                if (i >= raw.length()) return -1;
                if (raw.charAt(i) != '"') { k = k + 7; continue; }
                return i + 1;
            }
        }

        private void emitCorrection() throws IOException {
            if (correctionSent) return;
            correctionSent = true;
            Map<String, Object> corr = new LinkedHashMap<>();
            corr.put("corrected", corrected);
            corr.put("error_note", errorNote);
            SseUtil.send(out, Map.of("correction", corr));
        }

        /** 增量反转义 reply 字符串并推送；遇到闭引号停止并记录后缀起点 */
        private void drain() throws IOException {
            StringBuilder emit = new StringBuilder();
            while (pos < raw.length() && !replyDone) {
                char ch = raw.charAt(pos);
                if (collectingUnicode) {
                    unicode.append(ch);
                    pos++;
                    if (unicode.length() == 4) {
                        try {
                            emit.append((char) Integer.parseInt(unicode.toString(), 16));
                        } catch (Exception e) {
                            emit.append('?');
                        }
                        unicode.setLength(0);
                        collectingUnicode = false;
                    }
                    continue;
                }
                if (escape) {
                    escape = false;
                    pos++;
                    switch (ch) {
                        case 'n': emit.append('\n'); break;
                        case 't': emit.append('\t'); break;
                        case 'r': emit.append('\r'); break;
                        case 'b': emit.append('\b'); break;
                        case 'f': emit.append('\f'); break;
                        case '"': emit.append('"'); break;
                        case '\\': emit.append('\\'); break;
                        case '/': emit.append('/'); break;
                        case 'u': collectingUnicode = true; break;
                        default: emit.append(ch);
                    }
                    continue;
                }
                if (ch == '\\') {
                    escape = true;
                    pos++;
                    continue;
                }
                if (ch == '"') {
                    replyDone = true;
                    pos++;
                    suffixStart = pos;
                    break;
                }
                emit.append(ch);
                pos++;
            }
            if (emit.length() > 0) {
                reply.append(emit);
                SseUtil.send(out, Map.of("delta", emit.toString()));
            }
        }

        /** 流正常结束：解析 reply 之后的修正信息；若始终未定位 reply 字段，退化为整体解析 */
        void finish() {
            try {
                if (!inReply) {
                    fallbackFullParse();
                } else if (!correctionSent) {
                    parseSuffix();
                    emitCorrection();
                }
            } catch (IOException ignored) {
            }
        }

        private void parseSuffix() {
            if (suffixStart < 0) return;
            String s = raw.substring(suffixStart);
            int lb = s.indexOf(',');
            int rb = s.lastIndexOf('}');
            if (lb >= 0 && rb > lb) {
                try {
                    JsonNode obj = JsonUtil.parse("{" + s.substring(lb + 1, rb) + "}");
                    String c = obj.path("corrected").asText("").trim();
                    corrected = c.isEmpty() ? originalMessage : c;
                    errorNote = obj.path("error_note").asText("").trim();
                } catch (Exception ignored) {
                }
            }
        }

        private void fallbackFullParse() throws IOException {
            String s = raw.toString().trim();
            int i = s.indexOf('{');
            int j = s.lastIndexOf('}');
            String replyText = s;
            if (i != -1 && j > i) {
                try {
                    JsonNode obj = JsonUtil.parse(s.substring(i, j + 1));
                    String c = obj.path("corrected").asText("").trim();
                    corrected = c.isEmpty() ? originalMessage : c;
                    errorNote = obj.path("error_note").asText("").trim();
                    replyText = obj.path("reply").asText(s).trim();
                } catch (Exception ignored) {
                }
            }
            emitCorrection();
            if (!replyText.isEmpty()) {
                reply.append(replyText);
                SseUtil.send(out, Map.of("delta", replyText));
            }
        }

        void sendError(String msg) {
            try {
                SseUtil.send(out, Map.of("error", msg));
            } catch (IOException ignored) {
            }
        }
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
