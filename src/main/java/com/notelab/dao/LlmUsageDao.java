package com.notelab.dao;

import java.util.List;
import java.util.Map;

/**
 * 大模型用量域 DAO：表 {@code llm_usage} 的写入与按天聚合。
 *
 * <p>为什么自己记账：provider（DeepSeek）只暴露「余额」接口，**没有**按天的用量历史接口；
 * 而「每天最多用多少」这个限额必须由我们自己的账本来算 —— 所以每次调用后把响应里的
 * {@code usage} 落一行。行是**事实**，聚合是**视图**，两者分开，改口径只改查询。
 *
 * <p>⚠️ 不存 key 明文，只存 {@code key_mask}（形如 {@code sk-972f7…1c97}），
 * 否则这张表本身就成了密钥泄露源。
 */
public final class LlmUsageDao {

    private LlmUsageDao() {}

    /**
     * 记一次调用。
     *
     * @param ok 是否成功（失败也记一行，但 tokens 通常为 0；限额只统计成功行）
     */
    public static void record(String model, String keyMask, boolean ok, Integer httpCode,
                              int promptTokens, int completionTokens, int totalTokens, boolean stream) {
        Db.exec("INSERT INTO llm_usage (at, model, key_mask, ok, http_code, prompt_tokens, "
                        + "completion_tokens, total_tokens, stream) VALUES (NOW(3),?,?,?,?,?,?,?,?)",
                nz(model), nz(keyMask), ok ? 1 : 0, httpCode,
                Math.max(0, promptTokens), Math.max(0, completionTokens), Math.max(0, totalTokens),
                stream ? 1 : 0);
    }

    /** 今天的成功调用汇总：{requests, tokens} */
    public static Map<String, Object> today() {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS requests, COALESCE(SUM(total_tokens),0) AS tokens "
                        + "FROM llm_usage WHERE at >= CURDATE() AND ok = 1");
        return r == null ? Map.of("requests", 0, "tokens", 0) : r;
    }

    /** 最近 N 天，按天聚合（含失败数）。今天在最前 */
    public static List<Map<String, Object>> daily(int days) {
        return Db.queryAll(
                "SELECT DATE(at) AS day, COUNT(*) AS requests, COALESCE(SUM(total_tokens),0) AS tokens, "
                        + "COALESCE(SUM(ok = 0),0) AS failures "
                        + "FROM llm_usage WHERE at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) "
                        + "GROUP BY DATE(at) ORDER BY day DESC", days);
    }

    /** 最近 N 天按模型聚合 */
    public static List<Map<String, Object>> byModel(int days) {
        return Db.queryAll(
                "SELECT model, COUNT(*) AS requests, COALESCE(SUM(total_tokens),0) AS tokens "
                        + "FROM llm_usage WHERE at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) AND ok = 1 "
                        + "GROUP BY model ORDER BY tokens DESC", days);
    }

    /** 最近 N 天按 key（掩码）聚合 —— 多把 key 轮换时能看出谁在烧 */
    public static List<Map<String, Object>> byKey(int days) {
        return Db.queryAll(
                "SELECT key_mask, COUNT(*) AS requests, COALESCE(SUM(total_tokens),0) AS tokens "
                        + "FROM llm_usage WHERE at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) AND ok = 1 "
                        + "GROUP BY key_mask ORDER BY tokens DESC", days);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
