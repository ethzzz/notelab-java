package com.notelab.web;

import com.notelab.Db;
import com.notelab.Passwords;
import com.notelab.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限管理（仅超级管理员）：
 *  GET  /api/perm/overview            路由表 + 角色 + 账户总览
 *  POST /api/perm/roles/{code}/routes 给角色分配路由组（权限码集合）
 *  POST /api/perm/users               创建账户（注册入口关闭后由管理员建号）
 *  POST /api/perm/users/{id}/role     把角色赋给账户
 *  POST /api/perm/users/{id}/password 重置账户密码
 */
@RestController
@RequestMapping("/api/perm")
public class PermController {

    private static final Pattern USERNAME_RE = Pattern.compile("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}");
    private static final Pattern EMAIL_RE = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    public static class RoutesReq { public List<String> codes; }
    public static class CreateUserReq { public String username; public String password; public String email = ""; public String role = "user"; }
    public static class RoleReq { public String role; }
    public static class PasswordReq { public String password; }

    /** 守卫：未登录 401；非超级管理员 403 */
    private static Map<String, Object> guard(HttpServletRequest request) {
        return AuthUtil.user(request);
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpServletRequest request) {
        Map<String, Object> me = guard(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return forbidden();

        List<Map<String, Object>> roles = new ArrayList<>();
        for (String[] r : new String[][]{{PermService.ROLE_ADMIN, "超级管理员"}, {PermService.ROLE_USER, "普通用户"}}) {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("code", r[0]);
            role.put("name", r[1]);
            role.put("route_codes", Db.roleRouteCodes(r[0]));
            roles.add(role);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("me", Map.of("id", me.get("id"), "username", me.get("username"), "role", String.valueOf(me.get("role"))));
        body.put("routes", Db.listRoutes());
        body.put("roles", roles);
        body.put("users", Db.listUsersForPerm());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/roles/{code}/routes")
    public ResponseEntity<Map<String, Object>> setRoleRoutes(@PathVariable String code,
                                                             @RequestBody(required = false) RoutesReq req,
                                                             HttpServletRequest request) {
        Map<String, Object> me = guard(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return forbidden();
        if (!PermService.isValidRole(code)) {
            return ResponseEntity.status(404).body(Map.of("error", "角色不存在"));
        }
        if (PermService.ROLE_ADMIN.equals(code)) {
            return ResponseEntity.status(400).body(Map.of("error", "超级管理员默认拥有全部路由（含未来新增），无需分配"));
        }
        List<String> codes = req == null || req.codes == null ? List.of() : req.codes;
        // 过滤掉不存在的权限码，避免脏数据
        Set<String> known = new LinkedHashSet<>();
        for (Map<String, Object> r : Db.listRoutes()) known.add((String) r.get("code"));
        List<String> valid = new ArrayList<>();
        for (String c : codes) if (c != null && known.contains(c)) valid.add(c);
        Db.setRoleRoutes(code, valid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody(required = false) CreateUserReq req,
                                                          HttpServletRequest request) {
        Map<String, Object> me = guard(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return forbidden();
        if (req == null || req.username == null || req.password == null) {
            return ResponseEntity.status(400).body(Map.of("error", "用户名和密码不能为空"));
        }
        String username = req.username.trim();
        if (!USERNAME_RE.matcher(username).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "用户名需为 2-20 位字母/数字/下划线/中文"));
        }
        if (req.password.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "密码至少 6 位"));
        }
        String email = req.email == null ? "" : req.email.trim().toLowerCase();
        if (!email.isEmpty() && !EMAIL_RE.matcher(email).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "邮箱格式不正确"));
        }
        String role = PermService.isValidRole(req.role) ? req.role : "user";
        if (Db.getUserByUsername(username) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名已存在"));
        }
        if (!email.isEmpty() && Db.getUserByEmail(email) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "该邮箱已被注册"));
        }
        long uid;
        try {
            uid = Db.createUserWithRole(username, Passwords.hash(req.password), email.isEmpty() ? null : email, role);
        } catch (Db.UniqueViolation e) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名或邮箱已存在"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "id", uid));
    }

    @PostMapping("/users/{id}/role")
    public ResponseEntity<Map<String, Object>> setUserRole(@PathVariable long id,
                                                           @RequestBody(required = false) RoleReq req,
                                                           HttpServletRequest request) {
        Map<String, Object> me = guard(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return forbidden();
        if (req == null || !PermService.isValidRole(req.role)) {
            return ResponseEntity.status(400).body(Map.of("error", "角色无效"));
        }
        Map<String, Object> target = Db.getUserById(id);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        long meId = ((Number) me.get("id")).longValue();
        if (id == meId && !PermService.ROLE_ADMIN.equals(req.role) && Db.countSuperAdmins() <= 1) {
            return ResponseEntity.status(400).body(Map.of("error", "至少需要保留一名超级管理员"));
        }
        Db.setUserRole(id, req.role);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/users/{id}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable long id,
                                                             @RequestBody(required = false) PasswordReq req,
                                                             HttpServletRequest request) {
        Map<String, Object> me = guard(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return forbidden();
        if (req == null || req.password == null || req.password.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "密码至少 6 位"));
        }
        Map<String, Object> target = Db.getUserById(id);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "用户不存在"));
        }
        Db.setUserPassword(id, Passwords.hash(req.password));
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
