package com.notelab.model;

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
                            menu("spire-assets", "素材资源", "🧩", "/spire-editor/assets", true),
                            menu("spire-map", "地图生成", "🗺️", "/spire-editor/map", true),
                            menu("spire-access", "角色授权", "👥", "/spire-editor/access", true))))),
            group("g_tools", "工具箱", "🧰", List.of(
                    menu("english", "英语学习", "🗣️", "/english", true),
                    menu("translate", "翻译句子库", "🌐", "/translate", true),
                    menu("toolbox", "文本工具箱", "🧰", "/toolbox", true),
                    menu("docs", "文档编辑", "📝", "/docs", true),
                    menu("notes", "笔记", "🗒️", "/notes", true),
                    menu("tools", "AI工具库", "🔧", "/tools", true),
                    menu("lowcode", "低代码平台", "🧱", "/lowcode", true),
                    menu("image", "文生图", "🎨", "/image", false),
                    menu("audio", "语音转文字", "🎤", "/audio", false))),
            group("g_users", "用户管理", "👥", List.of(
                    menu("user-accounts", "账户管理", "👤", "/user/accounts", true),
                    menu("user-roles", "角色组管理", "🗂️", "/user/roles", true),
                    menu("user-invites", "邀请码", "🎟️", "/user/invites", true),
                    menu("c-users", "C端用户管理", "🙋", "/c-users", true))),
            group("g_system", "系统管理", "⚙️", List.of(
                    menu("ui", "界面配置", "🎛️", "/ui", true),
                    menu("perm", "权限管理", "🔐", "/perm", true))));

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
}
