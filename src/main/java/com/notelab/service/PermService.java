package com.notelab.service;

import com.notelab.common.PermGuard;
import com.notelab.model.PageRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import com.notelab.dao.Db;
import com.notelab.dao.UserDao;
import com.notelab.dao.PermDao;

/**
 * RBAC 权限服务。
 *
 * 权限码规则：
 *  - 页面路由  code = "page:<path>"（如 page:/chat），驱动菜单可见性
 *  - API 路由  code = "api:<path>"（如 api:/api/chat），启动时从 SpringMVC 请求映射自动采集
 *
 * ⚠️ 2026-09-27 起 api:* **会被真正校验**：common.ApiPermInterceptor 对 /api/** 做**默认拒绝**，
 *    角色组没显式持有对应权限码则 403；user 组的 api 权限由 registerAllRoutes 第 5 步自动同步。
 *    C 端接口（/api/c/**）与未登录请求豁免 —— 本次只隔离 B 端登录用户。
 *
 * 自动注册：每次启动时把当前所有 Controller 路由 + 前端页面路由 upsert 进 perm_routes 表，
 * 以后新增路由无需手工登记，重启即自动出现在权限路由表里。
 *
 * 角色：super_admin（超级管理员，天然拥有全部路由，含未来新增）/ user（普通用户，按 perm_role_routes 分配）。
 */
public final class PermService {

    public static final String ROLE_ADMIN = "super_admin";
    public static final String ROLE_USER = "user";

    private static final Logger log = LoggerFactory.getLogger(PermService.class);

    private PermService() {}

