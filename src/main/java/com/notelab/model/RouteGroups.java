package com.notelab.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「分配路由」弹窗的分组骨架：**页面 ↔ 接口模块**的归属，与**共用模块 ↔ 菜单分组**的归属。
 *
 * <p>为什么从前端搬到后端（2026-10-10）：这张表原本是 notelab-b 的 {@code src/lib/route-groups.ts}，
 * 手工维护、与后端的 {@link ApiModules} 各写一半 —— 模块键写错不会报错，那个模块只是静悄悄落进
 * 弹窗底部的「系统通用」分组，看上去「配过了」其实没生效。归属与模块定义分居两端，
 * 是典型的**静默失配**。现在两者同在后端，启动时还能校验（见 {@link #unknownModules}）。
 *
 * <p>新增 admin 页面的登记口径（一处改完即可）：
 * <ol>
 *   <li>{@link MenuTree}{@code .MENUS} —— 左侧菜单入口</li>
 *   <li>{@link PageRoutes}{@code .PAGE_ROUTES} —— 页面路由与显示名</li>
 *   <li>本类的 {@link #PAGE_MODULES} —— 这个页面用到哪些接口模块（决定「勾页面连带勾哪些接口」）</li>
 * </ol>
 * 第 3 步漏了不会报错：页面节点下没有接口子节点，管理员得自己到底部「系统通用」里找。
 * 所以启动时会把「声明了但没有任何接口在用」的模块键 warn 出来（见 {@link #unknownModules}）。
 *
 * <p>⚠️ 键的口径必须与 {@link ApiModules#keyOf} 的产物严格一致（如 {@code admin/ops/login-audit}、
 * {@code perm/users}、{@code c-admin/invite-codes}）。
 */
public final class RouteGroups {

    private RouteGroups() {}

    /**
     * 单个页面（path 与 {@link PageRoutes#PAGE_ROUTES} 一致）用到的接口模块键。
     *
     * <p>用途：分配路由时把页面与它依赖的接口放在同一个节点下，
     * 避免「勾了页面没勾接口 → 菜单能看、功能全 403」的错配。
     */
    public static final Map<String, List<String>> PAGE_MODULES = Map.ofEntries(
            Map.entry("/chat", List.of("chat", "conversations")),
            Map.entry("/arena", List.of("arena")),
            Map.entry("/rag", List.of("rag")),
            Map.entry("/extract", List.of("extract")),
            Map.entry("/english", List.of("english")),
            Map.entry("/translate", List.of("translate", "admin/translate")),
            Map.entry("/toolbox", List.of("toolbox")),
            Map.entry("/docs", List.of("docs")),
            Map.entry("/canvas", List.of("canvas")),
            Map.entry("/notes", List.of("notes")),
            Map.entry("/tools", List.of("tools")),
            Map.entry("/stress-test", List.of("admin/stress-test")),
            Map.entry("/trpg/gen", List.of("trpg")),
            Map.entry("/practice/dev-summary", List.of("dev-notes")),
            Map.entry("/analytics", List.of("analytics")),
            Map.entry("/perm", List.of("perm")),
            Map.entry("/user/accounts", List.of("perm/users")),
            Map.entry("/user/roles", List.of("perm/roles")),
            Map.entry("/user/invites", List.of("c-admin/invite-codes")),
            Map.entry("/c-users", List.of("c-admin")),
            Map.entry("/ui", List.of("ui-config")),
            Map.entry("/ops", List.of("admin/ops", "arch")),
            Map.entry("/login-audit", List.of("admin/ops/login-audit")),
            Map.entry("/blog-gen", List.of("blog"))
    );

    /**
     * 多个页面共用的接口模块 → 挂在哪个**菜单分组**节点下（分组 key 与 {@link MenuTree} 的 key 一致）。
     *
     * <p>典型场景：爬塔工坊 8 个页面都调 {@code spire-content}，逐个页面挂会重复 8 次；
     * 挂在 {@code gc_spire} 分组上一次即可。
     */
    public static final Map<String, List<String>> SHARED_MODULES = Map.ofEntries(
            Map.entry("gc_spire", List.of("spire-content", "spire-assets")),
            Map.entry("gc_loot", List.of("loot-content", "loot-assets"))
    );

    /** 共用模块 → 它所属的菜单分组（{@link #SHARED_MODULES} 的反查表，启动时构建一次） */
    public static final Map<String, String> MODULE_MENU_GROUP = buildModuleMenuGroup();

    private static Map<String, String> buildModuleMenuGroup() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : SHARED_MODULES.entrySet()) {
            for (String m : e.getValue()) out.putIfAbsent(m, e.getKey());
        }
        return Map.copyOf(out);
    }

    /** 某个页面用到的模块键（未登记的页面返回空表） */
    public static List<String> modulesOfPage(String path) {
        return PAGE_MODULES.getOrDefault(path, List.of());
    }

    /**
     * 不需要归属声明的模块键。
     *
     * <p>这些模块即使没有任何页面声明，落进「系统通用」也是**正确行为**，不该报警：
     * <ul>
     *   <li>{@code base} —— 归不进任何模块的接口（非 {@code /api} 前缀等），本来就该在通用组。</li>
     *   <li>会话基础端点（{@code auth}/{@code login}/{@code me}/{@code menu}/…）——
     *       它们在 {@code PermGuard.SESSION_ENDPOINTS} 里豁免，任何登录用户都能调，
     *       根本不需要授权，自然也不需要归属。</li>
     *   <li>{@code c} 与 {@code c/*} —— C 端接口由 C 端用户组持有（另一套身份体系），
     *       不在 B 端分配树里出现。</li>
     * </ul>
     */
    private static final Set<String> NO_CLAIM_NEEDED = Set.of(
            "base", "auth", "login", "logout", "me", "menu", "register", "health", "error", "c"
    );

    private static boolean claimNeeded(String module) {
        return !(module == null || NO_CLAIM_NEEDED.contains(module) || module.startsWith("c/"));
    }

    /** 归属表里已声明的全部模块键（页面声明 + 分组共用声明） */
    public static Set<String> declaredModules() {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> ms : PAGE_MODULES.values()) out.addAll(ms);
        for (List<String> ms : SHARED_MODULES.values()) out.addAll(ms);
        return out;
    }

    /**
     * 声明了却**没有任何接口在用**的模块键 —— 也就是拼错 / 已下线的键。
     *
     * <p>这是把归属搬到后端的直接收益：前端持有这张表时，写错的模块只会静悄悄落进「系统通用」分组，
     * 管理员以为配过了。现在启动就能把它喊出来。
     *
     * @param liveModules 当前 perm_routes 里真实出现的模块键
     * @return 悬空的模块键（按所属页面归并，便于定位）
     */
    public static List<String> unknownModules(Set<String> liveModules) {
        Set<String> live = liveModules == null ? Set.of() : liveModules;
        List<String> bad = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<String> ms : PAGE_MODULES.values()) {
            for (String m : ms) if (!live.contains(m) && seen.add(m)) bad.add(m);
        }
        for (List<String> ms : SHARED_MODULES.values()) {
            for (String m : ms) if (!live.contains(m) && seen.add(m)) bad.add(m);
        }
        return bad;
    }

    /**
     * 有接口在用、却**没有任何页面或分组声明归属**的模块键 —— 它们在分配树里会落进底部的
     * 「系统通用」分组，管理员得自己翻到底去找，症状是「新接口在树上找不到」。
     *
     * <p>成因通常是新增接口时漏了 {@link #PAGE_MODULES} 那一步登记
     * （新增 admin 页面四步里的第 4 步）。与 {@link #unknownModules} 是**反向**的：
     * 那个查「声明了但没接口用」，这个查「有接口但没人声明」。
     *
     * @param liveModules 当前 perm_routes 里真实出现的模块键
     * @return 无人认领的模块键
     */
    public static List<String> unclaimedModules(Set<String> liveModules) {
        if (liveModules == null || liveModules.isEmpty()) return List.of();
        Set<String> declared = declaredModules();
        List<String> out = new ArrayList<>();
        for (String m : liveModules) {
            if (claimNeeded(m) && !declared.contains(m)) out.add(m);
        }
        out.sort(String::compareTo);
        return out;
    }
}
