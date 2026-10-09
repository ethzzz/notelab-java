package com.notelab.common;

import com.notelab.controller.AuthUtil;
import com.notelab.controller.CAuthUtil;
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
 *   <li>**匿名请求**：B 端与 C 端会话都没有时直接放行，交给各 Controller 自己的 {@code unauth()}。
 *       注意 C 端大量接口本来就是匿名可访问的（站点配置、内容下发、埋点），不能要求登录。</li>
 *   <li>**super_admin**：天然全放行，与菜单树的 {@code PermService.allowedPagePaths} 保持一致
 *       （注意 super_admin 在 perm_role_routes 里是 0 行，权限不靠数据）。</li>
 * </ol>
 *
 * <p>⚠️ **两套会话各有各的规则**，判定时先认 B 端、再认 C 端：
 * B 端会话 → {@link PermGuard}（按 {@code perm_roles} 角色码，且 {@code /api/c/**} 对它一律豁免）；
 * C 端会话 → {@link CPermGuard}（按 {@code c_user_groups} 用户组码，只管 {@code /api/c/**}）。
 */
public final class ApiPermInterceptor implements HandlerInterceptor {

    private final UrlPathHelper pathHelper = new UrlPathHelper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // ① CORS 预检：不是真请求，放行交由 Spring 的 CORS 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String path = pathHelper.getPathWithinApplication(request);

        // ② 不是 B 端会话：可能是 C 端用户（另一套 Cookie）或匿名。
        //    匿名放行（交给各 Controller 的 unauth）；C 端用户按**其用户组**持有的路由码校验
        //    /api/c/**（见 CPermGuard）—— 这就是 C 端权限的真正生效点。
        Map<String, Object> user = AuthUtil.user(request);
        if (user == null) {
            Map<String, Object> cUser = CAuthUtil.user(request);
            if (cUser == null) return true;         // 匿名
            Object group = cUser.get("group_code");
            Set<String> cCodes = group == null
                    ? Set.of()
                    : new HashSet<>(PermService.cGroupRouteCodes(group.toString()));
            // 该用户组一条路由都没配（新组尚未初始化 / group_code 指向已删除的组）→ 按「未启用限制」放行。
            // 与 CPermGuard 的 fail-open 取向一致：C 端默认人人可用，权限是**收回**动作；
            // 反过来若按默认拒绝，一个脏 group_code 就能让用户连存档都读不了。
            if (cCodes.isEmpty()) return true;
            String cReason = CPermGuard.denyReason(path, cCodes);
            if (cReason == null) return true;
            writeDenied(response, cReason);
            return false;
        }

        // ③ 超级管理员天然全放行（含将来新增的路由）
        if (PermService.isSuperAdmin(user)) return true;

        // ④ B 端角色按角色组的权限码校验（C 端接口对 B 端会话一律豁免，见 PermGuard.C_ENDPOINT_PREFIX）
        Object role = user.get("role");
        Set<String> codes = role == null
                ? Set.of()
                : new HashSet<>(PermDao.roleRouteCodes(role.toString()));

        String reason = PermGuard.denyReason(path, codes);
        if (reason == null) return true;
        writeDenied(response, reason);
        return false;
    }

    private static void writeDenied(HttpServletResponse response, String reason) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtil.write(Map.of("error", reason)));
    }
}
