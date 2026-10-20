package com.notelab.infra;

import com.notelab.common.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台注册表：**只从环境变量**读「有哪些平台、每个平台怎么调、有哪些 key」。
 *
 * <h3>为什么要单独一层</h3>
 * 之前 key 是当参数传进调用点的（12 处各自 {@code AppConfig.llmKey()}），模型名与 base URL 也是全局单值 ——
 * 于是「换个平台」要改代码、要重启、还容易改漏（2026-10-20 就因为单值 key 与列表 key 不同源，
 * 让 {@code /api/models} 稳定 401）。现在：**平台是数据**（这里从 env 读），**调用方只认模型别名**。
 *
 * <h3>环境变量约定（加平台 = 只加变量，不改代码）</h3>
 * <pre>
 * LLM_PROVIDERS=deepseek,qwen          # 启用的平台名（逗号分隔）
 * LLM_DEEPSEEK_PROTOCOL=openai         # openai | anthropic，默认 openai
 * LLM_DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
 * LLM_DEEPSEEK_API_KEYS=sk-a,sk-b      # 逗号分隔，KeyRing 轮换用；也可只给 _API_KEY
 * LLM_DEEPSEEK_ENABLED=true            # 可选，默认 true
 * LLM_DEEPSEEK_LABEL=DeepSeek          # 可选，给后台页面显示
 * </pre>
 *
 * <h3>为什么 key 只在 env、不进库</h3>
 * 落库就等于"多一个能读到密钥的地方"（备份、dump、后台接口）。env（chmod 600）是这台机器上
 * 最小的暴露面；后台只显示「已配置 + 掩码尾部」，没有任何接口能读回明文。
 *
 * <p>⚠️ **不要**再把 key 放进 `/etc/environment`：那是全局环境变量，优先级高于 `.env`，
 * 2026-10-20 曾因此让「对话正常、模型列表 401」。
 */
public final class LlmProviders {

    private static final Logger log = LoggerFactory.getLogger(LlmProviders.class);

    /** 一个平台（provider）。key 只在内存里，不进任何响应。 */
    public record Provider(String name, String label, String protocol, String baseUrl,
                           List<String> apiKeys, boolean enabled) {

        public boolean hasKey() {
            return !apiKeys.isEmpty();
        }

        /** 给页面/日志用：形如 {@code sk-972f7…1c97}，永不返回明文 */
        public String maskedKey() {
            return apiKeys.isEmpty() ? "" : mask(apiKeys.get(0));
        }
    }

    private static volatile Map<String, Provider> cache;

    private LlmProviders() {}

    /** 全部平台（按名字排序；解析失败只跳过那一个并告警，不抛错） */
    public static Map<String, Provider> all() {
        Map<String, Provider> c = cache;
        if (c != null) return c;
        synchronized (LlmProviders.class) {
            if (cache == null) cache = load();
            return cache;
        }
    }

    /** 取某个平台；不存在返回 null */
    public static Provider get(String name) {
        return name == null ? null : all().get(name);
    }

    /** 环境变量改过之后（重启/热加载）清缓存 */
    public static void invalidate() {
        cache = null;
    }

    // ==================================================================================
    // 解析
    // ==================================================================================

    private static Map<String, Provider> load() {
        Map<String, Provider> out = new LinkedHashMap<>();
        String list = AppConfig.get("LLM_PROVIDERS", "").trim();

        if (list.isEmpty()) {
            // 引导期兼容：没有 LLM_PROVIDERS 时，把旧的单平台变量当成一个叫 default 的平台
            Provider p = legacyDefault();
            if (p != null) {
                out.put(p.name(), p);
                log.info("大模型平台（引导模式）：{} protocol={} base={} keys={}",
                        p.name(), p.protocol(), p.baseUrl(), p.apiKeys().size());
            } else {
                log.warn("大模型平台未配置：既没有 LLM_PROVIDERS，也没有 LLM_API_KEYS/LLM_API_KEY");
            }
            return Collections.unmodifiableMap(out);
        }

        for (String raw : list.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            String upper = name.toUpperCase().replace('-', '_').replace('.', '_');
            String baseUrl = AppConfig.get("LLM_" + upper + "_BASE_URL", "").trim();
            if (baseUrl.isEmpty()) {
                log.warn("平台 {} 没有配 LLM_{}_BASE_URL，跳过", name, upper);
                continue;
            }
            String protocol = AppConfig.get("LLM_" + upper + "_PROTOCOL", "openai").trim().toLowerCase();
            String label = AppConfig.get("LLM_" + upper + "_LABEL", name).trim();
            boolean enabled = !"false".equalsIgnoreCase(AppConfig.get("LLM_" + upper + "_ENABLED", "true").trim());
            List<String> keys = keysOf("LLM_" + upper + "_API_KEYS", "LLM_" + upper + "_API_KEY");
            if (keys.isEmpty()) {
                log.warn("平台 {} 没有配 LLM_{}_API_KEYS，调用会失败（不会崩，只是该平台不可用）", name, upper);
            }
            out.put(name, new Provider(name, label, protocol, baseUrl, keys, enabled));
            log.info("大模型平台：{} label={} protocol={} base={} keys={} enabled={}",
                    name, label, protocol, baseUrl, keys.size(), enabled);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 引导期：旧的单平台变量 → 一个名为 {@code default} 的平台。
     *
     * <p>取值顺序刻意保留 {@code QWEN_*} 兜底**仅此一处**：升级到新结构的那一次启动，
     * 那些老变量可能还在，让服务先跑起来比要求"同一次改完 env"安全。
     * 平台与模型搬到 ui_config 之后，这里只剩"首次导入"的作用。
     */
    private static Provider legacyDefault() {
        String baseUrl = firstNonEmpty("LLM_BASE_URL", "QWEN_BASE_URL");
        List<String> keys = keysOf("LLM_API_KEYS", "LLM_API_KEY");
        if (keys.isEmpty()) keys = keysOf("QWEN_API_KEYS", "QWEN_API_KEY");
        if (baseUrl.isEmpty() && keys.isEmpty()) return null;
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com/v1";
        return new Provider("default", "默认平台", "openai", baseUrl, keys, true);
    }

    /** 逗号列表优先，其次单值；两者都为空返回空列表 */
    private static List<String> keysOf(String csvKey, String singleKey) {
        List<String> keys = new ArrayList<>();
        for (String k : AppConfig.get(csvKey, "").split(",")) {
            String t = k.trim();
            if (!t.isEmpty() && !keys.contains(t)) keys.add(t);
        }
        if (keys.isEmpty()) {
            String one = AppConfig.get(singleKey, "").trim();
            if (!one.isEmpty()) keys.add(one);
        }
        return keys;
    }

    private static String firstNonEmpty(String k1, String k2) {
        String v = AppConfig.get(k1, "").trim();
        return v.isEmpty() ? AppConfig.get(k2, "").trim() : v;
    }

    /** 与 LlmHealth / LlmUsageService 相同的掩码口径：首 7 末 4 */
    static String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 12) return "***";
        return key.substring(0, 7) + "…" + key.substring(key.length() - 4);
    }
}
