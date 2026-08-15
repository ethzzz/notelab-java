package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.infra.QwenClient;
import com.notelab.common.SseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** POST /api/arena：并行多模型对比（SSE），10s 无结果发心跳，行为与 Python 版一致。 */
@RestController
@RequestMapping("/api")
public class ArenaController {

    public static class ArenaReq {
        public String message;
        public List<Object> models;
    }

    @PostMapping("/arena")
    public void arena(@RequestBody ArenaReq req, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) { ChatController.jsonError(response, 401, "请先登录"); return; }
        String key = AppConfig.qwenKey();
        if (key.isEmpty()) { ChatController.jsonError(response, 500, "未配置 QWEN_API_KEY"); return; }
        String message = req.message == null ? "" : req.message.trim();
        if (message.isEmpty()) { ChatController.jsonError(response, 400, "消息不能为空"); return; }
        List<String> models = new ArrayList<>();
        if (req.models != null) {
            for (Object m : req.models) {
                if (m instanceof String s && !s.trim().isEmpty()) models.add(s);
            }
        }
        if (models.isEmpty()) { ChatController.jsonError(response, 400, "请至少选择一个模型"); return; }
        if (models.size() > 6) models = models.subList(0, 6);

        SseUtil.prepare(response);
        OutputStream out = response.getOutputStream();
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("started", true);
        started.put("models", models);
        SseUtil.send(out, started);

        LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        ExecutorService pool = Executors.newCachedThreadPool();
        for (String model : models) {
            final String m = model;
            pool.submit(() -> {
                try {
                    queue.put(callOne(m, key, message));
                } catch (InterruptedException ignored) {
                }
            });
        }
        try {
            int remaining = models.size();
            while (remaining > 0) {
                Map<String, Object> result = queue.poll(10, TimeUnit.SECONDS);
                if (result == null) {
                    // 心跳保活：防止代理掐断空闲连接（对应 Python 的 10s keepalive）
                    SseUtil.send(out, Map.of("keepalive", true));
                } else {
                    remaining--;
                    SseUtil.send(out, result);
                }
            }
            SseUtil.send(out, Map.of("done", true));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> callOne(String model, String key, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", model);
        try {
            String content = QwenClient.complete(model,
                    List.of(Map.of("role", "user", "content", message)), key, 120);
            r.put("content", content);
        } catch (QwenClient.ModelHttpException e) {
            r.put("error", e.messageShort());
        } catch (HttpTimeoutException e) {
            r.put("error", String.valueOf(e.getMessage()));
        } catch (Exception e) {
            r.put("error", String.valueOf(e));
        }
        return r;
    }
}
