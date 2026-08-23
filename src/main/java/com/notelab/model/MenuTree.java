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
            group("g_games", "游戏中心", "🎮", List.of(
                    group("trpg", "剧本跑团", "🎲", List.of(
                            menu("trpg-play", "玩剧本", "🎮", "/trpg/play", true),
                            menu("trpg-gen", "生成剧本", "📜", "/trpg/gen", true))),
                    menu("vs", "吸血鬼幸存者", "🧛", "/vs", true),
                    menu("spire", "爬塔尖塔", "🗼", "/spire", true))),
            group("g_gamecfg", "游戏配置", "🎛️", List.of(
                    menu("spire-editor", "爬塔尖塔", "🗼", "/spire-editor", true))),
            group("g_tools", "工具箱", "🧰", List.of(
                    menu("english", "英语学习", "🗣️", "/english", true),
                    menu("toolbox", "文本工具箱", "🧰", "/toolbox", true),
                    menu("tools", "AI工具库", "🔧", "/tools", true),
                    menu("lowcode", "低代码平台", "🧱", "/lowcode", true),
                    menu("image", "文生图", "🎨", "/image", false),
                    menu("audio", "语音转文字", "🎤", "/audio", false))),
            group("g_users", "用户管理", "👥", List.of(
                    menu("user-accounts", "账户管理", "👤", "/user/accounts", true),
                    menu("user-roles", "角色组管理", "🗂️", "/user/roles", true))),
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
