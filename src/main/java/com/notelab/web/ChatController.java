package com.notelab.web;

import com.notelab.AppConfig;
import com.notelab.Db;
import com.notelab.JsonUtil;
import com.notelab.QwenClient;
import com.notelab.SseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** POST /api/chat：SSE 流式对话（历史入库，行为与 Python 版一致） */
@RestController
@RequestMapping("/api")
public class ChatController {

    public static class ChatReq {
        public Long conversation_id;
        public String message;
    }

    @PostMapping("/chat")
    public void chat(@RequestBody ChatReq req, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) { jsonError(response, 401, "请先登录"); return; }
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) { jsonError(response, 500, "未配置 QWEN_API_KEY"); return; }
        if (req.conversation_id == null) { jsonError(response, 422, "conversation_id 必填"); return; }
        Map<String, Object> conv = Db.getConversation(req.conversation_id, AuthUtil.userId(user));
        if (conv == null) { jsonError(response, 404, "会话不存在"); return; }
        String message = req.message == null ? "" : req.message.trim();
        if (message.isEmpty()) { jsonError(response, 400, "消息不能为空"); return; }

        long cid = ((Number) conv.get("id")).longValue();
        Db.addMessage(cid, "user", message);
        if (Db.countMessages(cid) == 1) {
            Db.setConversationTitle(cid, message.substring(0, Math.min(30, message.length())));
        }
        Db.touchConversation(cid);

        List<Map<String, String>> history = new ArrayList<>();
        for (Map<String, Object> m : Db.listMessages(cid)) {
            history.add(Map.of("role", String.valueOf(m.get("role")), "content", String.valueOf(m.get("content"))));
        }
        String model = String.valueOf(conv.get("model"));

        SseUtil.prepare(response);
        OutputStream out = response.getOutputStream();
        StringBuilder full = new StringBuilder();
        try {
            String upstreamErr = QwenClient.streamChat(model, history, key, 120, delta -> {
                full.append(delta);
                try {
                    SseUtil.send(out, Map.of("delta", delta));
                } catch (IOException e) {
                    throw new RuntimeException(e); // 客户端断开等
                }
            });
            if (upstreamErr != null) {
                sendSafe(out, Map.of("error", upstreamErr));
                sendSafe(out, Map.of("done", true));
                return;
            }
        } catch (HttpTimeoutException e) {
            sendSafe(out, Map.of("error", "模型响应超时"));
        } catch (Exception e) {
            sendSafe(out, Map.of("error", "请求模型出错：" + unwrap(e)));
        }
        if (full.length() > 0) {
            Db.addMessage(cid, "assistant", full.toString());
            Db.touchConversation(cid);
        }
        sendSafe(out, Map.of("done", true));
    }

    private static void sendSafe(OutputStream out, Map<String, Object> obj) {
        try {
            SseUtil.send(out, obj);
        } catch (IOException ignored) {
        }
    }

    private static String unwrap(Exception e) {
        return e.getCause() != null ? e.getCause().toString() : e.toString();
    }

    static void jsonError(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(JsonUtil.write(Map.of("error", msg)));
        resp.flushBuffer();
    }
}
