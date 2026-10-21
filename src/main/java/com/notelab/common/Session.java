package com.notelab.common;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import com.notelab.dao.UserDao;
import com.notelab.dao.CUserDao;

/**
 * HMAC 会话 Cookie，与 Python 版 make_token / parse_token 完全一致，保证切流后登录态兼容。
 *
 * <p><b>会话版本（2026-10-21 加）</b>：token 里带 {@code session_version}，与库中值比对。
 * 改密码 / 管理员重置密码 时把版本 +1，该用户**之前签发的所有 token 立即失效**。
 * 在此之前 token 只有 uid.exp —— 一旦泄露只能等 30 天自然过期，没有任何撤销手段。
 *
 * <p>格式演进（**刻意做成向后兼容**）：
 * <pre>
 *   B 端旧： uid.exp.sig          （3 段）→ 视为版本 0
 *   B 端新： uid.exp.ver.sig      （4 段，首段是数字）
 *   C 端旧： c.uid.exp.sig        （4 段，首段是 "c"）
 *   C 端新： c.uid.exp.ver.sig    （5 段）
 * </pre>
 * 靠「段数 + 首段是不是 c」区分，天然不会互相认错。旧 token 按版本 0 处理，
 * 而库里的 session_version 默认也是 0 —— 所以**这次升级不会把任何在线用户踢下线**，
 * 只有他改过密码之后旧会话才失效。签名 payload 也包含了 ver，旧 token 仍按原 payload 验签。
 */
public final class Session {

    private Session() {}

    /** 解析结果：uid + 会话版本（旧 token 的 version 恒为 0） */
    public record Parsed(long uid, int version) {}

