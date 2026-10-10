package com.notelab.model;

import java.util.Map;

/**
 * API 路由的「模块」——把接口按语义分组并给中文展示名，供「角色组管理 → 分配路由」的 API 树使用。
 *
 * <p>为什么放后端而不是前端：这份映射必须跟着**路由**走。放在前端就会出现「后端加了模块、
 * 前端忘了加」的静默失配（此前硬编码在 notelab-b 的 roles/page.tsx 里只有 6 条，
 * 实测 125 条路由里 71 条（57%）只能显示英文路径段）。由后端下发后，
 * 前端不再持有任何模块名/模块键逻辑，新增模块只改这一处。
 *
 * <p><b>模块键的粒度</b>（{@link #keyOf}）：不是简单取 {@code /api} 之后第一段，而是
 * 按「哪个页面在用它」细分 —— {@code /api/admin/**} 下压测 / 翻译管理 / 运维 / 登录审计
 * 分属不同页面，混成一个「运营与运维」会让授权时分不清。键必须与前端
 * 「分配路由」的分组骨架严格一致，否则树会分错组。
 *
 * <p>⚠️ 刻意**只做字符串推导、不查库**：它要能在无数据库依赖的场景（如启动期）也能用。
 */
public final class ApiModules {

    private ApiModules() {}

    /** 归不进任何模块时的键（非 /api 前缀、或 /api 下第一段为空） */
    public static final String BASE = "base";

    /**
     * 模块键 → 展示名。键由 {@link #keyOf} 产出。
     * 未登记的键退化为原样显示键名，不会报错 —— 新增模块忘了登记只是少个中文名，不会崩。
     */
    public static final Map<String, String> LABELS = Map.ofEntries(
            // ---- 系统与运维 ----
            Map.entry("admin", "运营与运维"),
            Map.entry("admin/ops", "运维状态接口"),
            Map.entry("admin/ops/login-audit", "登录审计接口"),
            Map.entry("admin/stress-test", "压测接口"),
            Map.entry("admin/translate", "翻译管理接口"),
            Map.entry("perm", "权限总览接口"),
            Map.entry("perm/users", "账户接口"),
            Map.entry("perm/roles", "角色组接口"),
            Map.entry("c-admin", "C端用户接口"),
            Map.entry("c-admin/invite-codes", "邀请码接口"),
            Map.entry("ui-config", "界面配置接口"),
            Map.entry("analytics", "埋点分析接口"),
            Map.entry("arch", "架构守护接口"),
            Map.entry("blog", "博客接口"),
            // ---- 会话基础（它们都是 PermGuard.SESSION_ENDPOINTS，登记时会被跳过，留着以防万一）----
            Map.entry("auth", "认证"),
            Map.entry("login", "登录"),
            Map.entry("logout", "登出"),
            Map.entry("me", "会话信息"),
            Map.entry("menu", "菜单"),
            Map.entry("register", "注册"),
            Map.entry("health", "健康检查"),
            Map.entry("error", "错误页"),
            // ---- AI 能力 ----
            Map.entry("chat", "智能对话接口"),
            Map.entry("conversations", "会话记录接口"),
            Map.entry("arena", "竞技场接口"),
            Map.entry("rag", "RAG 检索接口"),
            Map.entry("extract", "结构化抽取接口"),
            Map.entry("models", "模型列表"),
            Map.entry("tools", "工具库接口"),
            Map.entry("toolbox", "文本工具箱接口"),
            Map.entry("english", "英语学习接口"),
            Map.entry("translate", "翻译接口"),
            // ---- 内容与游戏配置 ----
            Map.entry("docs", "文档接口"),
            Map.entry("notes", "笔记接口"),
            Map.entry("canvas", "画布接口"),
            Map.entry("dev-notes", "开发笔记接口"),
            Map.entry("trpg", "TRPG 接口"),
            Map.entry("spire-content", "爬塔内容接口"),
            Map.entry("spire-assets", "爬塔素材接口"),
            Map.entry("loot-content", "摸金内容接口"),
            Map.entry("loot-assets", "摸金素材接口"),
            Map.entry("lowcode", "低代码平台"),
            // ---- C 端（/api/c/**，由 C 端用户组持有，见 CPermGuard）----
            Map.entry("c", "C端通用接口"),
            Map.entry("c/auth", "C端登录注册"),
            Map.entry("c/game", "C端游戏存档"),
            Map.entry("c/dungeon", "C端地牢存档"),
            Map.entry("c/trpg", "C端剧本"),
            Map.entry("c/track", "C端埋点"),
            Map.entry("c/config", "C端站点配置"),
            Map.entry("c/spire", "C端爬塔内容"),
            Map.entry("c/loot", "C端摸金内容")
    );

