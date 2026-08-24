package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.common.Passwords;
import com.notelab.common.Session;
import com.notelab.dao.CUserDao;
import com.notelab.dao.Db;
import com.notelab.service.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C 端认证（B/C 拆分阶段1）：/api/c/auth 下 register / login / logout / me。
 * 会话走 notelab_c_session（4 段 c. token），与 B 端 notelab_session 完全隔离。
 */
@RestController
@RequestMapping("/api/c/auth")
public class CAuthController {

    private static final Pattern USERNAME_RE = Pattern.compile("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}");

    public static class CAuthReq {
        public String username;
        public String password;
        public String nickname = "";
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) CAuthReq req,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String ip = AuthUtil.clientIp(request);
        // 限流用法对齐 AuthController：同 IP 10 次 / 300s（独立计数桶，不与 B 端互相影响）
        if (!RateLimit.rateOk("c-login:" + ip, 10, 300)) {
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请 5 分钟后再试"));
        }
        if (req == null || req.username == null || req.password == null) {
            return badField();
        }
        Map<String, Object> u = CUserDao.getCUserByUsername(req.username.trim());
        if (u == null || !Passwords.verify(req.password, (String) u.get("password_hash"))) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        if (!"active".equals(u.get("status"))) {
            return ResponseEntity.status(403).body(Map.of("error", "账号已被禁用"));
        }
        Session.setCookieC(response, Session.makeCToken(((Number) u.get("id")).longValue()));
        return ResponseEntity.ok(loginBody(u));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.get("id"));
        body.put("username", user.get("username"));
        body.put("nickname", user.get("nickname"));
        body.put("group_code", user.get("group_code"));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletResponse response) {
        Session.deleteCookieC(response);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 注册：受开关 C_REGISTER_OPEN 控制，默认关闭 */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) CAuthReq req,
                                                        HttpServletResponse response) {
        if (!"true".equalsIgnoreCase(AppConfig.get("C_REGISTER_OPEN", "false").trim())) {
            return ResponseEntity.status(403).body(Map.of("error", "注册未开放"));
        }
        if (req == null || req.username == null || req.password == null) {
            return badField();
        }
        String username = req.username.trim();
        if (!USERNAME_RE.matcher(username).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "用户名需为 2-20 位字母/数字/下划线/中文"));
        }
        if (req.password.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "密码至少 6 位"));
        }
        if (CUserDao.getCUserByUsername(username) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名已存在"));
        }
        String nickname = req.nickname == null ? "" : req.nickname.trim();
        long uid;
        try {
            uid = CUserDao.createCUser(username, Passwords.hash(req.password), nickname, "default");
        } catch (Db.UniqueViolation e) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名已存在"));
        }
        Map<String, Object> u = CUserDao.getCUserById(uid);
        Session.setCookieC(response, Session.makeCToken(uid));
        return ResponseEntity.ok(loginBody(u));
    }

    /** 登录/注册成功响应：ok + user 行（id/username/nickname/group_code，字段风格对齐 B 端 me） */
    private static Map<String, Object> loginBody(Map<String, Object> u) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", u.get("id"));
        body.put("username", u.get("username"));
        body.put("nickname", u.get("nickname"));
        body.put("group_code", u.get("group_code"));
        return body;
    }

    /** 对齐 FastAPI 缺字段时的 422 */
    private static ResponseEntity<Map<String, Object>> badField() {
        return ResponseEntity.status(422).body(Map.of("detail",
                java.util.List.of(Map.of("type", "missing", "loc", java.util.List.of("body"), "msg", "field required"))));
    }
}
