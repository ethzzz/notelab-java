package com.notelab.controller;

import com.notelab.common.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** C 端认证工具（B/C 拆分阶段1）：与 AuthUtil 并列，只认 notelab_c_session（4 段 c. token）。 */
public final class CAuthUtil {

    private CAuthUtil() {}

    /** 未登录统一响应：401 {"error": "请先登录"}（与 B 端一致） */
    public static ResponseEntity<Map<String, Object>> unauth() {
        return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
    }

    /** 解析 C 端会话 Cookie → c_users 行（仅 status='active'），未登录返回 null */
    public static Map<String, Object> user(HttpServletRequest request) {
        return Session.currentUserC(request);
    }

    public static long userId(Map<String, Object> user) {
        return ((Number) user.get("id")).longValue();
    }
}