    /** Bootstrap 在 Db.init() 之后调用。 */
    public static void registerAllRoutes(RequestMappingHandlerMapping mapping) {
        // 1) 页面路由
        for (String[] p : PageRoutes.PAGE_ROUTES) {
            PermDao.upsertRoute("page:" + p[0], p[0], "", "page", p[1]);
        }
        // 2) API 路由：从 SpringMVC 请求映射自动采集（新增 Controller 重启即自动注册）
        Map<String, Set<String>> methodsByPath = new TreeMap<>();
        Map<String, String> nameByPath = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = new LinkedHashSet<>();
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatterns().forEach(pp -> patterns.add(pp.getPatternString()));
            } else if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            String methods = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("ANY");
            for (String path : patterns) {
                methodsByPath.computeIfAbsent(path, k -> new TreeSet<>()).add(methods);
                nameByPath.putIfAbsent(path, e.getValue().getMethod().getName());
            }
        }
        for (Map.Entry<String, Set<String>> e : methodsByPath.entrySet()) {
            PermDao.upsertRoute("api:" + e.getKey(), e.getKey(), String.join("|", e.getValue()),
                    "api", nameByPath.getOrDefault(e.getKey(), ""));
        }
        // 3) 普通角色默认权限（仅首次为空时写入，不覆盖已有配置）：全部功能页，不含 ui/perm 管理页
        if (PermDao.roleRouteCodes(ROLE_USER).isEmpty()) {
            List<String> defaults = new ArrayList<>();
            for (String[] p : PageRoutes.PAGE_ROUTES) {
                if (!p[0].equals("/ui") && !p[0].equals("/perm")) defaults.add("page:" + p[0]);
            }
            PermDao.setRoleRoutes(ROLE_USER, defaults);
        }
        // 4) 首次启动若尚无超级管理员：把最早注册的用户提升为超级管理员（避免无人可管理）
        if (UserDao.countSuperAdmins() == 0) {
            UserDao.promoteFirstUserToAdmin();
        }
        // 5) 默认拒绝下的普通用户组接口权限：非受限 api 补齐、受限 api 移除（见方法注释）
        syncUserApiPerms();
        // 6) 刷新拦截器用的路由表。**必须排在最后**：要晚于上面所有 upsert，否则新路由不在表里
        PermGuard.reload();
    }

    /**
     * 普通用户组的 {@code api:*} 权限码**补齐 + 裁剪**（幂等，规则确定性）。
     *
     * <p>为什么非做不可：接口拦截器是**默认拒绝**的，角色组"恰好没勾"就等于"全禁止"。
     * 改造前 user 组能随便调 {@code /api/c-admin/users} 拿到全部 C 端用户，
     * 正是因为从来没人管过 {@code api:*} 这一栏 —— 光加拦截器不补数据，会把功能全闸掉。
     *
     * <p>为什么可以自动写：划分规则是确定的（非受限全给、受限全不给），没有需要人肉判断的余地，
     * 且用户已确认过受限范围（用户管理系统 + 界面配置）。
     *
     * <p>⚠️ **只动 {@code api:*}**：管理员工调整的 {@code page:*}（页面可见性）一律保留原样 ——
     * 那是另一回事，在这里重算会吃掉手工配置。
     *
     * <p>⚠️ 只处理 {@link #ROLE_USER}。自建角色组仍需管理员到「角色组管理」里勾 ——
     * 它们的意图没法推断，不该被代码覆盖。
     */
    private static void syncUserApiPerms() {
        List<String> before = PermDao.roleRouteCodes(ROLE_USER);
        Set<String> keep = new LinkedHashSet<>();
        for (String c : before) {
            if (c != null && c.startsWith("page:")) keep.add(c);
        }
        int restricted = 0;
        int given = 0;
        for (Map<String, Object> r : PermDao.listRoutes()) {
            if (!"api".equals(String.valueOf(r.get("kind")))) continue;
            String p = String.valueOf(r.get("path"));
            if (p == null || p.isEmpty()) continue;
            if (PermGuard.isRestricted(p)) {
                restricted++;
                continue;
            }
            keep.add("api:" + p);
            given++;
        }
        List<String> want = new ArrayList<>(keep);
        // 无变化就不写库 —— 每次启动都做一遍 delete + insert 没意义
        if (before.size() == want.size() && new HashSet<>(before).containsAll(want)) {
            log.info("普通用户组接口权限已是最新（api {} 条给 / {} 条受限），跳过写库", given, restricted);
            return;
        }
        PermDao.setRoleRoutes(ROLE_USER, want);
        log.info("已同步普通用户组接口权限：api {} 条给 / {} 条受限，page 权限保留 {} 条",
                given, restricted, want.size() - given);
    }

    public static boolean isSuperAdmin(Map<String, Object> user) {
        return user != null && ROLE_ADMIN.equals(user.get("role"));
    }

    /** 角色是否有效：以 perm_roles 表为准（支持自建角色组） */
    public static boolean isValidRole(String role) {
        return role != null && PermDao.getRole(role) != null;
    }

    /** 用户是否拥有某页面路由权限（super_admin 一律放行） */
    public static boolean hasPageRoute(Map<String, Object> user, String path) {
        if (isSuperAdmin(user)) return true;
        if (user == null) return false;
        Object role = user.get("role");
        return role != null && PermDao.roleRouteCodes(role.toString()).contains("page:" + path);
    }

    /**
     * 当前用户可进入的页面路径清单（不含 "page:" 前缀）——「下发的路由」，供 B 端前端做页面级守卫：
     * 无论点菜单还是直接敲 URL，不在本清单内的页面一律跳无权限页。
     *
     * 取值方式：**以 PageRoutes.PAGE_ROUTES 为准遍历、再看角色是否持有对应权限码**，
     * 而不是直接回吐 perm_role_routes 里的 page:* 行——这样数据库里的历史/脏权限码
     * （比如早已下线的页面）不会凭空开通任何路径，清单永远只包含当前代码里真实存在的页面。
     *
     * super_admin：全部页面（与 MenuTree 一致），不受 role_routes 影响；
     * 未登录 / 无 role：空清单（调用方 /api/menu 本身要求登录，此处只作兜底）。
     */
    public static List<String> allowedPagePaths(Map<String, Object> user) {
        List<String> out = new ArrayList<>();
        if (user == null) return out;
        if (isSuperAdmin(user)) {
            for (String[] p : PageRoutes.PAGE_ROUTES) out.add(p[0]);
            return out;
        }
        Object role = user.get("role");
        if (role == null) return out;
        List<String> codes = PermDao.roleRouteCodes(role.toString());
        for (String[] p : PageRoutes.PAGE_ROUTES) {
            if (codes.contains("page:" + p[0])) out.add(p[0]);
        }
        return out;
    }
}
