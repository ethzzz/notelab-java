package com.notelab.dao;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 全站埋点域 DAO：analytics_events 单表读写（PRD-P0 · 全站数据闭环）。
 *
 * <p>⚠️ 本类刻意落在 {@code com.notelab.dao}：{@link Db} 的 {@code exec/queryAll} 是**包级私有**，
 * 只有同包类能直接发 SQL。service 层的 {@code EventRecorder} 通过本类落库，不自己拼 SQL。
 *
 * <p>⚠️ 性能红线（4GB 机器）：所有聚合 SQL 都带 {@code day} 条件以命中 {@code idx_day_event}；
 * **禁止**写 {@code GROUP BY DATE(ts)} / {@code DATE(ts)=?} —— 函数套列 = 索引失效 = 全表扫。
 * 第一版不做预聚合日表（PRD §9：日活过千再说）。
 */
public final class AnalyticsDao {

    private AnalyticsDao() {}

    private static final int Q = 3;   // NOW(3)：列是 DATETIME(3)，比较也要带毫秒精度

    // ==================== 写入 ====================

    /** 单条落库。sessionId/anonId 未知时传 null（落空串，不是 NULL —— 见 DbSchema 注释）。 */
    public static void insert(long tsMillis, String day, String app, String event,
                              String sessionId, String anonId, Long userId, String path,
                              String propsJson, String ipHash, String ua) {
        Db.exec("INSERT INTO analytics_events "
                        + "(ts, day, app, event, session_id, anon_id, user_id, path, props, ip_hash, ua) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                new Timestamp(tsMillis), day, app, event,
                nz(sessionId), nz(anonId), userId, path, propsJson, ipHash, ua);
    }

