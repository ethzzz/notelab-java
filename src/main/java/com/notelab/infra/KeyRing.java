package com.notelab.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 某个平台内部的 key 轮换 + 健康标记。
 *
 * <p>与旧 {@link QwenKeys} 的区别：**按平台隔离**。旧实现是一张全局 {@code UNUSABLE} 表 + 一个全局
 * {@code current}，多平台时两家的 key 混在一个池里 —— 某把 key 403 了，日志里看不出是哪家的，
 * 而且"最近可用的那把"可能属于另一个平台。现在所有状态都以 provider 名为键。
 *
 * <p>判定口径沿用旧实现（401 无效 / 429 配额 / 403 无模型权限才换下一把，普通限流不换），
 * 并补上 Anthropic 的错误形状（{@code invalid x-api-key}、{@code rate_limit_error}），
 * 这样以后接 Claude 不用再改一遍判定。
 */
public final class KeyRing {

    private static final Logger log = LoggerFactory.getLogger(KeyRing.class);

    private record Mark(long at, String reason) {}

    /** provider → (key → 不可用标记) */
    private static final Map<String, Map<String, Mark>> UNUSABLE = new ConcurrentHashMap<>();
    /** provider → 最近一次调用成功的 key（下次优先用它） */
    private static final Map<String, String> CURRENT = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 30 * 60 * 1000L;

    private KeyRing() {}

    /**
     * 候选 key（该平台的）：最近可用的排最前，其余按配置顺序；全部被标记时仍返回全量
     * （宁可再试一次，也不直接报"没有 key" —— 充值/换套餐后无需重启即可恢复）。
     */
    public static List<String> candidates(String provider) {
        LlmProviders.Provider p = LlmProviders.get(provider);
        if (p == null) return List.of();
        long now = System.currentTimeMillis();
        Map<String, Mark> marks = UNUSABLE.getOrDefault(provider, Map.of());

        List<String> active = new ArrayList<>();
        for (String k : p.apiKeys()) {
            Mark m = marks.get(k);
            if (m == null || now - m.at >= RETRY_AFTER_MS) active.add(k);
        }
        if (active.isEmpty()) active.addAll(p.apiKeys());

        String cur = CURRENT.get(provider);
        if (cur != null && active.remove(cur)) active.add(0, cur);
        return active;
    }

    /** 记录调用成功的 key，后续请求优先用它 */
    public static void markWorking(String provider, String key) {
        CURRENT.put(provider, key);
    }

    /** 标记不可用（30 分钟后自动重试）；日志带上平台名，排障时能一眼看出是哪家的 key */
    public static void markUnusable(String provider, String key, String reason) {
        UNUSABLE.computeIfAbsent(provider, k -> new ConcurrentHashMap<>()).put(key, new Mark(System.currentTimeMillis(), reason));
        log.warn("[{}] key 已标记不可用（{}，{}），30 分钟后自动重试；剩余可用候选 {} 把",
                provider, mask(key), reason, usableCount(provider));
    }

    /** 仍可用的 key 数（不含"全部不可用则退回全量"的兜底），仅用于日志 */
    public static int usableCount(String provider) {
        LlmProviders.Provider p = LlmProviders.get(provider);
        if (p == null) return 0;
        long now = System.currentTimeMillis();
        Map<String, Mark> marks = UNUSABLE.getOrDefault(provider, Map.of());
        int n = 0;
        for (String k : p.apiKeys()) {
            Mark m = marks.get(k);
            if (m == null || now - m.at >= RETRY_AFTER_MS) n++;
        }
        return n;
    }

    /** 该错误是否意味着「这把 key 这次不能用，应换下一把」 */
    public static boolean isUnusable(int status, String body) {
        return unusableReason(status, body) != null;
    }

    /**
     * 不可用原因（中文，日志与标记用）；{@code null} 表示**不该**换 key（普通限流/瞬时错误原样抛出）。
     *
     * <p>覆盖两家协议的形状：OpenAI 兼容（401 invalid_api_key / 429 insufficient_quota /
     * 403 AccessDenied.Unpurchased）与 Anthropic（401 invalid x-api-key / 429 rate_limit_error）。
     */
    public static String unusableReason(int status, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 401) {
            if (b.contains("invalid_api_key") || b.contains("incorrect api key")
                    || b.contains("invalid api-key") || b.contains("invalid x-api-key") || b.isEmpty()) {
                return "key 无效（401）";
            }
            return "未授权（401）";
        }
        if (status == 429 && (b.contains("quota") || b.contains("insufficient_quota"))) {
            return "配额耗尽（429 insufficient_quota）";
        }
        if (status == 403 && (b.contains("accessdenied") || b.contains("access to model denied")
                || b.contains("unpurchased") || b.contains("model denied"))) {
            return "无该模型权限（403 AccessDenied.Unpurchased）";
        }
        return null;
    }

    /** 配额耗尽（429 + 配额关键词）；与 unusableReason 的 429 分支同义 */
    public static boolean isQuotaExhausted(int status, String body) {
        return status == 429 && body != null && body.toLowerCase(Locale.ROOT).contains("quota");
    }

    /** 日志展示用掩码：首 7 末 4（与 LlmProviders / LlmHealth 同口径） */
    public static String mask(String key) {
        return LlmProviders.mask(key);
    }
}
