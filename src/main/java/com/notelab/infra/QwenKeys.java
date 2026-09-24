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
 * Qwen 多 Key 轮换：某把 key 不可用时自动切换到下一把。
 * 不可用判定见 unusableReason(status, body)，覆盖三类上游错误：
 *  - 401：key 本身无效（过期 / 写错 / 被吊销）
 *  - 429 且响应体含 quota 关键词：配额耗尽（insufficient_quota）
 *  - 403 且响应体含权限关键词：key 有效但套餐没有该模型权限（未购买 / 未开通）
 * 已标记的 key 30 分钟后自动重试，充值 / 换模型 / 续费后无需重启服务即可恢复
 * （改 .env 换 key 需重启进程，重启后标记表随进程重建）。
 * 候选列表来自 AppConfig.qwenApiKeys()（QWEN_API_KEYS 逗号分隔；未配置时仅 QWEN_API_KEY）。
 */
public final class QwenKeys {

    private static final Logger log = LoggerFactory.getLogger(QwenKeys.class);
    /** key → 不可用标记（原因 + 时刻）；超过 RETRY_AFTER_MS 后重新参与轮换 */
    private static final Map<String, Mark> UNUSABLE = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 30 * 60 * 1000L;
    /** 最近一次调用成功的 key，后续请求优先使用 */
    private static volatile String current = null;

    private QwenKeys() {}

    /** 不可用标记：reason 进日志，便于区分「key 失效」「欠费」「无模型权限」 */
    private static final class Mark {
        final long at = System.currentTimeMillis();
        final String reason;

        Mark(String reason) {
            this.reason = reason;
        }
    }

    /** 候选 key 顺序：最近可用的排最前，其余按配置顺序；全部被标记不可用时仍按配置顺序返回（宁可再试也不直接报错） */
    public static synchronized List<String> candidates() {
        long now = System.currentTimeMillis();
        List<String> all = AppConfig.qwenApiKeys();
        List<String> active = new ArrayList<>();
        for (String k : all) {
            Mark m = UNUSABLE.get(k);
            if (m == null || now - m.at >= RETRY_AFTER_MS) active.add(k);
        }
        if (active.isEmpty()) active.addAll(all);
        if (current != null && active.remove(current)) active.add(0, current);
        return active;
    }

    /** 标记 key 不可用（30 分钟后自动重试）；reason 写进日志，便于一眼看出是哪种故障 */
    public static void markUnusable(String key, String reason) {
        UNUSABLE.put(key, new Mark(reason));
        log.warn("Qwen key 已标记不可用（{}，{}），30 分钟后自动重试；剩余可用候选 {} 把",
                mask(key), reason, usableCount());
    }

    /** 当前仍可用的 key 数量（未被标记，或标记已过期）：仅用于日志，不含 candidates() 里「全部不可用则退回全量」的兜底 */
    public static synchronized int usableCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (String k : AppConfig.qwenApiKeys()) {
            Mark m = UNUSABLE.get(k);
            if (m == null || now - m.at >= RETRY_AFTER_MS) n++;
        }
        return n;
    }

    /** 记录调用成功的 key，后续请求优先 */
    public static void markWorking(String key) {
        current = key;
    }

    /**
     * 判断该上游错误是否意味着「这把 key 这次不能用，应换下一把试试」。
     * 只有明确属于 key / 额度 / 模型权限问题的才轮换；普通限流与瞬时错误保持原样抛给调用方。
     */
    public static boolean isUnusable(int status, String body) {
        return unusableReason(status, body) != null;
    }

    /** 不可用原因（中文，日志与标记用）；返回 null 表示该错误不该触发换 key */
    public static String unusableReason(int status, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 401) {
            // dashscope 返回 invalid_api_key，token-plan 网关返回 "Invalid API-key provided"
            if (b.contains("invalid_api_key") || b.contains("incorrect api key")
                    || b.contains("invalid api-key") || b.isEmpty()) {
                return "key 无效（401 invalid_api_key）";
            }
            return "未授权（401）";
        }
        if (status == 429 && b.contains("quota")) {
            // 仅 insufficient_quota 等配额类 429 才轮换，避免误伤普通限流
            return "配额耗尽（429 insufficient_quota）";
        }
        if (status == 403 && (b.contains("accessdenied") || b.contains("access to model denied")
                || b.contains("unpurchased") || b.contains("model denied"))) {
            return "无该模型权限（403 AccessDenied.Unpurchased）";
        }
        return null;
    }

    /** 判断上游错误是否为「配额耗尽」（429 + 配额关键词），语义等同 unusableReason 中的 429 分支 */
    public static boolean isQuotaExhausted(int status, String body) {
        return status == 429 && body != null && body.toLowerCase(Locale.ROOT).contains("quota");
    }

    /** key 脱敏：前 8 位 + 尾 4 位（日志展示用） */
    public static String mask(String key) {
        if (key == null || key.length() <= 12) return "***";
        return key.substring(0, 8) + "…" + key.substring(key.length() - 4);
    }
}