    /** PRD §7.3 防前端重复触发：同 session + 同 event 在 windowSec 内已存在 → true（该丢弃）。 */
    public static boolean existsRecent(String sessionId, String event, int windowSec) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        Map<String, Object> r = Db.queryOne(
                "SELECT 1 AS x FROM analytics_events "
                        + "WHERE session_id=? AND event=? AND ts > NOW(" + Q + ") - INTERVAL ? SECOND LIMIT 1",
                sessionId, event, windowSec);
        return r != null;
    }

    /**
     * PRD §4.1 身份回填：把最近 windowMin 分钟内该 anon_id 的**未登录**事件补上 user_id，
     * 让登录前后连成一个人（否则永远分不清 anon 与 user）。返回受影响行数。
     */
    public static int backfillUser(String anonId, long userId, int windowMin) {
        if (anonId == null || anonId.isEmpty()) return 0;
        return Db.execCount("UPDATE analytics_events SET user_id=? "
                        + "WHERE anon_id=? AND user_id IS NULL AND ts > NOW(" + Q + ") - INTERVAL ? MINUTE",
                userId, anonId, windowMin);
    }

    /** PRD §8 定时清理：删 days 天前的明细，返回删除行数（调用方打印，别静默）。 */
    public static int cleanBefore(String day) {
        return Db.execCount("DELETE FROM analytics_events WHERE day < ?", day);
    }

    // ==================== 查询（看板） ====================

    /** DAU：当天活跃用户数（登录用 user_id、游客用 anon_id，二选一，避免同一人被算两次）。 */
    public static long dau(String app, String day) {
        return num(Db.queryOne("SELECT COUNT(DISTINCT COALESCE(user_id, anon_id)) AS n "
                + "FROM analytics_events WHERE app=? AND day=?", app, day));
    }

    /** 新用户数：当天**首次出现**的 anon_id 个数。 */
    public static long newUsers(String app, String day) {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM ("
                        + "  SELECT anon_id FROM analytics_events WHERE app=? GROUP BY anon_id HAVING MIN(day)=?"
                        + ") t", app, day);
        return num(r);
    }

    public static long eventTotal(String app, String from, String to) {
        return num(Db.queryOne("SELECT COUNT(*) AS n FROM analytics_events WHERE app=? AND day BETWEEN ? AND ?",
                app, from, to));
    }

    /** 每日活跃：day / dau / events（趋势图与指标卡共用）。 */
    public static List<Map<String, Object>> dailyActive(String app, String from, String to) {
        return Db.queryAll("SELECT day, COUNT(DISTINCT COALESCE(user_id, anon_id)) AS dau, COUNT(*) AS events "
                + "FROM analytics_events WHERE app=? AND day BETWEEN ? AND ? GROUP BY day ORDER BY day",
                app, from, to);
    }

    /** 每日新用户（首现日落在窗口内）。 */
    public static List<Map<String, Object>> newUsersByDay(String app, String from, String to) {
        return Db.queryAll("SELECT d AS day, COUNT(*) AS n FROM ("
                + "  SELECT anon_id, MIN(day) AS d FROM analytics_events WHERE app=? GROUP BY anon_id"
                + ") t WHERE d BETWEEN ? AND ? GROUP BY d ORDER BY d", app, from, to);
    }

    /**
     * 留存矩阵：窗口内每天作为「首次活跃日 D」的 cohort 与 offset 天后仍活跃人数。
     * 返回行 {day, cohort, retained}。窗口最后 offset 天没有 D+offset 数据，属正常（调用方剔除）。
     */
    public static List<Map<String, Object>> retention(String app, String from, String to, int offsetDays) {
        return Db.queryAll(
                "SELECT a.day AS day, COUNT(DISTINCT a.k) AS cohort, COUNT(DISTINCT b.k) AS retained "
                        + "FROM (SELECT day, COALESCE(user_id,anon_id) AS k FROM analytics_events "
                        + "      WHERE app=? AND day BETWEEN ? AND ?) a "
                        + "LEFT JOIN (SELECT DISTINCT day, COALESCE(user_id,anon_id) AS k FROM analytics_events WHERE app=?) b "
                        + "  ON b.k=a.k AND b.day=DATE_ADD(a.day, INTERVAL ? DAY) "
                        + "GROUP BY a.day ORDER BY a.day",
                app, from, to, app, offsetDays);
    }

    /** 事件排行：event / 次数 / 触达人数。 */
    public static List<Map<String, Object>> top(String app, String from, String to, int limit) {
        return Db.queryAll("SELECT event, COUNT(*) AS n, "
                + "COUNT(DISTINCT COALESCE(user_id,anon_id)) AS users "
                + "FROM analytics_events WHERE app=? AND day BETWEEN ? AND ? "
                + "GROUP BY event ORDER BY n DESC LIMIT ?", app, from, to, limit);
    }

    /** 按天时间序列（指定事件；event 为 null 时给全部事件总量）。 */
    public static List<Map<String, Object>> series(String app, String event, String from, String to) {
        if (event == null || event.isEmpty()) {
            return Db.queryAll("SELECT day, COUNT(*) AS n FROM analytics_events "
                    + "WHERE app=? AND day BETWEEN ? AND ? GROUP BY day ORDER BY day", app, from, to);
        }
        return Db.queryAll("SELECT day, COUNT(*) AS n FROM analytics_events "
                + "WHERE app=? AND event=? AND day BETWEEN ? AND ? GROUP BY day ORDER BY day",
                app, event, from, to);
    }

    /** 页面 PV：path / 浏览量。 */
    public static List<Map<String, Object>> pagePv(String app, String from, String to) {
        return Db.queryAll("SELECT path, COUNT(*) AS pv FROM analytics_events "
                + "WHERE app=? AND event='page_view' AND day BETWEEN ? AND ? AND path IS NOT NULL "
                + "GROUP BY path ORDER BY pv DESC", app, from, to);
    }

    /**
     * 页面停留原始样本（path + dwell_ms），由调用方算 p50/p95（MySQL 无 percentile 函数）。
     *
     * <p>停留 = 该 path 的 page_view 到**同一 session 的下一个事件**的时间差，用窗口函数 LEAD 一次算完
     * （比在 Java 里拉全量明细再排便宜得多）。两个截断：① 无后继事件（会话结束）→ 丢弃；
     * ② 超过 {@code capSec} 秒 → 丢弃（挂着标签页不动会污染 p95）。
     */
    public static List<Map<String, Object>> pageDwell(String app, String from, String to, int capSec) {
        return Db.queryAll(
                "SELECT path, TIMESTAMPDIFF(MICROSECOND, ts, next_ts)/1000 AS dwell_ms FROM ("
                        + "  SELECT path, session_id, ts, "
                        + "         LEAD(ts) OVER (PARTITION BY session_id, app ORDER BY ts) AS next_ts "
                        + "  FROM analytics_events "
                        + "  WHERE app=? AND event='page_view' AND day BETWEEN ? AND ?"
                        + ") t WHERE next_ts IS NOT NULL "
                        + "  AND TIMESTAMPDIFF(SECOND, ts, next_ts) BETWEEN 0 AND ?",
                app, from, to, capSec);
    }

    /** 漏斗人群：做了某事件的人数（按 COALESCE(user_id,anon_id) 去重）。 */
    public static long peopleWith(String app, String event, String from, String to) {
        return num(Db.queryOne("SELECT COUNT(DISTINCT COALESCE(user_id,anon_id)) AS n FROM analytics_events "
                + "WHERE app=? AND event=? AND day BETWEEN ? AND ?", app, event, from, to));
    }

    /** 漏斗末级：game_start 后 windowSec 秒内出现游戏内事件（spire_node_reached / loot_raid_settle）的 session 数。 */
    public static long playedGameWithin(String app, String from, String to, int windowSec) {
        return num(Db.queryOne(
                "SELECT COUNT(DISTINCT g.session_id) AS n FROM analytics_events g "
                        + "JOIN analytics_events n ON n.session_id=g.session_id AND n.app=g.app "
                        + "  AND n.ts > g.ts AND n.ts <= g.ts + INTERVAL ? SECOND "
                        + "WHERE g.app=? AND g.event='game_start' "
                        + "  AND n.event IN ('spire_node_reached','loot_raid_settle') "
                        + "  AND g.day BETWEEN ? AND ?",
                windowSec, app, from, to));
    }

    // ==================== 工具 ====================

    private static String nz(String s) { return s == null ? "" : s; }

    private static long num(Map<String, Object> row) {
        if (row == null) return 0L;
        Object v = row.get("n");
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
}
