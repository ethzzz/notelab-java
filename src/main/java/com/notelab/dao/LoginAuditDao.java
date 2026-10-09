package com.notelab.dao;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 登录审计域 DAO：{@code login_audit} 单表读写。
 *
 * <p>⚠️ 与 {@link AnalyticsDao} 同理，本类落在 {@code com.notelab.dao} 是因为 {@link Db} 的
 * {@code exec/queryAll} 是**包级私有** —— 只有同包类能直接发 SQL。service 层的
 * {@code LoginAudit} 通过本类落库，不自己拼 SQL。
 *
 * <p>⚠️ 本类**不吞异常**（照常抛）。「审计失败不能影响登录」这条约束由 service 层用 try/catch
 * 兜住 —— 把吞异常写进 DAO，会让「表不存在」这类真问题也一并静默。
 */
public final class LoginAuditDao {

    private LoginAuditDao() {}

    /** 单页上限：再大就是滥用（页面一次也就看几十条），同时兜住被构造的超大 size。 */
    public static final int MAX_PAGE = 200;

    // ==================== 写入 ====================

    /** 单条落库。userId 仅登录成功时有值，其余传 null（列可空）。 */
    public static void insert(long tsMillis, String day, String app, String username, Long userId,
                              String result, String ip, String ua) {
        Db.exec("INSERT INTO login_audit (ts, day, app, username, user_id, result, ip, ua) VALUES (?,?,?,?,?,?,?,?)",
                new Timestamp(tsMillis), day, nz(app), nz(username), userId, nz(result), nz(ip), ua);
    }

    /** 清理早于 {@code day} 的行，返回删除条数（定时任务要打印行数 —— 静默删数据最难排查）。 */
    public static int cleanBefore(String day) {
        return Db.execCount("DELETE FROM login_audit WHERE day < ?", day);
    }

    // ==================== 查询（超管审计页） ====================

    /**
     * 各结果计数（页面顶部一眼看出异常信号）。
     *
     * <p>两种口径二选一：{@code day} 精确某天；{@code fromDay} 起的连续区间（含当天，用于
     * 「过去 N 天」下拉）。两者都 null = 全部（保留期内）。
     */
    public static List<Map<String, Object>> countByResult(String day, String fromDay) {
        StringBuilder sb = new StringBuilder("SELECT result, COUNT(*) AS n FROM login_audit WHERE ");
        List<Object> args = new ArrayList<>();
        if (notBlank(day)) { sb.append("day=?"); args.add(day); }
        else if (notBlank(fromDay)) { sb.append("day>=?"); args.add(fromDay); }
        else { sb.append("1=1"); }
        sb.append(" GROUP BY result ORDER BY n DESC");
        return Db.queryAll(sb.toString(), args.toArray());
    }

    /**
     * 条件分页查询，按时间倒序。条件都可不传（null / 空串 = 不过滤）。
     *
     * <p>⚠️ LIMIT / OFFSET 是**拼接**而非占位符：值来自服务端 clamp 过的 int，没有任何字符串参与
     * （无注入面）；而 MySQL 对 {@code LIMIT ?} 的支持依赖驱动版本，直拼更稳。
     */
    public static List<Map<String, Object>> search(String day, String fromDay,
                                                   String ip, String username, String result,
                                                   int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(day, fromDay, ip, username, result, args);
        int lim = Math.max(1, Math.min(MAX_PAGE, limit));
        int off = Math.max(0, offset);
        return Db.queryAll("SELECT id, ts, app, username, user_id, result, ip, ua FROM login_audit"
                + where + " ORDER BY ts DESC, id DESC LIMIT " + lim + " OFFSET " + off, args.toArray());
    }

    /** 同条件的总条数（页面的分页器用）。 */
    public static long count(String day, String fromDay, String ip, String username, String result) {
        List<Object> args = new ArrayList<>();
        String where = where(day, fromDay, ip, username, result, args);
        Map<String, Object> r = Db.queryOne("SELECT COUNT(*) AS n FROM login_audit" + where, args.toArray());
        Object v = r == null ? null : r.get("n");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // ==================== 内部 ====================

    /**
     * 拼 WHERE。条件全部走 {@code ?} 占位符，值进 args。
     *
     * <p>ip / username 用**精确匹配**而不是 LIKE：审计要回答的是「某个账号被谁试过」
     * 「某个 IP 都干了什么」，精确匹配可预测且不必处理 LIKE 通配符转义。
     *
     * <p>时间条件：{@code day}（精确某天）与 {@code fromDay}（该日起、含当天）由调用方保证
     * **二选一**（Controller 层已互斥），DAO 不做仲裁 —— 两个都传时两个条件都会拼上（AND）。
     * {@code day} 是 {@code yyyy-MM-dd} 字符串，字典序即时间序，{@code day>=?} 即「从该日起」。
     */
    private static String where(String day, String fromDay, String ip, String username, String result,
                                List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (notBlank(day)) { sb.append(" AND day=?"); args.add(day); }
        if (notBlank(fromDay)) { sb.append(" AND day>=?"); args.add(fromDay); }
        if (notBlank(ip)) { sb.append(" AND ip=?"); args.add(ip); }
        if (notBlank(username)) { sb.append(" AND username=?"); args.add(username); }
        if (notBlank(result)) { sb.append(" AND result=?"); args.add(result); }
        return sb.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
