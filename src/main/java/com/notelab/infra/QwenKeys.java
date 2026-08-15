package com.notelab.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.notelab.common.AppConfig;

/**
 * Qwen 多 Key 轮换：某把 key 配额耗尽（HTTP 429 insufficient_quota）时自动切换到下一把。
 * 候选列表来自 AppConfig.qwenApiKeys()（QWEN_API_KEYS 逗号分隔；未配置时仅 QWEN_API_KEY）。
 * 已耗尽的 key 标记 30 分钟后自动重试，充值后无需重启服务即可恢复。
 */
public final class QwenKeys {

    private static final Logger log = LoggerFactory.getLogger(QwenKeys.class);
    /** 已耗尽 key → 标记时间；超过 RETRY_AFTER_MS 后重新参与轮换 */
    private static final Map<String, Long> EXHAUSTED = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 30 * 60 * 1000L;
    /** 最近一次调用成功的 key，后续请求优先使用 */
    private static volatile String current = null;

    private QwenKeys() {}

    /** 候选 key 顺序：最近可用的排最前，其余按配置顺序；全部被标记耗尽时仍按配置顺序返回（宁可再试也不直接报错） */
    public static synchronized List<String> candidates() {
        long now = System.currentTimeMillis();
        List<String> all = AppConfig.qwenApiKeys();
        List<String> active = new ArrayList<>();
        for (String k : all) {
            Long t = EXHAUSTED.get(k);
            if (t == null || now - t >= RETRY_AFTER_MS) active.add(k);
        }
        if (active.isEmpty()) active.addAll(all);
        if (current != null && active.remove(current)) active.add(0, current);
        return active;
    }

    /** 标记 key 配额耗尽（30 分钟后自动重试） */
    public static void markExhausted(String key) {
        EXHAUSTED.put(key, System.currentTimeMillis());
        log.warn("Qwen key 配额耗尽已标记（{}），30 分钟后自动重试；剩余可用候选 {} 把",
                mask(key), Math.max(0, candidates().size() - 1));
    }

    /** 记录调用成功的 key，后续请求优先 */
    public static void markWorking(String key) {
        current = key;
    }

    /** 判断上游错误是否为「配额耗尽」：仅 429 且响应体含配额关键词才轮换，避免误伤普通限流/瞬时错误 */
    public static boolean isQuotaExhausted(int status, String body) {
        if (status != 429 || body == null) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("insufficient_quota") || b.contains("quota");
    }

    /** key 脱敏：前 8 位 + 尾 4 位（日志展示用） */
    public static String mask(String key) {
        if (key == null || key.length() <= 12) return "***";
        return key.substring(0, 8) + "…" + key.substring(key.length() - 4);
    }
}
