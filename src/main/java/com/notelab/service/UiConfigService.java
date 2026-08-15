package com.notelab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.model.MenuTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.notelab.common.JsonUtil;
import com.notelab.dao.UiConfigDao;
import com.notelab.dao.PermDao;

/**
 * 界面配置：多级菜单树 + 背景配置（30s 缓存）。
 * 菜单为树形结构：分组节点含 children（可任意嵌套层级），叶子节点含 path/ready。
 * 名称/图标可被 ui_config.menus 覆盖；RBAC 在叶子级过滤，空分组自动剪掉。
 */
public final class UiConfigService {

    private UiConfigService() {}

    /** DEFAULT_UI_CONFIG：menus 递归收集全部节点（含分组）的默认名称/图标 */
    public static Map<String, Object> defaultConfig() {
        Map<String, Object> background = new LinkedHashMap<>();
        background.put("type", "color");
        background.put("color", "#f6f7f9");
        background.put("image_url", "");
        Map<String, Object> menus = new LinkedHashMap<>();
        collectDefaults(MenuTree.MENUS, menus);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("background", background);
        cfg.put("menus", menus);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static void collectDefaults(List<Map<String, Object>> nodes, Map<String, Object> out) {
        for (Map<String, Object> n : nodes) {
            out.put((String) n.get("key"), menuItem((String) n.get("name"), (String) n.get("icon")));
            if (n.get("children") instanceof List<?> kids) {
                collectDefaults((List<Map<String, Object>>) kids, out);
            }
        }
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
            String row = UiConfigDao.getUiConfigJson();
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

    /**
     * 构建菜单树（带 RBAC）：
     *  - 超级管理员看到全部；
     *  - 其他角色仅保留其路由组内的叶子页面（page:<path>），无可见叶子的分组被剪掉。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> buildMenuItems(Map<String, Object> cfg, Map<String, Object> user) {
        Set<String> allowed = null;
        if (!PermService.isSuperAdmin(user) && user != null) {
            Object role = user.get("role");
            allowed = role == null ? Set.of() : new HashSet<>(PermDao.roleRouteCodes(role.toString()));
        }
        Map<String, Object> menusCfg = cfg.get("menus") instanceof Map
                ? (Map<String, Object>) cfg.get("menus") : Map.of();
        return buildNodes(MenuTree.MENUS, menusCfg, allowed);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildNodes(List<Map<String, Object>> nodes,
                                                        Map<String, Object> menusCfg, Set<String> allowed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            String key = (String) n.get("key");
            Map<String, Object> c = menusCfg.get(key) instanceof Map
                    ? (Map<String, Object>) menusCfg.get(key) : Map.of();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            Object name = c.get("name");
            item.put("name", (name instanceof String s && !s.isEmpty()) ? s : n.get("name"));
            Object icon = c.get("icon");
            item.put("icon", (icon instanceof String s2 && !s2.isEmpty()) ? s2 : n.getOrDefault("icon", ""));
            if (n.get("children") instanceof List<?> kids) {
                List<Map<String, Object>> built = buildNodes((List<Map<String, Object>>) kids, menusCfg, allowed);
                if (built.isEmpty()) continue; // 空分组剪掉
                item.put("children", built);
            } else {
                String path = (String) n.get("path");
                if (allowed != null && !allowed.contains("page:" + path)) continue; // 叶子级权限过滤
                item.put("path", path);
                item.put("ready", n.get("ready"));
            }
            out.add(item);
        }
        return out;
    }
}