package com.notelab.model;

import java.util.List;

/**
 * 前端页面路由表常量（数据结构层，菜单驱动）：path, 名称。
 * image/audio 为"敬请期待"占位页，同样纳入权限体系。
 */
public final class PageRoutes {

    private PageRoutes() {}

    public static final List<String[]> PAGE_ROUTES = List.of(
            new String[]{"/", "仪表盘"},
            new String[]{"/chat", "智能对话"},
            new String[]{"/arena", "模型竞技场"},
            new String[]{"/toolbox", "文本工具箱"},
            new String[]{"/rag", "文档问答 RAG"},
            new String[]{"/english", "英语学习"},
            new String[]{"/translate", "翻译句子库"},
            new String[]{"/image", "文生图"},
            new String[]{"/audio", "语音转文字"},
            new String[]{"/extract", "结构化抽取"},
            new String[]{"/tools", "AI工具库"},
            // 数据看板（PRD-P0 §6.2）：接口 /api/analytics/** 属受限前缀（仅超管）
            new String[]{"/analytics", "数据看板"},
            new String[]{"/stress-test", "接口压测"},
            new String[]{"/lowcode", "低代码平台"},
            // B/C 拆分 P6：/trpg/play、/vs、/spire 游玩页已移至 C 端，从 B 端页面路由表移除
            new String[]{"/trpg/gen", "生成剧本"},
            // 文档管理（2026-10-18，与 MenuTree.g_docs 一一对应）：文档编辑 / 笔记 / PRD 管理。
            // ⚠️ 路径由 /docs、/notes 迁到 /documents/* ⇒ 权限码 page:<path> 随之变化，
            //    非超管角色要在「角色组管理 → 分配路由」里重新勾选这三页（旧 page:/docs、page:/notes 成为死码）。
            //    旧地址由 notelab-b/next.config.ts 的 redirects 307 兜住，书签不会 404。
            //    守卫的 pages.includes(pathname) 是**精确匹配**，三页必须逐条登记。
            new String[]{"/documents/editor", "文档管理·文档编辑"},
            new String[]{"/documents/notes", "文档管理·笔记"},
            new String[]{"/documents/prd", "文档管理·PRD 管理"},
            // 协作画布（与 MenuTree.g_tools 的 canvas 一一对应；漏这里叶子会被 RBAC 过滤看不到）。
            // ⚠️ 编辑页用 /canvas?room=<roomId> 单页承载（守卫 pages.includes(pathname) 是**精确匹配**，
            //    子路由 /canvas/xxx 不在表里会被判无权限），故本表只需登记 /canvas 一条。
            new String[]{"/canvas", "协作画布"},
            // 爬塔工坊由单页 4 个 Tab 拆成 6 个子页（原 /spire-editor 现为 307 重定向，见 notelab-b/next.config.ts）。
            // ⚠️ 这里与 MenuTree.gc_spire 必须一一对应：只加菜单不加路由 → 叶子被 RBAC 过滤，谁都看不到；
            //    只加路由不加菜单 → 没有入口。历史 role 里的 page:/spire-editor 行会自然失效（不在本表 → 不再开通任何路径），
            //    非超管角色需要在「角色组管理」里重新勾选下面这 8 条。
            new String[]{"/spire-editor/cards", "爬塔·卡片制作"},
            new String[]{"/spire-editor/chars", "爬塔·角色制作"},
            new String[]{"/spire-editor/skills", "爬塔·技能制作"},
            new String[]{"/spire-editor/enemies", "爬塔·敌人制作"},
            new String[]{"/spire-editor/balance", "爬塔·难度配置"},
            new String[]{"/spire-editor/assets", "爬塔·素材资源"},
            new String[]{"/spire-editor/map", "爬塔·地图生成"},
            new String[]{"/spire-editor/access", "爬塔·角色授权"},
            // 摸金行动（Loot Raid）：与 MenuTree.gc_loot 一一对应；只加菜单不加这里 → 叶子被 RBAC 过滤看不到。
            // 非超管角色需在「角色组管理」里重新勾选下面这 6 条。
            new String[]{"/loot-editor/rarities", "摸金·稀有度配置"},
            new String[]{"/loot-editor/items", "摸金·物品配置"},
            new String[]{"/loot-editor/containers", "摸金·容器配置"},
            new String[]{"/loot-editor/tables", "摸金·掉落表"},
            new String[]{"/loot-editor/maps", "摸金·地图配置"},
            new String[]{"/loot-editor/balance", "摸金·全局参数"},
            // 项目实践 → 项目开发总结（与 MenuTree.g_practice 一一对应；漏这里叶子会被 RBAC 过滤）
            new String[]{"/practice/dev-summary", "项目实践·开发总结"},
            // 练习解答（原「博客文章生成」/「练习实践」，2026-10-16 改名并挪进自己的菜单分组
            // g_solutions —— 与 MenuTree 的 practice 一一对应；漏这里叶子会被 RBAC 过滤）。
            // ⚠️ 路径仍是 /practice —— 只改显示名与菜单归属，不动 URL（旧书签与 /practice?id=N 的二级页都还认），
            //    权限码 page:/practice 因此不变，历史角色授权不会失效。
            // ⚠️ 二级页面走 /practice?id=<记录id>&mode=view|edit —— 守卫的 pages.includes(pathname) 是
            //    **精确匹配**，子路由 /practice/xxx 不在表里会被判无权限（协作画布 / 编程学习踩过同一个坑）。
            new String[]{"/practice", "练习解答·解答记录"},
            // 编程学习（与 MenuTree.g_learn 一一对应；漏这里 → 叶子被 RBAC 过滤看不到）。
            // 详情页用 /learning?track=<code> 单页承载 —— 守卫的 pages.includes(pathname) 是**精确匹配**，
            // 子路由 /learning/xxx 不在表里会被判无权限（协作画布踩过同一个坑）。
            new String[]{"/learning", "编程学习"},
            // 学习路线文档（2026-10-18，与 MenuTree.g_learn 的三条新叶子一一对应）：
            //   文档正文是 notelab-b 的 src/content/study/*.md，随 git 走；这里只登记页面路由。
            // ⚠️ 漏登记 → 叶子被 RBAC 过滤，菜单里根本看不到（不是 404，是"没有这一项"）。
            new String[]{"/study/java", "编程学习·Java 路线"},
            new String[]{"/study/go", "编程学习·Go 路线"},
            new String[]{"/study/react", "编程学习·React 路线"},
            new String[]{"/study/nextjs", "编程学习·Next.js 路线"},
            // 实践项目库（2026-10-19）：列表页固定在 /study/projects，详情走 ?p=<route>/<slug>。
            // 用查询参数而不是 /study/projects/xxx，理由同上面几条：守卫是精确匹配。
            new String[]{"/study/projects", "编程学习·实践项目"},
            new String[]{"/user/accounts", "账户管理"},
            new String[]{"/user/roles", "角色组管理"},
            new String[]{"/user/invites", "邀请码"},
            new String[]{"/c-users", "C端用户管理"},
            new String[]{"/ui", "界面配置"},
            new String[]{"/perm", "权限管理"},
            // 运维看板（超管专属：接口在受限前缀 /api/admin/ops，且默认角色不含本页，见 PermService）
            new String[]{"/ops", "运维看板"},
            // 登录审计（超管专属：接口在受限前缀 /api/admin/ops/login-audit）。
            // ⚠️ 与 MenuTree.g_system 的 login-audit 一一对应：只加菜单不加这里 → 叶子被 RBAC 过滤看不到。
            new String[]{"/login-audit", "登录审计"},
            // 模型用量（超管专属：接口在受限前缀 /api/admin/llm-usage）。
            // ⚠️ 与 MenuTree.g_system 的 llm-usage 一一对应，且要进 PermGuard.SUPER_ONLY_PAGES。
            new String[]{"/llm-usage", "系统·模型用量"},
            // 模型与平台（超管专属：接口在受限前缀 /api/admin/llm-platforms）
            new String[]{"/llm-platforms", "系统·模型与平台"},
            // 博客管理 → 文章起草（B 端写入 ethzzz/blog 仓库的路线文章 posts，见 BlogContentController）
            new String[]{"/blog-gen", "博客·文章起草"},
            // 博客管理 → 文章记录（每篇 posts 的浏览数与改动记录；浏览数由博客侧匿名上报）
            new String[]{"/blog-articles", "博客·文章记录"},
            // 博客管理 → 部署状态（后台触发的服务器本地构建：进度、结果、失败日志尾部）
            new String[]{"/blog-deploy", "博客·部署状态"},
            // 路径不含 /admin 前缀：PAGE_ROUTES 与前端 router.pathname 比对，
            // 而 B 端 basePath（/admin）由 Next 自己拼，写进去会永远匹配不上 → 页面被守卫拦掉
            new String[]{"/game-access", "游戏登录管理"}
    );
}
