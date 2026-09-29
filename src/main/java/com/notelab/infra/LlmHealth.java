package com.notelab.infra;

import com.notelab.common.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型可用性探测（带缓存 + 降噪）。
 *
 * <p>背景（2026-09-29 实测）：三处配置里只有两把不同的 key，全部返回 401 InvalidApiKey，
 * 且 {@code QWEN_API_KEYS} 只有一把、无轮转兜底。于是翻译判分 / toolbox / extract / rag / TRPG
 * 全部静默失效 —— 用户侧只看到「判分失败，请重试」，不知道发生了什么。
 *
 * <p>本类解决两件事：
 * <ol>
 *   <li>**可用性别当成每次都去问上游**：探测结果缓存 {@link #TTL_MS}，缓存期内直接返回结论。
 *       没有 key 时，不会每个请求都去打一次 401。</li>
 *   <li>**降噪**：只在「可用 ↔ 不可用」**状态翻转**时打一条日志，不再每次失败都刷。</li>
 * </ol>
 *
 * <p>⚠️ 探测**必须走真实 chat 调用**（{@code max_tokens=1}），不能用 {@code GET /models}：
 * 2026-09-30 实测某把 key 对 {@code /models} 返回 200（key 本身有效），但对**所有模型**的 chat
 * 都返回 403 AccessDenied.Unpurchased（没开通）。用 /models 判定会得出"可用"的假阳性，
 * 结果判分时才发现根本出不了内容。一次 1 token 的调用很便宜，且结果缓存 5 分钟。
 */
public final class LlmHealth {

    private static final Logger log = LoggerFactory.getLogger(LlmHealth.class);

    /** 探测结果缓存时长：期间内不重复打上游 */
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final int PROBE_TIMEOUT_SEC = 8;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static volatile Boolean available = null;
    private static volatile long checkedAt = 0L;
    private static volatile String reason = "";

    private LlmHealth() {}

    /** 是否配置了至少一把 key（没配就没必要探测，直接判定不可用） */
    public static boolean configured() {
        return !AppConfig.qwenApiKeys().isEmpty();
    }

    /** 缓存优先的可用性判定；缓存过期才真探测一次 */
    public static boolean available() {
        Boolean v = available;
        if (v != null && System.currentTimeMillis() - checkedAt < TTL_MS) return v;
        return probe().available;
    }

    /** 强制探测并刷新缓存（synchronized：避免并发下重复打上游） */
    public static synchronized Result probe() {
        Result r = configured() ? doProbe() : new Result(false, "未配置大模型 key（QWEN_API_KEYS 为空）");
        apply(r.available, r.reason);
        return r;
    }

    /**
     * 外部（判分/生成失败）显式标记不可用。
     * 目的：缓存期内继续走本地实现，不再打上游，也就不会再刷 401 日志。
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
            log.info("大模型可用性：已恢复可用");
        } else {
            log.warn("大模型可用性：不可用（{}）—— 依赖大模型的判分/生成将降级为本地实现，{} 分钟内不再探测",
                    reason, TTL_MS / 60000);
        }
    }

    private static Result doProbe() {
        List<String> keys = QwenKeys.candidates();
        if (keys.isEmpty()) return new Result(false, "未配置大模型 key（QWEN_API_KEYS 为空）");
        String last = null;
        for (String k : keys) {
            Result r = chatProbe(k);
            log.info("LlmHealth 探测：model={} key={} 可用={} 原因={}",
                    AppConfig.qwenModel(), QwenKeys.mask(k), r.available, r.reason);
            if (r.available) {
                QwenKeys.markWorking(k);
                return r;
            }
            // 401（key 无效）/ 403（key 有效但没开通该模型）属于"这把 key 用不了"，标记后 30 分钟内不再试
            if (r.status == 401 || r.status == 403) QwenKeys.markUnusable(k, r.reason);
            last = r.reason;
        }
        return new Result(false, last == null ? "无可用 key" : last);
    }

    /** 兜底探测：发一条只生成 1 个 token 的 chat 请求，确认这条链路真的通 */
    private static Result chatProbe(String key) {
        try {
            String payload = "{\"model\":\"" + AppConfig.qwenModel()
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1,\"stream\":false}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(AppConfig.qwenBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SEC + 2))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int st = resp.statusCode();
            if (st == 200) return new Result(true, "", st);
            String why = QwenKeys.unusableReason(st, resp.body());
            if (why == null) why = "chat 探测返回 HTTP " + st;
            return new Result(false, why, st);
        } catch (Exception e) {
            return new Result(false, "chat 探测异常：" + e.getClass().getSimpleName(), 0);
        }
    }

    /** 供接口/前端展示的快照：一定会触发一次缓存判定 */
    public static Map<String, Object> snapshot() {
        boolean ok = available();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", ok);
        m.put("mode", ok ? "llm" : "local");
        m.put("reason", reason);
        m.put("key_count", AppConfig.qwenApiKeys().size());
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
