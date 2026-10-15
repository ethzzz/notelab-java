package com.notelab.dao;

import java.util.List;
import java.util.Map;

/**
 * B 端仪表盘聚合查询：跨表计数 / 按天新增 / 登录审计分日。
 *
 * <p>⚠️ 本类刻意落在 {@code com.notelab.dao}：{@link Db} 的 {@code exec/queryAll} 是**包级私有**，
 * 只有同包类能直接发 SQL（与 {@link AnalyticsDao} 同规矩）。
 *
 * <p>⚠️ {@link #count}/{@link #dailyNew} 的表名是字符串拼接，**只允许本类内部传硬编码字面量**，
 * 绝不要把 Controller 传来的参数拼进去 —— 那是注入面。
 *
 * <h3>性能（4GB 机器）</h3>
 * 这里统计的表都是**内容表**（笔记/文档/画布/练习，量级是几百行），
 * {@code GROUP BY DATE_FORMAT(created_at)} 会全表扫，当前完全可接受。
 * 埋点表（{@code analytics_events}）量级完全不同，不走本类 —— 它在 {@link AnalyticsDao} 里
 * 一律限定 {@code day} 命中索引。等某张内容表上千行，再给它加 created_at 索引或日汇总表。
 */
public final class DashboardDao {

    private DashboardDao() {}

    /** 表行数。table 只由本类内硬编码字面量传入。 */
    public static long count(String table) {
        Map<String, Object> r = Db.queryOne("SELECT COUNT(*) AS n FROM " + table);
        return r == null ? 0L : num(r.get("n"));
    }

    /**
     * 按天新增条数：{@code created_at} 落在 [from, to] 这两天之间（含 to 全天）。
     * 返回 {@code {day, n}}，day 已格式化成字符串（不让 JDBC 的 java.sql.Date 漏到 JSON 里）。
     */
    public static List<Map<String, Object>> dailyNew(String table, String from, String to) {
        return Db.queryAll("SELECT DATE_FORMAT(created_at,'%Y-%m-%d') AS day, COUNT(*) AS n FROM " + table
                + " WHERE created_at >= ? AND created_at < DATE_ADD(?, INTERVAL 1 DAY)"
                + " GROUP BY day ORDER BY day", from, to);
    }

    /** 练习实践按类型分布 {@code {type, n}}，条数降序（给环形图）。 */
    public static List<Map<String, Object>> practiceByType() {
        return Db.queryAll("SELECT type, COUNT(*) AS n FROM practice_record GROUP BY type ORDER BY n DESC");
    }

    /** 已发布到博客的练习记录条数（{@code published_slug} 非空即视为已发布）。 */
    public static long practicePublished() {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM practice_record WHERE published_slug <> ''");
        return r == null ? 0L : num(r.get("n"));
    }

    /** 登录审计分日分结果：{@code {day, result, n}}（result 由调用方归成成功/失败两类）。 */
    public static List<Map<String, Object>> loginDaily(String app, String from, String to) {
        return Db.queryAll("SELECT DATE_FORMAT(day,'%Y-%m-%d') AS day, result, COUNT(*) AS n FROM login_audit"
                + " WHERE app=? AND day BETWEEN ? AND ? GROUP BY day, result ORDER BY day", app, from, to);
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
