package com.notelab;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/** HMAC 会话 Cookie，与 Python 版 make_token / parse_token 完全一致，保证切流后登录态兼容。 */
public final class Session {

    private Session() {}

    private static String hmacSha256Hex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(AppConfig.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** token = base64url("<uid>.<exp>.<hmac_sha256_hex(uid.exp)>")（含 = 填充，与 Python urlsafe_b64encode 一致） */
    public static String makeToken(long userId) {
        long exp = System.currentTimeMillis() / 1000 + AppConfig.SESSION_TTL;
        String payload = userId + "." + exp;
        String sig = hmacSha256Hex(payload);
        return Base64.getUrlEncoder().encodeToString((payload + "." + sig).getBytes(StandardCharsets.UTF_8));
    }

    /** 解析并验签，返回 uid；无效/过期返回 null */
    public static Long parseToken(String token) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = s.split("\\.");
            if (parts.length != 3) return null;
            String payload = parts[0] + "." + parts[1];
            String expected = hmacSha256Hex(payload);
            if (!java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            long exp = Long.parseLong(parts[1]);
            if (exp < System.currentTimeMillis() / 1000) return null;
            return Long.parseLong(parts[0]);
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
                Long uid = parseToken(c.getValue());
                if (uid == null) return null;
                return Db.getUserById(uid);
            }
        }
        return null;
    }

    public static void setCookie(HttpServletResponse resp, String token) {
        // Python: resp.set_cookie(SESSION_COOKIE, token, max_age=SESSION_TTL, httponly=True, samesite="lax")
        org.springframework.http.ResponseCookie rc = org.springframework.http.ResponseCookie
                .from(AppConfig.SESSION_COOKIE, token)
                .maxAge(AppConfig.SESSION_TTL)
                .path("/")
                .sameSite("Lax")
                .httpOnly(true)
                .build();
        resp.addHeader("Set-Cookie", rc.toString());
    }

    public static void deleteCookie(HttpServletResponse resp) {
        org.springframework.http.ResponseCookie rc = org.springframework.http.ResponseCookie
                .from(AppConfig.SESSION_COOKIE, "")
                .maxAge(0)
                .path("/")
                .sameSite("Lax")
                .httpOnly(true)
                .build();
        resp.addHeader("Set-Cookie", rc.toString());
    }
}
