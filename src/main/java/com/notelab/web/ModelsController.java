package com.notelab.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.AppConfig;
import com.notelab.QwenClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** GET /api/models：模型下拉列表（600s 缓存，逻辑与 Python 版一致） */
@RestController
@RequestMapping("/api")
public class ModelsController {

    private static final Pattern FILTER = Pattern.compile("image|audio|tts|wan2|vl", Pattern.CASE_INSENSITIVE);

    private static long cacheAt = 0;
    private static List<String> cacheModels = new ArrayList<>();

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        String key = AppConfig.qwenKey();
        long now = System.currentTimeMillis() / 1000;
        synchronized (ModelsController.class) {
            if (now - cacheAt < 600 && !cacheModels.isEmpty()) {
                return ResponseEntity.ok(Map.of("models", cacheModels));
            }
        }
        if (key.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("models", List.of(AppConfig.qwenModel()));
            body.put("note", "未配置密钥，仅默认模型");
            return ResponseEntity.ok(body);
        }
        try {
            JsonNode data = QwenClient.fetchModels(key);
            List<String> ids = new ArrayList<>();
            JsonNode arr = data.get("data");
            if (arr != null && arr.isArray()) {
                for (JsonNode m : arr) {
                    JsonNode id = m.get("id");
                    if (id != null && !id.isNull() && !id.asText().isEmpty()) ids.add(id.asText());
                }
            }
            List<String> chat = new ArrayList<>();
            for (String id : ids) {
                if (!FILTER.matcher(id).find()) chat.add(id);
            }
            if (chat.isEmpty()) chat = ids;
            if (!chat.contains(AppConfig.qwenModel())) {
                chat.add(0, AppConfig.qwenModel());
            }
            synchronized (ModelsController.class) {
                cacheAt = now;
                cacheModels = chat;
            }
            return ResponseEntity.ok(Map.of("models", chat));
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("models", List.of(AppConfig.qwenModel()));
            body.put("note", "获取模型列表失败：" + e.getMessage());
            return ResponseEntity.ok(body);
        }
    }
}
