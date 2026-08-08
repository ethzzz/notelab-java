package com.notelab.web;

import com.notelab.Db;
import com.notelab.PermService;
import com.notelab.UiConfigService;
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
        List<Map<String, Object>> items = UiConfigService.buildMenuItems(cfg);
        // RBAC：非超级管理员只能看到其角色路由组内的页面菜单
        if (!PermService.isSuperAdmin(user)) {
            Object role = user.get("role");
            Set<String> allowed = role == null
                    ? Set.of()
                    : new HashSet<>(Db.roleRouteCodes(role.toString()));
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> it : items) {
                if (allowed.contains("page:" + it.get("path"))) filtered.add(it);
            }
            items = filtered;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("menu", items);
        Object bg = cfg.get("background");
        body.put("background", bg == null ? Map.of() : bg);
        return ResponseEntity.ok(body);
    }
}
