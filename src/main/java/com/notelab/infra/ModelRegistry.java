package com.notelab.infra;

import com.notelab.common.AppConfig;
import com.notelab.service.UiConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 模型注册表：**别名 → (平台, 厂商模型名)**，以及每个平台允许的模型清单（allowlist）。
 *
 * <h3>为什么需要"别名"这一层</h3>
 * 代码里的意图（聊天默认、判分、RAG、TRPG、博客起草）不该写死厂商模型名 ——
 * 2026-10-20 就因为老会话存了 {@code qwen3.8-max}、换平台后一发送就 400。
 * 现在代码只写 {@code chat} / {@code reasoning} / {@code fast}，换平台改配置即可。
 *
 * <h3>为什么还需要"厂商模型名"</h3>
 * 用户在后台**选模型**时要看到具体是哪个模型（竞技场的意义就是对比模型），
 * 所以对外的可选清单（catalog）仍是厂商名，别名只活在代码与配置里。
 *
 * <h3>数据来源</h3>
 * {@code ui_config.llm_models}：
 * <pre>
 * { "aliases": { "chat": {"provider":"deepseek","id":"deepseek-flash"},
 *                "reasoning": {"provider":"deepseek","id":"deepseek-v4-pro"} },
 *   "defaultAlias": "chat",
 *   "catalog": { "deepseek": ["deepseek-flash","deepseek-v4-pro"] } }
 * </pre>
 * 没有配置时用环境变量引导（单平台 + {@code LLM_MODEL}），保证升级那一次启动可用。
 * 缓存 60 秒：后台改完配置不必重启，最迟一分钟生效。
 */
