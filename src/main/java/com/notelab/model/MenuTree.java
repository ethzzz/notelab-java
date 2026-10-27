package com.notelab.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单树常量（数据结构层）：图标为内置默认值，ui_config.menus 可覆盖名称与图标。
 * 树形结构：分组节点含 children（可任意嵌套层级），叶子节点含 path/ready。
 */
public final class MenuTree {

    private MenuTree() {}

    public static final List<Map<String, Object>> MENUS = List.of(
            menu("dashboard", "仪表盘", "📊", "/", true),
            group("g_ai", "AI对话", "💬", List.of(
                    menu("chat", "智能对话", "💬", "/chat", true),
                    menu("arena", "模型竞技场", "⚔️", "/arena", true),
                    menu("rag", "文档问答 RAG", "📚", "/rag", true),
                    menu("extract", "结构化抽取", "🧩", "/extract", true))),
            // B/C 拆分 P6：游玩（玩剧本/吸血鬼幸存者/爬塔）已移至 C 端，B 端仅保留配置/生成能力
            // 剧本玩法：生成剧本从原“游戏中心”移入“游戏配置 → 剧本玩法”子菜单（游戏中心组因此为空被剪掉）
            // 爬塔尖塔：原为单个叶子（页内 4 个 Tab 挤在一起），按功能拆成 6 个子页，
            // 各子页路由必须同时登记在 PageRoutes.PAGE_ROUTES，否则普通角色看不到（叶子被 RBAC 过滤）。
            group("g_gamecfg", "游戏配置", "🎛️", List.of(
                    group("gc_trpg", "剧本玩法", "🎲", List.of(
                            menu("trpg-gen", "生成剧本", "📜", "/trpg/gen", true))),
                    group("gc_spire", "爬塔尖塔", "🗼", List.of(
                            menu("spire-cards", "卡片制作", "🎴", "/spire-editor/cards", true),
                            menu("spire-chars", "角色制作", "🧙", "/spire-editor/chars", true),
                            menu("spire-skills", "技能制作", "⚡", "/spire-editor/skills", true),
                            menu("spire-enemies", "敌人制作", "👾", "/spire-editor/enemies", true),
                            menu("spire-balance", "难度配置", "⚖️", "/spire-editor/balance", true),
                            menu("spire-assets", "素材资源", "🧩", "/spire-editor/assets", true),
                            menu("spire-map", "地图生成", "🗺️", "/spire-editor/map", true),
                            menu("spire-access", "角色授权", "👥", "/spire-editor/access", true))),
                    // 摸金行动（Loot Raid）：搜刮撤离类 H5，内容全部后台配置（见 LootContentController）。
                    // 与 gc_spire 同构：6 个子页，路由同样登记在 PageRoutes.PAGE_ROUTES（漏登记会被 RBAC 过滤）。
                    // ⚠️ rarities 放在**第一位**：它是其余几页的字典（档位/颜色/每格基准价都由它定义），
                    //    先建档位再配物品才顺手。
                    group("gc_loot", "摸金行动", "🪙", List.of(
                            menu("loot-rarities", "稀有度配置", "🌈", "/loot-editor/rarities", true),
                            menu("loot-items", "物品配置", "💎", "/loot-editor/items", true),
                            menu("loot-containers", "容器配置", "📦", "/loot-editor/containers", true),
                            menu("loot-tables", "掉落表", "🎰", "/loot-editor/tables", true),
                            menu("loot-maps", "地图配置", "🗺️", "/loot-editor/maps", true),
                            menu("loot-balance", "全局参数", "⚖️", "/loot-editor/balance", true))),
                    // 注意：菜单 path 不含 /admin 前缀（basePath 由前端 Next 自己拼），
                    // 与 PageRoutes.PAGE_ROUTES 保持同一口径，否则守卫匹配不上。
                    menu("game-access", "游戏登录管理", "🔑", "/game-access", true))),
            group("g_tools", "工具箱", "🧰", List.of(
                    menu("english", "英语学习", "🗣️", "/english", true),
                    menu("translate", "翻译句子库", "🌐", "/translate", true),
                    menu("toolbox", "文本工具箱", "🧰", "/toolbox", true),
                    // 协作画布：tldraw 白板（画布 + 富文本卡片），多人实时协作；
                    // 元数据存 canvas_doc（见 CanvasController），内容由协作服务 :3030 的 SQLite 持有。
                    menu("canvas", "协作画布", "🖌️", "/canvas", true),
                    menu("tools", "AI工具库", "🔧", "/tools", true),
                    menu("stress-test", "接口压测", "🔥", "/stress-test", true),
                    menu("lowcode", "低代码平台", "🧱", "/lowcode", true),
                    menu("image", "文生图", "🎨", "/image", false),
                    menu("audio", "语音转文字", "🎤", "/audio", false))),
            // 文档管理（2026-10-18）：文档编辑 / 笔记从「工具箱」移出，与新增的 PRD 管理并成一组。
            // 三类内容同属「写作与存档」，放在工具箱里和压测、低代码混在一起没有共同点。
            // ⚠️ 菜单 key 保持 docs / notes 不动（/ui 里的名称覆盖按键存），**页面 path 变了**：
            //    /docs → /documents/editor、/notes → /documents/notes ⇒ 权限码 page:<path> 随之变化，
            //    非超管角色需在「角色组管理 → 分配路由」里重新勾选这三页（旧 page:/docs、page:/notes 成为死码）。
            //    旧地址由 notelab-b/next.config.ts 的 redirects 307 兜住，书签不会 404。
            group("g_docs", "文档管理", "📚", List.of(
                    menu("docs", "文档编辑", "📝", "/documents/editor", true),
                    menu("notes", "笔记", "🗒️", "/documents/notes", true),
                    // PRD 管理：与笔记同一套实现（同表 notes.doc_type、同 Markdown 编辑器、同导出），
                    // 只在「看哪一类文档」上分开。见 NoteController#type 与 com.notelab.model.NoteType。
                    menu("prd", "PRD 管理", "📐", "/documents/prd", true))),
            // 项目实践：把做项目时踩到的技术难点沉淀成结构化记录（现象 / 原因 / 解决方案 / 代码）。
            // 内容存 ui_config.dev_notes（见 DevNoteController），不是 C 端消费的配置，编辑即生效、无发布快照。
            group("g_practice", "项目实践", "🧪", List.of(
                    menu("dev-summary", "项目开发总结", "📓", "/practice/dev-summary", true))),
            // 编程学习：语言 → 阶段 → 课时 → 随堂测试，每节课有讲解 + 可运行示例 + 选择题。
            // 内容存 ui_config.learning（见 LearningContentController），后台知识库、编辑即生效、无发布快照。
            // 每个语言带 runner 标签说明「能不能在浏览器里真跑、走什么方案」（能力表只读，见 LearningSeed.RUNNERS）。
            // ⚠️ 路由 /learning 必须同步登记在 PageRoutes.PAGE_ROUTES，漏了叶子会被 RBAC 过滤 → 谁都看不到。
            //
            // 学习路线文档（2026-10-18）：同一分组下再挂三份**长文档**，与上面的「课程」分工不同 ——
            //   课程 = 短课时 + 随堂测试 + 学习进度（内容在 ui_config.learning，可在线编辑）；
            //   文档 = 可对照开发的 Markdown 全文（在 notelab-b 的 src/content/study/*.md，随 git 走）。
            // 两者不合并的原因：一个要能在线改、一个要能 diff/review；混在一起会互相打架。
            // ⚠️ 三条新 path 同样必须登记 PageRoutes，漏了普通角色看不到。
            group("g_learn", "编程学习", "🎓", List.of(
                    menu("learn-tracks", "学习路线", "🎓", "/learning", true),
                    menu("learn-java", "Java 路线", "☕", "/study/java", true),
                    menu("learn-go", "Go 路线", "🐹", "/study/go", true),
                    menu("learn-react", "React 路线", "⚛️", "/study/react", true),
                    // Next.js 路线（2026-10-20）：面向高级/资深前端，内容在 notelab-b/src/content/study/nextjs.md。
                    // ⚠️ 同样必须同步登记 PageRoutes。
                    menu("learn-nextjs", "Next.js 路线", "▲", "/study/nextjs", true),
                    // Agent 前端路线（2026-10-27）：面向 AI Agent 产品前端岗位，6 周冲刺、目标 P6；
                    // 内容在 notelab-b/src/content/study/agent.md，配套实验台在 .agent/tmp/agent-frontend-lab。
                    // ⚠️ 同样必须同步登记 PageRoutes（漏了普通角色看不到）。
                    menu("learn-agent", "Agent 路线", "🤖", "/study/agent", true),
                    // 实践项目库（2026-10-19）：按企业业务方向组织的项目文档，内容在
                    // notelab-b/src/content/study/projects/<route>/*.md —— 加项目只加文件，不改路由。
                    // ⚠️ 路由必须同步登记 PageRoutes，否则叶子被 RBAC 过滤 → 谁都看不到。
                    menu("learn-projects", "实践项目", "🧪", "/study/projects", true))),
            group("g_users", "用户管理", "👥", List.of(
                    menu("user-accounts", "账户管理", "👤", "/user/accounts", true),
                    menu("user-roles", "角色组管理", "🗂️", "/user/roles", true),
                    menu("user-invites", "邀请码", "🎟️", "/user/invites", true),
                    menu("c-users", "C端用户管理", "🙋", "/c-users", true))),
            group("g_system", "系统管理", "⚙️", List.of(
                    menu("analytics", "数据看板", "📈", "/analytics", true),
                    menu("ui", "界面配置", "🎛️", "/ui", true),
                    menu("perm", "权限管理", "🔐", "/perm", true),
                    // 运维看板：只读展示进程/端口/git/资源 + 发布自检，仅超管（见 PermService 默认排除）
                    menu("ops", "运维看板", "🩺", "/ops", true),
                    // 登录审计：每一次登录尝试（含失败）的留痕，用于事后回溯「有没有人在撞库 / 某个洞有没有被利用」。
                    // 接口在受限前缀 /api/admin/ops/login-audit，同样仅超管；路由与 PageRoutes 一一对应。
                    menu("login-audit", "登录审计", "🛡️", "/login-audit", true),
                    // 模型用量（2026-10-20 接 DeepSeek 时加）：大模型调用的 token/请求账本 + 每日限额。
                    // 接口在受限前缀 /api/admin/llm-usage → 仅超管；本页同时登记 PageRoutes 与 PermGuard.SUPER_ONLY_PAGES。
                    menu("llm-usage", "模型用量", "🔋", "/llm-usage", true),
                    // 模型与平台：看平台（key 只显示掩码）、探测候选、改别名与可选清单（2026-10-20 加）
                    menu("llm-platforms", "模型与平台", "🧩", "/llm-platforms", true))),
            // 博客管理：B 端写入「十年前端笔记」博客（GitHub: ethzzz/blog）。
            group("g_blog", "博客管理", "📖", List.of(
                    // 文章起草：手动输入 / AI 起草**路线文章**（posts）后提交到博客仓库（BlogContentController）。
                    // 从「文章生成」改名过来 —— 原来与「博客文章生成」太像，而两者产物完全不同。
                    menu("blog-gen", "文章起草", "✍️", "/blog-gen", true),
                    // 文章记录：每篇博客文章的浏览数与改动记录（浏览数由博客侧上报，见 BlogStatController）。
                    menu("blog-articles", "文章记录", "📊", "/blog-articles", true),
                    // 部署状态：博客是静态站，写进仓库后要构建一次才上线 —— 这一页看进度/结果/失败原因
                    // （后台触发的服务器本地构建，见 BlogDeployService；单开一页是用户 2026-10-16 定的）。
                    menu("blog-deploy", "部署状态", "🚀", "/blog-deploy", true))),
            // 社区管理（PRD-P9 M2，2026-10-23）：C 端 /community 的内容供给主要来自**管理员**而不是用户
            // （C 端只有 5 个账号），所以「后台能写主题 / 管板块 / 把博客导成讨论区」比用户侧互动更早需要。
            // ⚠️ 路由 /community-admin 必须同步登记 PageRoutes，否则叶子被 RBAC 过滤 → 谁都看不到。
            group("g_community", "社区管理", "💬", List.of(
                    menu("community-admin", "社区运营", "💬", "/community-admin", true))),
            // 练习解答：一条记录 = 一道练习题解，配好发布信息后出现在博客的**解答页**
            // （内容存 MySQL、发布即生效、不写博客仓，见 PracticeController）。
            // ⚠️ 2026-10-16 从「博客管理」挪出来单独成组：原来叫「博客文章生成」、与「文章起草」并排，
            //    名字和位置都让人以为两者重复 —— 实际产物（题解 vs 文章）、真相源（MySQL vs git 仓库）、
            //    发布方式（运行时读接口 秒级 vs 构建 约 1 分钟）全都不一样。
            //    这次只改了**显示名与菜单归属**：菜单 key 与页面 path（/practice）都没动，
            //    所以历史角色授权（page:/practice）与 /practice?id=N 的二级页书签都不受影响。
            group("g_solutions", "练习解答", "🏋️", List.of(
                    menu("practice", "解答记录", "📝", "/practice", true))));

    private static Map<String, Object> menu(String key, String name, String icon, String path, boolean ready) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("icon", icon);
        m.put("path", path);
        m.put("ready", ready);
        return m;
    }

    private static Map<String, Object> group(String key, String name, String icon, List<Map<String, Object>> children) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("icon", icon);
        m.put("children", children);
        return m;
    }

    /**
     * 全部**叶子节点**的 path（按树序、去重）。
     *
     * <p>用途有二：① 与 {@link PageRoutes#PAGE_ROUTES} 做一致性校验（两份常量必须一一对应，
     * 失配是静默的）；② 「分配路由」页面树的分组骨架。
     */
    public static List<String> leafPaths() {
        List<String> out = new ArrayList<>();
        collectLeafPaths(MENUS, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectLeafPaths(List<Map<String, Object>> nodes, List<String> out) {
        for (Map<String, Object> n : nodes) {
            Object kids = n.get("children");
            if (kids instanceof List<?> ks && !ks.isEmpty()) {
                collectLeafPaths((List<Map<String, Object>>) ks, out);
            } else {
                Object p = n.get("path");
                if (p instanceof String s && !s.isEmpty() && !out.contains(s)) out.add(s);
            }
        }
    }
}
