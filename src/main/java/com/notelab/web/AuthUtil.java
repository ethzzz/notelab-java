package com.notelab.web;

import com.notelab.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class AuthUtil {

    private AuthUtil() {}

    /** 未登录统一响应：401 {"error": "请先登录"} */
    public static ResponseEntity<Map<String, Object>> unauth() {
        return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }

    public static Map<String, Object> user(HttpServletRequest request) {
        return Session.currentUser(request);
    }

    public static long userId(Map<String, Object> user) {
        return ((Number) user.get("id")).longValue();
    }

    public static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("x-forwarded-for");
        if (fwd != null && !fwd.isEmpty()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
