package com.notelab.service;

import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import com.notelab.dao.LlmUsageDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型用量与配额：记账、按天限额、给后台页面出汇总。
 *
 * <h3>为什么限额放在这里（而不是各 Controller）</h3>
 * 所有出网调用都经过 {@code QwenClient.complete / streamChat} 两个口子 —— 在那里拦一次
 * 就等于覆盖全部功能（聊天 / 竞技场 / RAG / 抽取 / 工具箱 / TRPG / 翻译判分 / 博客 AI 起草）。
 * 各 Controller 自己拦，迟早漏一个。
 *
 * <h3>限额存在哪</h3>
 * {@code ui_config.llm_quota} 一个键：<pre>{"enabled":true,"dailyTokens":200000,"dailyRequests":500}</pre>
 * 不用新表：限额是"配置"，不是"事实"，而 ui_config 就是全站配置的既有落点（且后台能改）。
 *
 * <h3>超限时的行为</h3>
 * 抛 {@link QuotaExceededException}（RuntimeException），由调用方按各自既有的失败路径处理：
 * 判分/翻译降级为本地实现、聊天把错误消息发给前端。**不静默返回空答案** —— 静默会让人以为模型坏了。
 */
public final class LlmUsageService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageService.class);

    /** ui_config 里的键名 */
    private static final String QUOTA_KEY = "llm_quota";

    /** 超限异常：消息会直接展示给用户，所以要写清楚「今天用了多少 / 上限多少」 */
    public static final class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String msg) {
            super(msg);
        }
    }

    private LlmUsageService() {}

    // ==================================================================================
    // 记账
    // ==================================================================================

    /** 记一次调用（成功/失败都记）。异常一律吞掉：记账失败不该让业务调用失败。 */
    public static void record(String model, String key, boolean ok, Integer httpCode,
                              int promptTokens, int completionTokens, int totalTokens, boolean stream) {
        try {
            LlmUsageDao.record(model, mask(key), ok, httpCode, promptTokens, completionTokens, totalTokens, stream);
        } catch (Exception e) {
            log.warn("大模型用量记账失败（不影响本次调用）：{}", e.toString());
        }
    }

    /** key 脱敏：sk-972f7…1c97（保留首 7 末 4，与 LlmHealth 的日志口径一致） */
    static String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 12) return "***";
        return key.substring(0, 7) + "…" + key.substring(key.length() - 4);
    }

    // ==================================================================================
    // 限额
    // ==================================================================================

    /** 限额配置的当前值（缺省 = 不限制） */
    public static Map<String, Object> limits() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean enabled = false;
        long dailyTokens = 0, dailyRequests = 0;
        try {
            Object raw = UiConfigService.getConfig().get(QUOTA_KEY);
            if (raw instanceof Map<?, ?> m) {
                enabled = truthy(m.get("enabled"));
                dailyTokens = num(m.get("dailyTokens"));
                dailyRequests = num(m.get("dailyRequests"));
            }
        } catch (Exception e) {
            log.warn("读 llm_quota 失败，按「不限制」处理：{}", e.toString());
        }
        out.put("enabled", enabled);
        out.put("dailyTokens", dailyTokens);
        out.put("dailyRequests", dailyRequests);
        return out;
    }

    /** 写限额（三个字段一起写，语义是"整份替换"） */
    public static Map<String, Object> saveLimits(Boolean enabled, Long dailyTokens, Long dailyRequests) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("enabled", enabled != null && enabled);
        v.put("dailyTokens", dailyTokens == null ? 0 : Math.max(0, dailyTokens));
        v.put("dailyRequests", dailyRequests == null ? 0 : Math.max(0, dailyRequests));
        UiConfigService.update(QUOTA_KEY, v);
        return limits();
    }

    /**
     * 调用前检查今日额度；超了抛 {@link QuotaExceededException}。
     *
     * <p>⚠️ 只在「启用 + 上限 &gt; 0」时生效：0 表示该维度不限制（避免"误设 0 = 全禁"这种惊吓）。
     */
    public static void assertWithinDaily() {
        Map<String, Object> lim = limits();
        if (!Boolean.TRUE.equals(lim.get("enabled"))) return;
        long maxTokens = num(lim.get("dailyTokens"));
        long maxRequests = num(lim.get("dailyRequests"));
        if (maxTokens <= 0 && maxRequests <= 0) return;

        Map<String, Object> t = LlmUsageDao.today();
        long tokens = num(t.get("tokens"));
        long requests = num(t.get("requests"));
        if (maxTokens > 0 && tokens >= maxTokens) {
            throw new QuotaExceededException(String.format(
                    "今日大模型额度已用完（tokens %d / 上限 %d），明天 0 点自动恢复；如需临时放开请到「系统管理 → 模型用量」调整。",
                    tokens, maxTokens));
        }
        if (maxRequests > 0 && requests >= maxRequests) {
            throw new QuotaExceededException(String.format(
                    "今日大模型请求次数已用完（%d / 上限 %d），明天 0 点自动恢复；如需临时放开请到「系统管理 → 模型用量」调整。",
                    requests, maxRequests));
        }
    }

    // ==================================================================================
    // 汇总（给后台页面）
    // ==================================================================================

    /** 页面要的一块数据：今天 + 限额 + 剩余 + 最近 N 天 + 按模型/按 key + 余额 */
    public static Map<String, Object> summary(int days) {
        int d = Math.max(1, Math.min(90, days));
        Map<String, Object> t = LlmUsageDao.today();
        long tokens = num(t.get("tokens"));
        long requests = num(t.get("requests"));
        Map<String, Object> lim = limits();
        long maxTokens = num(lim.get("dailyTokens"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", Map.of("tokens", tokens, "requests", requests));
        out.put("limits", lim);
        // 剩余只对 tokens 维度给（请求维度前端可自己算）；上限为 0 = 不限制 → 剩余为 null
        out.put("remainingTokens", maxTokens > 0 ? Math.max(0, maxTokens - tokens) : null);
        out.put("model", AppConfig.llmModel());
        out.put("keyMask", mask(AppConfig.llmKey()));
        out.put("keyCount", AppConfig.llmApiKeys().size());
        out.put("days", daily(d));
        out.put("byModel", LlmUsageDao.byModel(d));
        out.put("byKey", LlmUsageDao.byKey(d));
        out.put("balance", balance());
        return out;
    }

    private static List<Map<String, Object>> daily(int days) {
        return LlmUsageDao.daily(days);
    }

    private static long balanceAt = 0;
    private static Map<String, Object> balanceCache = null;

    /**
     * 查 provider 的余额（DeepSeek: {@code GET /user/balance}，注意**不在** {@code /v1} 下）。
     *
     * <p>best-effort：换 provider / 接口不存在时返回 {@code {ok:false, reason:...}}，
     * 页面显示"余额未知"而不是整页报错。缓存 5 分钟（这是要花钱的接口？不用，但没必要每次都打）。
     */
    public static Map<String, Object> balance() {
        long now = System.currentTimeMillis();
        if (balanceCache != null && now - balanceAt < 5 * 60 * 1000L) return balanceCache;

        String key = AppConfig.llmKey();
        if (key.isEmpty()) return Map.of("ok", false, "reason", "未配置 key");
        String base = AppConfig.llmBaseUrl();
        String url = base.replaceAll("/v1/?$", "") + "/user/balance";
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + key)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                Map<String, Object> r = Map.of("ok", false, "reason", "HTTP " + resp.statusCode());
                balanceCache = r; balanceAt = now;
                return r;
            }
            Map<String, Object> parsed = new LinkedHashMap<>();
            com.fasterxml.jackson.databind.JsonNode node = JsonUtil.parse(resp.body());
            parsed.put("ok", true);
            parsed.put("available", node.path("is_available").asBoolean(false));
            parsed.put("infos", node.path("balance_infos"));
            balanceCache = parsed; balanceAt = now;
            return parsed;
        } catch (Exception e) {
            Map<String, Object> r = Map.of("ok", false, "reason", e.getClass().getSimpleName() + ": " + e.getMessage());
            balanceCache = r; balanceAt = now;
            return r;
        }
    }
}
