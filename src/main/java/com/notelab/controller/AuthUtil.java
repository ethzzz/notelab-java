package com.notelab.controller;

import com.notelab.common.Session;
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

    // 取客户端 IP 的方法已收敛到 com.notelab.common.ClientIp.of(request)。
    // ⚠️ 不要在这里加回 clientIp() —— 它的旧实现取 X-Forwarded-For 第一项，
    //    而 nginx 是追加语义（客户端伪造值排在最前），会让限流按伪造值分桶 = 形同虚设。
    //    详见 ClientIp 的类注释与 2026-10-08 的实测记录。
}
