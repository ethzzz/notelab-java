package com.notelab.controller;

import com.notelab.dao.Db;
import com.notelab.service.PermService;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GET /api/menu：前端外壳用菜单项（按当前账户角色的路由组过滤）+ 背景配置 */
@RestController
@RequestMapping("/api")
public class MenuController {

    @GetMapping("/menu")
    public ResponseEntity<Map<String, Object>> menu(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> cfg = UiConfigService.getConfig();
        // 多级菜单树 + RBAC（叶子级过滤、空分组剪枝）在 buildMenuItems 内完成
        List<Map<String, Object>> items = UiConfigService.buildMenuItems(cfg, user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("menu", items);
        Object bg = cfg.get("background");
        body.put("background", bg == null ? Map.of() : bg);
        return ResponseEntity.ok(body);
    }
}
