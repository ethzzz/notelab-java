package com.notelab.common;

import com.notelab.controller.AuthUtil;
import com.notelab.dao.PermDao;
import com.notelab.service.PermService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 接口级权限拦截器（默认拒绝），由 {@link WebConfig} 注册到 {@code /api/**}。
 *
 * <p>只做**门槛**，不做业务鉴权：内部还要叠加各 Controller 自己的判断（未登录 → 401、
 * 管理功能 → {@code isSuperAdmin}），这是刻意保留的**双保险** —— 就算权限码配错，
 * Controller 那层仍然拦得住。
 *
 * <p>三个必须放行的场景（漏掉任何一个都会造成明显故障）：
 * <ol>
 *   <li>**OPTIONS 预检**：拦截器的 {@code addPathPatterns("/api/**")} 也会命中 CORS 预检，
 *       一旦拦下，所有跨域调用直接全灭。</li>
 *   <li>**未登录 / 非 B 端会话**：{@code AuthUtil.user(request)} 为 null 时直接放行，
 *       交给各 Controller 自己的 {@code AuthUtil.unauth()}。本次只针对**已持有 B 端会话**的用户做隔离，
 *       C 端用户（另一套 Cookie）与匿名请求都不在范围内。</li>
 *   <li>**super_admin**：天然全放行，与菜单树的 {@code PermService.allowedPagePaths} 保持一致
 *       （注意 super_admin 在 perm_role_routes 里是 0 行，权限不靠数据）。</li>
 * </ol>
 */
public final class ApiPermInterceptor implements HandlerInterceptor {

    private final UrlPathHelper pathHelper = new UrlPathHelper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // ① CORS 预检：不是真请求，放行交由 Spring 的 CORS 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        // ② 未登录 / 不是 B 端会话（C 端用户、匿名）→ 沿用各 Controller 自有判断
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) return true;

        // ③ 超级管理员天然全放行（含将来新增的路由）
        if (PermService.isSuperAdmin(user)) return true;

        Object role = user.get("role");
        Set<String> codes = role == null
                ? Set.of()
                : new HashSet<>(PermDao.roleRouteCodes(role.toString()));

        String reason = PermGuard.denyReason(pathHelper.getPathWithinApplication(request), codes);
        if (reason == null) return true;

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtil.write(Map.of("error", reason)));
        return false;
    }
}
