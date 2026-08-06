package com.notelab.web;

import com.notelab.AppConfig;
import com.notelab.Db;
import com.notelab.Passwords;
import com.notelab.RateLimit;
import com.notelab.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 认证：register / login / logout / me（契约与 Python 版一致） */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Pattern USERNAME_RE = Pattern.compile("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}");
    private static final Pattern EMAIL_RE = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    public static class AuthReq {
        public String username;
        public String password;
        public String email = "";
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) AuthReq req,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        String ip = AuthUtil.clientIp(request);
        if (!RateLimit.rateOk("register:" + ip, 5, 600)) {
            return ResponseEntity.status(429).body(Map.of("error", "注册过于频繁，请 10 分钟后再试"));
        }
        if (req == null || req.username == null || req.password == null) {
            return badField();
        }
        String username = req.username.trim();
        String password = req.password;
        if (!USERNAME_RE.matcher(username).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "用户名需为 2-20 位字母/数字/下划线/中文"));
        }
        if (password.length() < 6) {
            return ResponseEntity.status(400).body(Map.of("error", "密码至少 6 位"));
        }
        String email = req.email == null ? "" : req.email.trim().toLowerCase();
        if (!EMAIL_RE.matcher(email).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "邮箱格式不正确"));
        }
        if (Db.getUserByUsername(username) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名已存在"));
        }
        if (Db.getUserByEmail(email) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "该邮箱已被注册"));
        }
        long uid;
        try {
            uid = Db.createUser(username, Passwords.hash(password), email);
        } catch (Db.UniqueViolation e) {
            return ResponseEntity.status(409).body(Map.of("error", "用户名或邮箱已存在"));
        }
        Session.setCookie(response, Session.makeToken(uid));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) AuthReq req,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String ip = AuthUtil.clientIp(request);
        if (!RateLimit.rateOk("login:" + ip, 10, 300)) {
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请 5 分钟后再试"));
        }
        if (req == null || req.username == null || req.password == null) {
            return badField();
        }
        Map<String, Object> u = Db.getUserByUsername(req.username.trim());
        if (u == null || !Passwords.verify(req.password, (String) u.get("password_hash"))) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        Session.setCookie(response, Session.makeToken(((Number) u.get("id")).longValue()));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletResponse response) {
        Session.deleteCookie(response);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return AuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.get("id"));
        body.put("username", user.get("username"));
        body.put("email", user.get("email"));
        return ResponseEntity.ok(body);
    }

    /** 对齐 FastAPI 缺字段时的 422 */
    private static ResponseEntity<Map<String, Object>> badField() {
        return ResponseEntity.status(422).body(Map.of("detail",
                java.util.List.of(Map.of("type", "missing", "loc", java.util.List.of("body"), "msg", "field required"))));
    }
}