public final class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);
    private static final String CFG_KEY = "llm_models";
    private static final long TTL_MS = 60_000L;

    /** 解析结果：用哪个平台、哪个厂商模型 */
    public record Target(String provider, String modelId) {}

    private record Snapshot(Map<String, Target> aliases, String defaultAlias,
                            Map<String, List<String>> catalog, long at) {}

    private static volatile Snapshot cache;

    private ModelRegistry() {}

    // ==================================================================================
    // 解析
    // ==================================================================================

    /** 别名 → 目标；未知别名返回 null（调用方据此报"未知别名"） */
    public static Target resolveAlias(String alias) {
        Snapshot s = snapshot();
        Target t = s.aliases().get(alias);
        if (t == null) t = s.aliases().get(s.defaultAlias());
        return t;
    }

    /**
     * 厂商模型名 → 目标（**allowlist 校验点**）。
     *
     * <p>只有登记在 catalog 里的模型名才允许调用 —— 这样"老会话里存的旧模型名"会在本地被拒，
     * 而不是转发给 provider 换一个 400。
     */
    public static Target resolveModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        String id = modelId.trim();
        Snapshot s = snapshot();
        for (Map.Entry<String, List<String>> e : s.catalog().entrySet()) {
            if (e.getValue().contains(id)) return new Target(e.getKey(), id);
        }
        return null;
    }

    /** 该模型名是否在 allowlist 里 */
    public static boolean allows(String modelId) {
        return resolveModelId(modelId) != null;
    }

    /** 用户可选清单（厂商模型名，按平台分组后扁平化，去重保序） */
    public static List<String> catalogIds() {
        return new ArrayList<>(catalogOrdered().keySet());
    }

    /** 给 /api/models 的 details：[{id, provider, label}] */
    public static List<Map<String, Object>> catalogDetails() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : catalogOrdered().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("provider", e.getValue());
            m.put("label", e.getKey());
            out.add(m);
        }
        return out;
    }

    /** 每个平台的可选模型清单（后台展示/编辑用；返回副本，调用方改不到缓存） */
    public static Map<String, List<String>> catalog() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        snapshot().catalog().forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }
    /** 全部别名（后台展示用） */
    public static Map<String, Target> aliases() {
        return snapshot().aliases();
    }

    public static String defaultAlias() {
        return snapshot().defaultAlias();
    }

    /** 清缓存（后台保存配置后调用，让改动立刻生效） */
    public static void invalidate() {
        cache = null;
    }

    // ==================================================================================
    // 内部
    // ==================================================================================

    /** id → provider（去重保序：同一 id 出现在多个平台时取先出现的） */
    private static Map<String, String> catalogOrdered() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : snapshot().catalog().entrySet()) {
            for (String id : e.getValue()) out.putIfAbsent(id, e.getKey());
        }
        return out;
    }

    private static Snapshot snapshot() {
        Snapshot s = cache;
        if (s != null && System.currentTimeMillis() - s.at() < TTL_MS) return s;
        synchronized (ModelRegistry.class) {
            if (cache == null || System.currentTimeMillis() - cache.at() >= TTL_MS) cache = load();
            return cache;
        }
    }

    @SuppressWarnings("unchecked")
    private static Snapshot load() {
        long now = System.currentTimeMillis();
        try {
            Object raw = UiConfigService.getConfig().get(CFG_KEY);
            if (raw instanceof Map<?, ?> m) {
                Map<String, Target> aliases = new LinkedHashMap<>();
                Object a = m.get("aliases");
                if (a instanceof Map<?, ?> am) {
                    for (Map.Entry<?, ?> e : am.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> t) {
                            String p = String.valueOf(t.get("provider") == null ? "" : t.get("provider")).trim();
                            String id = String.valueOf(t.get("id") == null ? "" : t.get("id")).trim();
                            if (!p.isEmpty() && !id.isEmpty()) aliases.put(String.valueOf(e.getKey()), new Target(p, id));
                        }
                    }
                }
                Map<String, List<String>> catalog = new LinkedHashMap<>();
                Object c = m.get("catalog");
                if (c instanceof Map<?, ?> cm) {
                    for (Map.Entry<?, ?> e : cm.entrySet()) {
                        List<String> ids = new ArrayList<>();
                        if (e.getValue() instanceof List<?> list) {
                            for (Object o : list) {
                                String id = String.valueOf(o).trim();
                                if (!id.isEmpty()) ids.add(id);
                            }
                        }
                        if (!ids.isEmpty()) catalog.put(String.valueOf(e.getKey()), ids);
                    }
                }
                String def = m.get("defaultAlias") == null ? "" : String.valueOf(m.get("defaultAlias")).trim();
                if (!aliases.isEmpty()) {
                    if (def.isEmpty() || !aliases.containsKey(def)) def = aliases.keySet().iterator().next();
                    // catalog 没配时，用别名的目标兜底（至少保证"当前在用的模型"可选）
                    if (catalog.isEmpty()) catalog = catalogFromAliases(aliases);
                    return new Snapshot(aliases, def, catalog, now);
                }
            }
        } catch (Exception e) {
            log.warn("读 ui_config.{} 失败，改用环境变量引导：{}", CFG_KEY, e.toString());
        }
        return bootstrap(now);
    }

    /** 没配置时：单平台 + LLM_MODEL → 一个 chat 别名 */
    private static Snapshot bootstrap(long now) {
        String provider = LlmProviders.defaultProviderName();
        String model = AppConfig.get("LLM_MODEL", "deepseek-flash").trim();
        if (provider == null) provider = "default";
        Map<String, Target> aliases = new LinkedHashMap<>();
        aliases.put("chat", new Target(provider, model));
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        catalog.put(provider, List.of(model));
        log.info("模型注册表（引导模式）：默认别名 chat → {}:{}", provider, model);
        return new Snapshot(aliases, "chat", catalog, now);
    }

    private static Map<String, List<String>> catalogFromAliases(Map<String, Target> aliases) {
        Map<String, LinkedHashSet<String>> tmp = new LinkedHashMap<>();
        for (Target t : aliases.values()) {
            tmp.computeIfAbsent(t.provider(), k -> new LinkedHashSet<>()).add(t.modelId());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : tmp.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }
}
