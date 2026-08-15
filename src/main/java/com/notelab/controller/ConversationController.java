package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.dao.Db;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.notelab.dao.ConversationDao;

/** 智能对话会话管理（契约与 Python 版一致） */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    public static class ConvCreateReq {
        public String model = "";
    }

    public static class ConvModelReq {
        public String model;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : ConversationDao.listConversations(AuthUtil.userId(user))) {
            out.add(convRow(c));
        }
        return ResponseEntity.ok(Map.of("conversations", out));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) ConvCreateReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String model = (req == null || req.model == null) ? "" : req.model.trim();
        if (model.isEmpty()) model = AppConfig.qwenModel();
        long cid = ConversationDao.createConversation(AuthUtil.userId(user), "新对话", model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", cid);
        body.put("title", "新对话");
        body.put("model", model);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{cid}/messages")
    public ResponseEntity<Map<String, Object>> messages(@PathVariable long cid, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> conv = ConversationDao.getConversation(cid, AuthUtil.userId(user));
        if (conv == null) return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Map<String, Object> m : ConversationDao.listMessages(cid)) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.get("role"));
            mm.put("content", m.get("content"));
            msgs.add(mm);
        }
        Map<String, Object> convOut = new LinkedHashMap<>();
        convOut.put("id", conv.get("id"));
        convOut.put("title", conv.get("title"));
        convOut.put("model", conv.get("model"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation", convOut);
        body.put("messages", msgs);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{cid}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long cid, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        ConversationDao.deleteConversation(cid, AuthUtil.userId(user));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{cid}/model")
    public ResponseEntity<Map<String, Object>> setModel(@PathVariable long cid,
                                                        @RequestBody ConvModelReq req,
                                                        HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> conv = ConversationDao.getConversation(cid, AuthUtil.userId(user));
        if (conv == null) return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        String model = (req.model == null ? "" : req.model.trim());
        ConversationDao.setConversationModel(cid, model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("model", model);
        return ResponseEntity.ok(body);
    }

    /** _conv_row */
    private static Map<String, Object> convRow(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("title", c.get("title"));
        m.put("model", c.get("model"));
        m.put("updated_at", String.valueOf(c.get("updated_at")));
        return m;
    }
}
