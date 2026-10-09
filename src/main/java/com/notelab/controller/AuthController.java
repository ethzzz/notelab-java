package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.common.ClientIp;
import com.notelab.dao.Db;
import com.notelab.common.Passwords;
import com.notelab.service.RateLimit;
import com.notelab.service.LoginAudit;
import com.notelab.service.PermService;
import com.notelab.common.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import com.notelab.dao.UserDao;

/** 认证：register / login / logout / me（契约与 Python 版一致） */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Pattern USERNAME_RE = Pattern.compile("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}");
    private static final Pattern EMAIL_RE = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** X-Auth-Purpose 取值：ai-lab 门禁（nginx /_ailab_auth 注入） */
    private static final String PURPOSE_AILAB = "ailab";

    public static class AuthReq {
        public String username;
        public String password;
        public String email = "";
    }

        @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) AuthReq req,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        // 注册入口已关闭：账号由超级管理员在「权限管理」中统一创建（POST /api/perm/users）
        return ResponseEntity.status(403).body(Map.of("error", "注册入口已关闭，请联系管理员创建账号"));
    }
@PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) AuthReq req,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String ip = ClientIp.of(request);
        if (!RateLimit.rateOk("login:" + ip, 10, 300)) {
            LoginAudit.record("b", request, req == null ? null : req.username, null, LoginAudit.RATE_LIMITED);
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请 5 分钟后再试"));
        }
        if (req == null || req.username == null || req.password == null) {
            LoginAudit.record("b", request, req == null ? null : req.username, null, LoginAudit.BAD_REQUEST);
            return badField();
        }
        // ⚠️ 这里从 `u == null || !verify(...)` 的短路拆成了两个 if：**响应与拆分前逐字节相同**
        //    （都是同一句 401），拆开只是为了让审计能区分「没这个人」与「密码错」——
        //    那个区分只进 login_audit，**绝不进响应**（否则等于送人一个用户名枚举接口）。
        Map<String, Object> u = UserDao.getUserByUsername(req.username.trim());
        if (u == null) {
            LoginAudit.record("b", request, req.username, null, LoginAudit.NO_SUCH_USER);
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        long uid = ((Number) u.get("id")).longValue();
        if (!Passwords.verify(req.password, (String) u.get("password_hash"))) {
            LoginAudit.record("b", request, req.username, uid, LoginAudit.BAD_PASSWORD);
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        LoginAudit.record("b", request, req.username, uid, LoginAudit.SUCCESS);
        Session.setCookie(response, Session.makeToken(uid));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 外部账号一键登录（匿名可用，登录页「外部登录」按钮专用）：
     * 服务端从 external 角色组里**随机挑一个**账号直接建立 B 端会话，全程不出现密码。
     *
     * <p>为什么这么做（2026-10-09 收敛）：最初实现是把 external1-10 的账号密码硬编码进
     * 登录页前端源码再随机回填表单 —— 那等于把 10 组永久弱口令公开在构建产物里。
     * 现在密码不再离开服务端：泄露面从「固定弱口令」收敛为「一个可开关、可频控的匿名入口」。
     * 外部人需要手动输账密登录的场景，由超管在权限管理里重置密码后线下单独告知。
     *
     * <p>开关：.env 配 {@code EXTERNAL_LOGIN_ENABLED=0} 一键关闭本入口（默认开）。
     * 两层频控：① 全站每日总量上限（默认 100 次/自然日，.env {@code EXTERNAL_LOGIN_DAILY_LIMIT} 可调，
     * 键按日期分片对齐自然日，Redis INCR 计数重启不丢）；② 单 IP 10 次/5 分钟（与 /api/login 同档）。
     * 结果照常进 login_audit。
     * 匿名请求在 {@link com.notelab.common.ApiPermInterceptor} 天然放行，无需注册 api 权限码；
     * 已登录用户点本按钮会被拦截器按权限码判 403 —— 无妨，登录页对已登录会话直接回跳，
     * 按钮根本不可见。
     */
    @PostMapping("/auth/external-login")
    public ResponseEntity<Map<String, Object>> externalLogin(HttpServletRequest request,
                                                             HttpServletResponse response) {
        if (!"1".equals(AppConfig.get("EXTERNAL_LOGIN_ENABLED", "1"))) {
            return ResponseEntity.status(403).body(Map.of("error", "外部登录入口已关闭"));
        }
        // ① 全站每日总量：键里带日期（自然日对齐），TTL 只做 Redis 清理，跨天自动换新键
        int dailyLimit;
        try {
            dailyLimit = Integer.parseInt(AppConfig.get("EXTERNAL_LOGIN_DAILY_LIMIT", "100").trim());
        } catch (NumberFormatException e) {
            dailyLimit = 100;
        }
        if (!RateLimit.rateOk("extlogin:daily:" + java.time.LocalDate.now(), dailyLimit, 86400)) {
            LoginAudit.record("b", request, null, null, LoginAudit.RATE_LIMITED);
            return ResponseEntity.status(429).body(Map.of("error", "今日外部登录次数已达上限，请明天再试"));
        }
        String ip = ClientIp.of(request);
        if (!RateLimit.rateOk("extlogin:" + ip, 10, 300)) {
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请 5 分钟后再试"));
        }
        java.util.List<Map<String, Object>> pool = UserDao.listUsersByRole(PermService.ROLE_EXTERNAL);
        if (pool.isEmpty()) {
            return ResponseEntity.status(503).body(Map.of("error", "暂无可用外部账号"));
        }
        Map<String, Object> pick = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        long uid = ((Number) pick.get("id")).longValue();
        String username = String.valueOf(pick.get("username"));
        LoginAudit.record("b", request, username, uid, LoginAudit.SUCCESS);
        Session.setCookie(response, Session.makeToken(uid));
        return ResponseEntity.ok(Map.of("ok", true, "username", username));
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
        body.put("role", user.get("role"));
        return ResponseEntity.ok(body);
    }

    /**
     * SSO 校验端点（nginx auth_request 子请求专用，ai-lab 等旁路服务门禁）：
     * 200 时通过 X-Auth-User 头透传 "b:<id>" / "c:<id>"，
     * 供 nginx auth_request_set 注入上游（子请求响应体不可取，只能走头）。
     *
     * <p>调用方通过 {@code X-Auth-Purpose} 声明「来意」，本端点据此做**用途级**判定：
     * <ul>
     *   <li>{@code ailab}（nginx /_ailab_auth 注入）—— **只认 C 端会话**，见下方实现注释。
     *       历史坑（2026-10-10）：原本这里是「B 端优先、C 端回落」，而 ai-lab 的
     *       {@code app/security.py get_c_user()} **只接受 `c:` 前缀**，于是浏览器里
     *       带 B 端会话（登录过 /admin）的用户会陷入 /ailab/ ↔ /login 死循环：
     *       门禁 200 放行 + X-Auth-User=b:&lt;id&gt; → ai-lab 页面加载 → 业务 API 全 401
     *       → 前端 gotoLogin() 跳 /login?next=/ailab/ → 登录页判定 C 端会话有效又自动跳回。
     *       实测：`X-Auth-User: c:11` → 200，`b:18` → 401。
     *       所以 ailab 用途下必须只认 C 端：没有 C 端会话就 401，让 nginx 把人送到
     *       C 端登录页（401 才会触发 {@code error_page 401 = @ailab_login}）。</li>
     *   <li>不带该头（协作画布的 WS 握手就是这么调的）—— 保持「B 端优先、C 端回落」，
     *       只验「是不是登录用户」，不做用途判定。**别把用途判定做成默认行为**，
     *       否则画布会跟着受连累。</li>
     * </ul>
     */
    @GetMapping("/auth/verify")
    public ResponseEntity<Map<String, Object>> verify(HttpServletRequest request,
                                                      @RequestHeader(value = "X-Auth-Purpose", required = false) String purpose) {
        // ai-lab 门禁：只认 C 端会话（理由见方法注释）。外部账号（external）是 B 端用户组的
        // 概念，C 端账号不属于它，故这里无需再判 isExternal。
        if (PURPOSE_AILAB.equals(purpose)) {
            Map<String, Object> cUser = CAuthUtil.user(request);
            if (cUser == null) return AuthUtil.unauth();
            return ResponseEntity.ok()
                    .header("X-Auth-User", "c:" + AuthUtil.userId(cUser))
                    .body(Map.of("ok", true, "scope", "c", "id", cUser.get("id")));
        }

        // 其余用途（含画布 WS 握手，不带 X-Auth-Purpose）：B 端优先、C 端回落
        Map<String, Object> user = AuthUtil.user(request);
        String prefix = "b";
        if (user == null) {
            user = CAuthUtil.user(request);
            prefix = "c";
        }
        if (user == null) return AuthUtil.unauth();
        return ResponseEntity.ok()
                .header("X-Auth-User", prefix + ":" + AuthUtil.userId(user))
                .body(Map.of("ok", true, "scope", prefix, "id", user.get("id")));
    }

    /** 对齐 FastAPI 缺字段时的 422 */
    private static ResponseEntity<Map<String, Object>> badField() {
        return ResponseEntity.status(422).body(Map.of("detail",
                java.util.List.of(Map.of("type", "missing", "loc", java.util.List.of("body"), "msg", "field required"))));
    }
}
