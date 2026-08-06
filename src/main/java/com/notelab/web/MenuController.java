package com.notelab.web;

import com.notelab.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/menu：前端外壳用菜单项 + 背景配置 */
@RestController
@RequestMapping("/api")
public class MenuController {

    @GetMapping("/menu")
    public ResponseEntity<Map<String, Object>> menu(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> cfg = UiConfigService.getConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("menu", UiConfigService.buildMenuItems(cfg));
        Object bg = cfg.get("background");
        body.put("background", bg == null ? Map.of() : bg);
        return ResponseEntity.ok(body);
    }
}
