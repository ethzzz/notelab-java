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
            loaded = loadFromDb();
        } catch (Exception ignored) {
            // 读失败 / 库中内容损坏 → 回落默认配置。**仅读路径**如此（既有行为，保持）：
            // 读路径拿到默认值顶多是显示不对；写路径若也这样回落，就会拿默认值覆盖真实配置。
        }
        if (loaded == null) {
            loaded = defaultConfig();
        }
        cacheAtMillis = now;
        cacheConfig = loaded;
        return loaded;
    }

    /**
     * 读库中的 ui_config 原文并转成 Map（**不走 30 秒缓存**）。
     *
     * <p>返回 {@code null} = 「表里还没有行」（全新部署）；读失败或内容不是合法 JSON
     * 对象则**抛异常**，由调用方决定怎么处理（读路径回落默认、写路径拒绝写入）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFromDb() {
        String row = UiConfigDao.getUiConfigJson();
        if (row == null || row.isEmpty()) return null;
        JsonNode node;
        try {
            node = JsonUtil.parse(row);
        } catch (Exception e) {
            throw new IllegalStateException("ui_config 内容不是合法 JSON", e);
        }
        if (!node.isObject()) {
            throw new IllegalStateException("ui_config 内容不是 JSON 对象");
        }
        return JsonUtil.MAPPER.convertValue(node, LinkedHashMap.class);
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


    // ---------- 唯一的写入口：读-改-写原子化（消除 §1.1 的并发丢更新）----------

    /**
     * 取库中**最新**内容作为写基底。
     *
     * <p>⚠️ 与读路径的关键差别：这里**不能**在失败时回落 {@link #defaultConfig()}。
     * 那会把「读不到」变成「用默认配置覆盖真实配置」—— 比丢更新更严重的数据事故。
     * 所以读失败直接抛，由 controller 转 500 让调用方重试；只有「表里确实还没有行」
     * （全新部署）才允许以默认配置为基底。
     */
    private static Map<String, Object> loadForWrite() {
        try {
            Map<String, Object> loaded = loadFromDb();
            return loaded != null ? loaded : defaultConfig();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "读取 ui_config 失败，已拒绝本次写入以免覆盖现有配置：" + e.getMessage(), e);
        }
    }

    /** 落库并失效缓存。只在持锁的写方法内调用。 */
    private static void persist(Map<String, Object> cfg) {
        UiConfigDao.saveUiConfig(JsonUtil.write(cfg));
        invalidate();
    }

    /**
     * 原子写入一个顶层键 —— ui_config 的**唯一业务写入口**。
     *
     * <p>此前的写法是每个写点各写一遍
     * {@code new LinkedHashMap<>(getConfig()) → put(键) → saveUiConfig(JSON 整份)}，
     * 而 {@code getConfig()} 带 30 秒缓存：两个管理员同时保存不同模块时，
     * 后写的一方会用「自己读到的旧快照」把前者**整份覆盖，且没有任何报错**。
     * 缓存把冲突窗口从毫秒放大到秒级，让这件事从"几乎不可能"变成"偶尔会发生"。
     *
     * <p>收敛后：{@code synchronized} 进程内串行 + 基底改走 {@link #loadFromDb()}
     * 直读库（不经缓存），「读-改-写」因此是原子的，不会再丢掉别人刚写的键。
     *
     * <p>⚠️ 只解决**单进程内**的并发。将来若起多实例，需要 DB 侧行锁或
     * {@code JSON_SET} 原子更新 —— 那时只换这一个方法的实现，调用方不动。
     *
     * @throws IllegalStateException 读不到现有配置（DB 异常 / 内容损坏）时抛出，调用方应转 500
     */
    public static synchronized void update(String key, Object value) {
        Map<String, Object> cfg = loadForWrite();
        cfg.put(key, value);
        persist(cfg);
    }

    /** 原子删除一个顶层键（下架场景，如删 {@code spire_published}）。 */
    public static synchronized void remove(String key) {
        Map<String, Object> cfg = loadForWrite();
        cfg.remove(key);
        persist(cfg);
    }

    /**
     * 原子写入多个顶层键（**一次**读-改-写）。
     *
     * <p>为什么不连调三次 {@link #update}：中间会有别的写点插进来，且变成写三次库。
     * 需要「一起生效」的一组键必须走这里。
     */
    public static synchronized void updateAll(Map<String, Object> patch) {
        Map<String, Object> cfg = loadForWrite();
        cfg.putAll(patch);
        persist(cfg);
    }
}