    private static String hmacSha256Hex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(AppConfig.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 常量时间比较，避免按字节提前返回泄露签名前缀 */
    private static boolean sigEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * token = base64url("{uid}.{exp}.{ver}.{hmac_sha256_hex(uid.exp.ver)}")
     * （含 = 填充，与 Python urlsafe_b64encode 一致）
     *
     * <p>版本在签发时就从库里取并写进签名，之后比对方能发现「已被撤销」。
     */
    public static String makeToken(long userId) {
        long exp = System.currentTimeMillis() / 1000 + AppConfig.SESSION_TTL;
        int ver = UserDao.sessionVersion(userId);
        String payload = userId + "." + exp + "." + ver;
        return Base64.getUrlEncoder().encodeToString(
                (payload + "." + hmacSha256Hex(payload)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析并验签，返回 uid + 版本；格式不对 / 签名错 / 过期一律返回 null。
     *
     * <p>⚠️ 这里**只验签与过期**，不查版本是否仍有效 —— 那需要一次查库，
     * 放在 {@link #currentUser} 里与取用户行合并，避免两次查询。
     */
    public static Parsed parseToken(String token) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = s.split("\\.");
            String payload;
            String sig;
            int ver;
            if (parts.length == 3) {
                // 旧格式：uid.exp.sig（版本按 0 处理）
                payload = parts[0] + "." + parts[1];
                sig = parts[2];
                ver = 0;
            } else if (parts.length == 4 && !"c".equals(parts[0])) {
                // 新格式：uid.exp.ver.sig
                payload = parts[0] + "." + parts[1] + "." + parts[2];
                sig = parts[3];
                ver = Integer.parseInt(parts[2]);
            } else {
                return null;
            }
            if (!sigEquals(hmacSha256Hex(payload), sig)) return null;
            long exp = Long.parseLong(parts[1]);
            if (exp < System.currentTimeMillis() / 1000) return null;
            return new Parsed(Long.parseLong(parts[0]), ver);
        } catch (Exception e) {
            return null;
        }
    }

    /** 等价于 Python get_current_user(request) */
    public static Map<String, Object> currentUser(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (AppConfig.SESSION_COOKIE.equals(c.getName())) {
                Parsed p = parseToken(c.getValue());
                if (p == null) return null;
                Map<String, Object> u = UserDao.getUserById(p.uid());
                if (u == null) return null;
                // 版本不一致 = 该 token 已被撤销（改密码 / 重置密码）
                if (versionOf(u) != p.version()) return null;
                return u;
            }
        }
        return null;
    }

    /** 从用户行里取 session_version（列缺失或类型异常时按 0，与旧 token 口径一致） */
    private static int versionOf(Map<String, Object> row) {
        Object v = row.get("session_version");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 统一的 Set-Cookie 构造：{@code SameSite=Lax} + {@code HttpOnly} 恒定；
     * {@code Secure} 由 {@link AppConfig#cookieSecure()} 决定（生产 .env 开、本地 HTTP 开发关）。
     *
     * <p>抽成一个方法是因为四个出口（B/C 端 × 设置/清除）原先各抄了一遍同样的属性链 ——
     * 想加一个安全属性就得改四处，漏一处就会出现「一半 Cookie 有保护」。
     */
    private static void writeCookie(HttpServletResponse resp, String name, String value, long maxAge) {
        org.springframework.http.ResponseCookie.ResponseCookieBuilder b =
                org.springframework.http.ResponseCookie.from(name, value)
                        .maxAge(maxAge)
                        .path("/")
                        .sameSite("Lax")
                        .httpOnly(true);
        if (AppConfig.cookieSecure()) b.secure(true);
        resp.addHeader("Set-Cookie", b.build().toString());
    }

    public static void setCookie(HttpServletResponse resp, String token) {
        // 对齐 Python：set_cookie(..., max_age=SESSION_TTL, httponly=True, samesite="lax")
        writeCookie(resp, AppConfig.SESSION_COOKIE, token, AppConfig.SESSION_TTL);
    }

    public static void deleteCookie(HttpServletResponse resp) {
        writeCookie(resp, AppConfig.SESSION_COOKIE, "", 0);
    }

    // ================= C 端（B/C 拆分阶段1）：独立 token，与 B 端天然互不认 =================

    /** C 端新 token = base64url("c.{uid}.{exp}.{ver}.{hmac(c.uid.exp.ver)}")（5 段） */
    public static String makeCToken(long userId) {
        long exp = System.currentTimeMillis() / 1000 + AppConfig.SESSION_TTL;
        int ver = CUserDao.sessionVersion(userId);
        String payload = "c." + userId + "." + exp + "." + ver;
        return Base64.getUrlEncoder().encodeToString(
                (payload + "." + hmacSha256Hex(payload)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 C 端 token：新格式 5 段（c.uid.exp.ver.sig），旧格式 4 段（c.uid.exp.sig，版本按 0）。
     * 首段必须是 "c" —— 这是与 B 端 token 的结构性区分。
     */
    public static Parsed parseCToken(String token) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = s.split("\\.");
            String payload;
            String sig;
            int ver;
            if (parts.length == 4 && "c".equals(parts[0])) {
                // 旧格式：c.uid.exp.sig
                payload = parts[0] + "." + parts[1] + "." + parts[2];
                sig = parts[3];
                ver = 0;
            } else if (parts.length == 5 && "c".equals(parts[0])) {
                // 新格式：c.uid.exp.ver.sig
                payload = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
                sig = parts[4];
                ver = Integer.parseInt(parts[3]);
            } else {
                return null;
            }
            if (!sigEquals(hmacSha256Hex(payload), sig)) return null;
            long exp = Long.parseLong(parts[2]);
            if (exp < System.currentTimeMillis() / 1000) return null;
            return new Parsed(Long.parseLong(parts[1]), ver);
        } catch (Exception e) {
            return null;
        }
    }

    /** 等价 B 端 currentUser：读 notelab_c_session → c_users 行；仅 status='active' 且版本一致才有效 */
    public static Map<String, Object> currentUserC(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (AppConfig.SESSION_COOKIE_C.equals(c.getName())) {
                Parsed p = parseCToken(c.getValue());
                if (p == null) return null;
                Map<String, Object> u = CUserDao.getCUserById(p.uid());
                if (u == null || !"active".equals(u.get("status"))) return null;
                // 版本不一致 = 已被撤销（C 端改密码 / 管理员重置）
                if (versionOf(u) != p.version()) return null;
                return u;
            }
        }
        return null;
    }

    public static void setCookieC(HttpServletResponse resp, String token) {
        writeCookie(resp, AppConfig.SESSION_COOKIE_C, token, AppConfig.SESSION_TTL);
    }

    public static void deleteCookieC(HttpServletResponse resp) {
        writeCookie(resp, AppConfig.SESSION_COOKIE_C, "", 0);
    }
}
