package com.notelab.web;

import com.notelab.AppConfig;
import com.notelab.QwenClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** POST /api/toolbox：文本工具箱（摘要/翻译/改写/情感分析，提示词与 Python 版一致） */
@RestController
@RequestMapping("/api")
public class ToolboxController {

    public static class ToolboxReq {
        public String action;
        public String text;
    }

    private static final Map<String, Map<String, String>> ACTIONS = new LinkedHashMap<>();

    static {
        ACTIONS.put("summarize", Map.of("name", "摘要",
                "system", "你是文本摘要助手。请把用户给出的文本浓缩成 3-5 句话的摘要，抓住核心信息，只输出摘要本身，不要任何解释或前缀。"));
        ACTIONS.put("translate", Map.of("name", "翻译",
                "system", "你是翻译助手。请把用户给出的文本翻译成英文；如果原文已经是英文，则翻译成中文。只输出译文，不要任何解释或前缀。"));
        ACTIONS.put("rewrite", Map.of("name", "改写",
                "system", "你是润色改写助手。请把用户给出的文本改写得更加通顺、专业、简洁，保持原意不变，不要增删关键信息。只输出改写后的文本，不要任何解释或前缀。"));
        ACTIONS.put("sentiment", Map.of("name", "情感分析",
                "system", "你是情感分析助手。请判断用户给出的文本的情感倾向。第一行只输出「积极」「消极」或「中性」三者之一，第二行用一句简短的话说明理由。"));
    }

    @PostMapping("/toolbox")
    public ResponseEntity<Map<String, Object>> toolbox(@RequestBody ToolboxReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "未配置 QWEN_API_KEY"));
        Map<String, String> action = ACTIONS.get(req.action);
        if (action == null) {
            return ResponseEntity.status(400).body(Map.of("error", "未知动作：" + req.action));
        }
        String text = req.text == null ? "" : req.text.trim();
        if (text.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "文本不能为空"));
        }
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", action.get("system")),
                Map.of("role", "user", "content", text));
        try {
            String result = QwenClient.complete(AppConfig.qwenModel(), messages, key, 90);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", req.action);
            body.put("name", action.get("name"));
            body.put("result", result);
            return ResponseEntity.ok(body);
        } catch (HttpTimeoutException e) {
            return ResponseEntity.status(504).body(Map.of("error", "模型响应超时（90s）"));
        } catch (QwenClient.ModelHttpException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.messageFull()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "请求模型出错：" + e));
        }
    }
}
