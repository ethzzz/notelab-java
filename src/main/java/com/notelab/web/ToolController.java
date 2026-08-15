package com.notelab.web;

import com.notelab.Db;
import com.notelab.JsonUtil;
import com.notelab.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 工具库：/api/tools。
 * 工具 = 可被各 AI 功能引用的增强能力登记（搜索/生成/语音/MCP/自定义 API 等）。
 * 全部接口仅超级管理员可用（403 守卫）。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    public static class ToolReq {
        public String name;
        public String icon;
        public String category;
        public String type;
        public String description;
        public String endpoint;
        public String config;
        public Boolean enabled;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));
    }

    private static boolean isToolType(String t) {
        return "api".equals(t) || "mcp".equals(t) || "function".equals(t) || "plugin".equals(t);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(user)) return forbidden();
        return ResponseEntity.ok(Map.of("tools", Db.listTools()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) ToolReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(user)) return forbidden();
        if (req == null || req.name == null || req.name.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "工具名称不能为空"));
        }
        String name = req.name.trim();
        if (name.length() > 100) {
            return ResponseEntity.status(400).body(Map.of("error", "工具名称过长（最多 100 字）"));
        }
        String config = req.config == null ? "" : req.config.trim();
        if (!config.isEmpty()) {
            try {
                JsonUtil.parse(config);
            } catch (Exception e) {
                return ResponseEntity.status(400).body(Map.of("error", "config 必须是合法 JSON"));
            }
        }
        long id = Db.createTool(
                name,
                req.icon == null || req.icon.trim().isEmpty() ? "🔧" : req.icon.trim(),
                req.category == null || req.category.trim().isEmpty() ? "自定义" : req.category.trim(),
                isToolType(req.type) ? req.type : "api",
                req.description == null ? "" : req.description.trim(),
                req.endpoint == null ? "" : req.endpoint.trim(),
                config,
                req.enabled == null || req.enabled);
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable long id,
                                                      @RequestBody(required = false) ToolReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(user)) return forbidden();
        Map<String, Object> existing = Db.getTool(id);
        if (existing == null) {
            return ResponseEntity.status(404).body(Map.of("error", "工具不存在"));
        }
        if (req == null || req.name == null || req.name.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "工具名称不能为空"));
        }
        String name = req.name.trim();
        if (name.length() > 100) {
            return ResponseEntity.status(400).body(Map.of("error", "工具名称过长（最多 100 字）"));
        }
        String config = req.config == null ? "" : req.config.trim();
        if (!config.isEmpty()) {
            try {
                JsonUtil.parse(config);
            } catch (Exception e) {
                return ResponseEntity.status(400).body(Map.of("error", "config 必须是合法 JSON"));
            }
        }
        Db.updateTool(id,
                name,
                req.icon == null || req.icon.trim().isEmpty() ? "🔧" : req.icon.trim(),
                req.category == null || req.category.trim().isEmpty() ? "自定义" : req.category.trim(),
                isToolType(req.type) ? req.type : "api",
                req.description == null ? "" : req.description.trim(),
                req.endpoint == null ? "" : req.endpoint.trim(),
                config,
                req.enabled == null ? toBool(existing.get("enabled")) : req.enabled);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(user)) return forbidden();
        Map<String, Object> existing = Db.getTool(id);
        if (existing == null) {
            return ResponseEntity.status(404).body(Map.of("error", "工具不存在"));
        }
        boolean now = !toBool(existing.get("enabled"));
        Db.setToolEnabled(id, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("enabled", now);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(user)) return forbidden();
        Map<String, Object> existing = Db.getTool(id);
        if (existing == null) {
            return ResponseEntity.status(404).body(Map.of("error", "工具不存在"));
        }
        Db.deleteTool(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return v != null && !"0".equals(v.toString());
    }
}
