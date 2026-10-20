package com.notelab.infra;

import com.notelab.service.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型可用性探测（带缓存 + 降噪）。
 *
 * <p>背景（2026-09-29 实测）：三处配置里只有两把不同的 key，全部返回 401，于是翻译判分 /
 * toolbox / extract / rag / TRPG 全部静默失效，用户只看到「判分失败，请重试」。
 *
 * <p>本类解决两件事：
 * <ol>
 *   <li>**可用性别每次都去问上游**：探测结果缓存 {@link #TTL_MS}，缓存期内直接返回结论；</li>
 *   <li>**降噪**：只在「可用 ↔ 不可用」状态翻转时打日志。</li>
 * </ol>
 *
 * <p>⚠️ 探测**必须走真实 chat 调用**，不能用 {@code GET /models}：2026-09-30 实测某把 key 对
 * {@code /models} 返回 200（key 有效），但对**所有模型**的 chat 都返回 403 AccessDenied.Unpurchased
 * （没开通）—— 用 /models 判定会得出"可用"的假阳性。
 *
 * <p>2026-10-20 起探测改为经 {@link LlmGateway}（此前自己拼 HTTP + 自己轮换 key）：
 * 好处是 key 轮换与失效标记只在一个地方（{@link KeyRing}），探活也顺带计入用量账本。
 * 代价是探测会生成一小段回答（之前是 {@code max_tokens=1}）——"ping" 的回答只有几十 token，
 * 且有 5 分钟缓存，可以接受。
 */
public final class LlmHealth {

    private static final Logger log = LoggerFactory.getLogger(LlmHealth.class);

    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int PROBE_TIMEOUT_SEC = 10;

    private static volatile Boolean available = null;
    private static volatile long checkedAt = 0L;
    private static volatile String reason = "";

    private LlmHealth() {}

    /** 是否配置了至少一把 key（没配就没必要探测，直接判定不可用） */
    public static boolean configured() {
        return LlmGateway.keyCount() > 0;
    }

    /** 缓存优先的可用性判定；缓存过期才真探测一次 */
    public static boolean available() {
        Boolean v = available;
        if (v != null && System.currentTimeMillis() - checkedAt < TTL_MS) return v;
        return probe().available;
    }

    /** 强制探测并刷新缓存（synchronized：避免并发下重复打上游） */
    public static synchronized Result probe() {
        Result r = configured() ? doProbe() : new Result(false, "未配置大模型（见「系统管理 → 模型与平台」）");
        apply(r.available, r.reason);
        return r;
    }

    /**
     * 外部（判分/生成失败）显式标记不可用 —— 缓存期内继续走本地实现，不再打上游，也就不会再刷日志。
     */
    public static synchronized void markDown(String why) {
        apply(false, why == null ? "" : why);
    }

    private static void apply(boolean ok, String why) {
        boolean changed = (available == null) || (available.booleanValue() != ok);
        available = ok;
        checkedAt = System.currentTimeMillis();
        reason = why == null ? "" : why;
        if (!changed) return;
        if (ok) {
            log.info("大模型可用性：已恢复可用（model={}）", LlmGateway.defaultModelId());
        } else {
            log.warn("大模型可用性：不可用（{}）—— 依赖大模型的判分/生成将降级为本地实现，{} 分钟内不再探测",
                    reason, TTL_MS / 60000);
        }
    }

    private static Result doProbe() {
        String model = LlmGateway.defaultModelId();
        try {
            LlmGateway.chatModel(model, List.of(Map.of("role", "user", "content", "ping")), PROBE_TIMEOUT_SEC);
            log.info("LlmHealth 探测：model={} 可用=true", model);
            return new Result(true, "");
        } catch (LlmException e) {
            String why = KeyRing.unusableReason(e.status, e.body);
            if (why == null) why = e.messageFull();
            log.info("LlmHealth 探测：model={} 可用=false 原因={}（HTTP {}）", model, why, e.status);
            return new Result(false, why, e.status);
        } catch (Exception e) {
            String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage());
            log.info("LlmHealth 探测：model={} 可用=false 原因={}", model, why);
            return new Result(false, why, 0);
        }
    }

    /** 供接口/前端展示的快照：一定会触发一次缓存判定 */
    public static Map<String, Object> snapshot() {
        boolean ok = available();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", ok);
        m.put("mode", ok ? "llm" : "local");
        m.put("reason", reason);
        m.put("key_count", LlmGateway.keyCount());
        m.put("model", LlmGateway.defaultModelId());
        m.put("checked_at", checkedAt == 0 ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(checkedAt), ZoneId.systemDefault()).toString());
        return m;
    }

    public static final class Result {
        public final boolean available;
        public final String reason;
        /** 上游 HTTP 状态码（0 表示没走到 HTTP，如超时/未配置） */
        public final int status;

        Result(boolean available, String reason) {
            this(available, reason, 0);
        }

        Result(boolean available, String reason, int status) {
            this.available = available;
            this.reason = reason == null ? "" : reason;
            this.status = status;
        }
    }
}
