package com.notelab;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 界面配置（与 Python 版 DEFAULT_UI_CONFIG / MENUS / get_ui_config / build_menu_items 一致，缓存 30s）。 */
public final class UiConfigService {

    private UiConfigService() {}

    /** 与 Python MENUS 顺序一致 */
    public static final List<Map<String, Object>> MENUS = List.of(
            menu("dashboard", "仪表盘", "/", true),
            menu("chat", "智能对话", "/chat", true),
            menu("arena", "模型竞技场", "/arena", true),
            menu("toolbox", "文本工具箱", "/toolbox", true),
            menu("rag", "文档问答 RAG", "/rag", true),
            menu("english", "英语学习", "/english", true),
            menu("image", "文生图", "/image", false),
            menu("audio", "语音转文字", "/audio", false),
            menu("extract", "结构化抽取", "/extract", true),
            menu("ui", "界面配置", "/ui", true),
            menu("perm", "权限管理", "/perm", true));

    private static Map<String, Object> menu(String key, String name, String path, boolean ready) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("path", path);
        m.put("ready", ready);
        return m;
    }

    /** DEFAULT_UI_CONFIG */
    public static Map<String, Object> defaultConfig() {
        Map<String, Object> background = new LinkedHashMap<>();
        background.put("type", "color");
        background.put("color", "#f6f7f9");
        background.put("image_url", "");
        Map<String, Object> menus = new LinkedHashMap<>();
        menus.put("dashboard", menuItem("仪表盘", "📊"));
        menus.put("chat", menuItem("智能对话", "💬"));
        menus.put("toolbox", menuItem("文本工具箱", "🧰"));
        menus.put("rag", menuItem("文档问答 RAG", "📚"));
        menus.put("english", menuItem("英语学习", "🗣️"));
        menus.put("image", menuItem("文生图", "🎨"));
        menus.put("audio", menuItem("语音转文字", "🎤"));
        menus.put("extract", menuItem("结构化抽取", "🧩"));
        menus.put("ui", menuItem("界面配置", "🎛️"));
        menus.put("perm", menuItem("权限管理", "🔐"));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("background", background);
        cfg.put("menus", menus);
        return cfg;
    }

    private static Map<String, Object> menuItem(String name, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("icon", icon);
        return m;
    }

    // ---------- 30 秒缓存（对应 Python _UI_CACHE） ----------
    private static long cacheAtMillis = 0;
    private static Map<String, Object> cacheConfig = null;

    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Object> getConfig() {
        long now = System.currentTimeMillis();
        if (cacheConfig != null && now - cacheAtMillis < 30_000) {
            return cacheConfig;
        }
        Map<String, Object> loaded = null;
        try {
            String row = Db.getUiConfigJson();
            if (row != null && !row.isEmpty()) {
                JsonNode node = JsonUtil.parse(row);
                if (node.isObject()) {
                    loaded = JsonUtil.MAPPER.convertValue(node, LinkedHashMap.class);
                }
            }
        } catch (Exception e) {
            loaded = null;
        }
        if (loaded == null) {
            loaded = defaultConfig();
        }
        cacheAtMillis = now;
        cacheConfig = loaded;
        return loaded;
    }

    public static synchronized void invalidate() {
        cacheAtMillis = 0;
        cacheConfig = null;
    }

    /** build_menu_items(cfg) */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> buildMenuItems(Map<String, Object> cfg) {
        Map<String, Object> menusCfg = cfg.get("menus") instanceof Map
                ? (Map<String, Object>) cfg.get("menus") : Map.of();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> m : MENUS) {
            Map<String, Object> c = menusCfg.get(m.get("key")) instanceof Map
                    ? (Map<String, Object>) menusCfg.get(m.get("key")) : Map.of();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", m.get("key"));
            item.put("path", m.get("path"));
            item.put("ready", m.get("ready"));
            Object name = c.get("name");
            item.put("name", (name instanceof String s && !s.isEmpty()) ? s : m.get("name"));
            item.put("icon", c.getOrDefault("icon", ""));
            items.add(item);
        }
        return items;
    }
}
