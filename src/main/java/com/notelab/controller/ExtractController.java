package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import com.notelab.infra.QwenClient;
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

/** POST /api/extract：结构化抽取（提示词与 JSON 解析逻辑与 Python 版一致） */
@RestController
@RequestMapping("/api")
public class ExtractController {

    public static class ExtractReq {
        public String text;
        public String fields;
    }

    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(@RequestBody ExtractReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "未配置 QWEN_API_KEY"));
        String text = req.text == null ? "" : req.text.trim();
        String fields = req.fields == null ? "" : req.fields.trim();
        if (text.isEmpty() || fields.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "文本和目标字段都不能为空"));
        }
        String system = "你是结构化信息抽取助手。用户给你一段文本和一组目标字段，"
                + "你需要从文本中抽取这些字段的值，只输出一个 JSON 对象：键为字段名，值为抽取到的值（抽取不到则为 null）。"
                + "不要输出 JSON 以外的任何内容。";
        String userPrompt = "文本：\n" + text + "\n\n目标字段：" + fields + "\n\n请输出 JSON：";
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", userPrompt));
        try {
            String raw = QwenClient.complete(AppConfig.qwenModel(), messages, key, 90);
            String s = raw.trim();
            int i = s.indexOf('{');
            int j = s.lastIndexOf('}');
            JsonNode result = null;
            if (i != -1 && j != -1 && j > i) {
                try {
                    result = JsonUtil.parse(s.substring(i, j + 1));
                } catch (Exception e) {
                    result = null;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("raw", raw);
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
