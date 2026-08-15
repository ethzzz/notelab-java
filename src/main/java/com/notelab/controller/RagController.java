package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.infra.QwenClient;
import com.notelab.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 文档问答 RAG（upload 为 JSON {name,content}，与 main.py 实际实现一致） */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    public static class RagUploadReq {
        public String name;
        public String content;
    }

    public static class RagAskReq {
        public String question;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestBody RagUploadReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String name = RagService.cleanName(req.name == null ? "" : req.name);
        String content = req.content == null ? "" : req.content.trim();
        if (content.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "内容不能为空"));
        }
        List<String> chunks = RagService.chunkText(content);
        RagService.put(name, content);
        try {
            Files.writeString(RagService.uploadDir().resolve(name + ".txt"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存文档失败：" + e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("chunks", chunks.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/docs")
    public ResponseEntity<Map<String, Object>> docs(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        return ResponseEntity.ok(Map.of("docs", RagService.docSummaries()));
    }

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody RagAskReq req, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) return ResponseEntity.status(500).body(Map.of("error", "未配置 QWEN_API_KEY"));
        String question = req.question == null ? "" : req.question.trim();
        if (question.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "问题不能为空"));
        }
        if (RagService.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "还没有上传文档，请先上传一份文档"));
        }
        List<Map<String, Object>> hits = RagService.retrieve(question, 3);
        StringBuilder refs = new StringBuilder();
        if (hits.isEmpty()) {
            refs.append("（未检索到与问题相关的内容）");
        } else {
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) refs.append("\n\n");
                refs.append("[").append(i + 1).append("] (来源:")
                        .append(hits.get(i).get("doc")).append(") ")
                        .append(hits.get(i).get("text"));
            }
        }
        String system = "你是文档问答助手。必须严格依据用户给出的「参考资料」作答，不要用资料之外的知识，更不要编造。"
                + "如果参考资料不足以回答问题，请直接说：参考资料中没有提到。"
                + "作答时，在相关句子末尾用方括号标注引用编号（如 [1]）。";
        String userPrompt = "参考资料：\n" + refs + "\n\n问题：" + question;
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", userPrompt));
        try {
            String answer = QwenClient.complete(AppConfig.qwenModel(), messages, key, 90);
            List<Map<String, Object>> citations = new ArrayList<>();
            for (Map<String, Object> h : hits) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("doc", h.get("doc"));
                c.put("text", h.get("text"));
                c.put("score", h.get("score"));
                citations.add(c);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", question);
            body.put("answer", answer);
            body.put("citations", citations);
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
