package com.notelab;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * RBAC 权限服务。
 *
 * 权限码规则：
 *  - 页面路由  code = "page:<path>"（如 page:/chat），驱动菜单可见性
 *  - API 路由  code = "api:<path>"（如 api:/api/chat），启动时从 SpringMVC 请求映射自动采集
 *
 * 自动注册：每次启动时把当前所有 Controller 路由 + 前端页面路由 upsert 进 perm_routes 表，
 * 以后新增路由无需手工登记，重启即自动出现在权限路由表里。
 *
 * 角色：super_admin（超级管理员，天然拥有全部路由，含未来新增）/ user（普通用户，按 perm_role_routes 分配）。
 */
public final class PermService {

    public static final String ROLE_ADMIN = "super_admin";
    public static final String ROLE_USER = "user";

    /** 前端页面路由（菜单驱动）：path, 名称。image/audio 为"敬请期待"占位页，同样纳入权限体系。 */
    public static final List<String[]> PAGE_ROUTES = List.of(
            new String[]{"/", "仪表盘"},
            new String[]{"/chat", "智能对话"},
            new String[]{"/arena", "模型竞技场"},
            new String[]{"/toolbox", "文本工具箱"},
            new String[]{"/rag", "文档问答 RAG"},
            new String[]{"/english", "英语学习"},
            new String[]{"/image", "文生图"},
            new String[]{"/audio", "语音转文字"},
            new String[]{"/extract", "结构化抽取"},
            new String[]{"/ui", "界面配置"},
            new String[]{"/perm", "权限管理"}
    );

    private PermService() {}

    /** Bootstrap 在 Db.init() 之后调用。 */
    public static void registerAllRoutes(RequestMappingHandlerMapping mapping) {
        // 1) 页面路由
        for (String[] p : PAGE_ROUTES) {
            Db.upsertRoute("page:" + p[0], p[0], "", "page", p[1]);
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
            Db.upsertRoute("api:" + e.getKey(), e.getKey(), String.join("|", e.getValue()),
                    "api", nameByPath.getOrDefault(e.getKey(), ""));
        }
        // 3) 普通角色默认权限（仅首次为空时写入，不覆盖已有配置）：全部功能页，不含 ui/perm 管理页
        if (Db.roleRouteCodes(ROLE_USER).isEmpty()) {
            List<String> defaults = new ArrayList<>();
            for (String[] p : PAGE_ROUTES) {
                if (!p[0].equals("/ui") && !p[0].equals("/perm")) defaults.add("page:" + p[0]);
            }
            Db.setRoleRoutes(ROLE_USER, defaults);
        }
        // 4) 首次启动若尚无超级管理员：把最早注册的用户提升为超级管理员（避免无人可管理）
        if (Db.countSuperAdmins() == 0) {
            Db.promoteFirstUserToAdmin();
        }
    }

    public static boolean isSuperAdmin(Map<String, Object> user) {
        return user != null && ROLE_ADMIN.equals(user.get("role"));
    }

    public static boolean isValidRole(String role) {
        return ROLE_ADMIN.equals(role) || ROLE_USER.equals(role);
    }

    /** 用户是否拥有某页面路由权限（super_admin 一律放行） */
    public static boolean hasPageRoute(Map<String, Object> user, String path) {
        if (isSuperAdmin(user)) return true;
        if (user == null) return false;
        Object role = user.get("role");
        return role != null && Db.roleRouteCodes(role.toString()).contains("page:" + path);
    }
}