    /**
     * 由接口路径推导模块键。规则与「这个接口属于哪个页面」对齐：
     * <ul>
     *   <li>{@code /api/x/**} → {@code x}（两段也算 —— 修掉旧版「{@code /api/notes} 落 base、
     *       {@code /api/notes/{id}} 落 notes」的分裂）</li>
     *   <li>{@code /api/admin/x/**} → {@code admin/x}；其中 {@code ops} 再按 {@code login-audit} 细分</li>
     *   <li>{@code /api/perm/{users,roles}/**} 拆开（分属账户管理 / 角色组管理页），其余归 {@code perm}</li>
     *   <li>{@code /api/c-admin/invite-codes} 拆给邀请码页，其余归 {@code c-admin}</li>
 *   <li>{@code /api/c/x/**} → {@code c/x}（C 端接口，给「C端用户组 → 分配路由」用）</li>
     *   <li>非 {@code /api} 前缀（如 Spring 的 {@code /error}）→ 首段；解析不出 → {@link #BASE}</li>
     * </ul>
     */
    public static String keyOf(String apiPath) {
        if (apiPath == null || apiPath.isEmpty()) return BASE;
        String p = apiPath.startsWith("/") ? apiPath.substring(1) : apiPath;
        String[] s = p.split("/");
        if (s.length == 0 || s[0].isEmpty()) return BASE;
        if (!"api".equals(s[0])) return s[0];
        if (s.length < 2 || s[1].isEmpty()) return BASE;
        String head = s[1];
        String sub = s.length >= 3 ? s[2] : "";
        if ("admin".equals(head)) {
            if (sub.isEmpty()) return "admin";
            if ("ops".equals(sub)) return "login-audit".equals(s.length >= 4 ? s[3] : "") ? "admin/ops/login-audit" : "admin/ops";
            return "admin/" + sub;
        }
        if ("perm".equals(head)) {
            if ("users".equals(sub)) return "perm/users";
            if ("roles".equals(sub)) return "perm/roles";
            return "perm";
        }
        if ("c-admin".equals(head)) {
            return "invite-codes".equals(sub) ? "c-admin/invite-codes" : "c-admin";
        }
        // C 端接口 /api/c/<x>/**：按第二段细分（auth/game/trpg/track/config...）。
        // ⚠️ 注意别把 /api/c-admin 吃进来：它上面的 head 是 "c-admin"（按 "/" 切分，
        // "c-admin" 是完整的一段），两个分支互不干扰。
        if ("c".equals(head)) return sub.isEmpty() ? "c" : "c/" + sub;
        return head;
    }

    /** 模块键 → 展示名；未登记退化为键名本身，{@link #BASE} 显示为「系统基础」 */
    public static String labelOf(String key) {
        if (key == null || key.isEmpty() || BASE.equals(key)) return "系统基础";
        return LABELS.getOrDefault(key, key);
    }

    /** 路径 → 模块键 */
    public static String moduleOf(String apiPath) {
        return keyOf(apiPath);
    }

    /** 路径 → 展示名 */
    public static String label(String apiPath) {
        return labelOf(keyOf(apiPath));
    }
}
