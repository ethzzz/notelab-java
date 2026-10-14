package com.notelab.dao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 练习实践 · 记录域 DAO：{@code practice_record} 单表读写。
 *
 * <p>⚠️ 本类必须落在 {@code com.notelab.dao}：{@link Db} 的 {@code exec/queryAll/queryOne} 是**包级私有**，
 * 只有同包类能直接发 SQL（同 {@link LowCodeDao} 的约定）。
 *
 * <p>为什么是表而不是 {@code ui_config} 的一个键：记录会随条数无限增长（每条还带一段 Markdown 正文），
 * 而「删一条记录」不该重写整份文档 —— 判据与 {@code lowcode_records} 一致。
 *
 * <p>⚠️ <b>列表查询刻意不返回 {@code content}</b>：正文是 LONGTEXT，一页 20 条整包吐出来纯属浪费
 * （列表页一个字都不显示）。要正文必须走 {@link #getRecord(long)} 单条读。
 */
public final class PracticeDao {

    private PracticeDao() {}

    /** 列表默认/上限条数（Controller 用它 clamp 前端传的 size） */
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    // ==================== 写入 ====================

    /** 新建一条记录（正文留空，进编辑页后再写）。@return 新记录 id —— 前端要靠它跳到二级页面 */
    public static long insertRecord(String title, String type, String content,
                                    Long userId, String userName) {
        return Db.execInsert(
                "INSERT INTO practice_record (title, type, content, created_by, created_by_name, "
                        + "updated_by, updated_by_name) VALUES (?,?,?,?,?,?,?)",
                title, type, content == null ? "" : content,
                userId, nn(userName), userId, nn(userName));
    }

    /**
     * 只改「非内容数据」（标题 + 类型）。正文一个字都不碰 —— 这正是编辑页把两类数据分开保存的落点：
     * 用户在顶部改个标题不该把下面正在写的正文一起提交（也就不会覆盖）。
     *
     * <p>⚠️ 刻意**不在 UPDATE 里写 updated_at**：列上有 {@code ON UPDATE CURRENT_TIMESTAMP}，
     * 手工赋值会把它钉死成应用侧的时间，与 DB 时钟不一致。
     *
     * @return 受影响行数（0 = 记录不存在）
     */
    public static int updateMeta(long id, String title, String type, Long userId, String userName) {
        return Db.execCount(
                "UPDATE practice_record SET title=?, type=?, updated_by=?, updated_by_name=? WHERE id=?",
                title, type, userId, nn(userName), id);
    }

    /** 只改「内容数据」（Markdown 正文）。@return 受影响行数（0 = 记录不存在） */
    public static int updateContent(long id, String content, Long userId, String userName) {
        return Db.execCount(
                "UPDATE practice_record SET content=?, updated_by=?, updated_by_name=? WHERE id=?",
                content == null ? "" : content, userId, nn(userName), id);
    }

    /** 按 id 删除。返回删除行数（0 = 已不存在，属正常，不该报 404） */
    public static int deleteRecord(long id) {
        return Db.execCount("DELETE FROM practice_record WHERE id=?", id);
    }

    // ==================== 查询 ====================

    /**
     * 单条记录（**含正文**）。不存在返回 null。
     *
     * <p>正文统一走这里读：编辑页打开时要拿它填进编辑器。
     */
    public static Map<String, Object> getRecord(long id) {
        Map<String, Object> r = Db.queryOne(
                "SELECT id, title, type, content, created_by, created_by_name, "
                        + "updated_by, updated_by_name, created_at, updated_at "
                        + "FROM practice_record WHERE id=?", id);
        return r == null ? null : rowOf(r, true);
    }

    /**
     * 列表（**不含正文**，新的在前）。
     *
     * @param title 标题模糊匹配的关键词；空串 = 不过滤
     * @param types 类型多选；空表 = 不过滤（任一命中即可）
     */
    public static List<Map<String, Object>> listRecords(String title, List<String> types,
                                                        int offset, int limit) {
        Cond c = cond(title, types);
        String sql = "SELECT id, title, type, created_by, created_by_name, updated_by, updated_by_name, "
                + "created_at, updated_at FROM practice_record "
                + c.where
                + "ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(c.args);
        args.add(limit);
        args.add(offset);
        List<Map<String, Object>> rows = Db.queryAll(sql, args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(rowOf(r, false));
        return out;
    }

    /** 满足同一筛选条件的总条数（分页器用） */
    public static long countRecords(String title, List<String> types) {
        Cond c = cond(title, types);
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS c FROM practice_record " + c.where, c.args.toArray());
        return r == null ? 0 : num(r.get("c"));
    }

    // ==================== 内部 ====================

    /** WHERE 片段 + 参数（两个查询共用，避免「列表加了条件、计数忘了加」这类静默不一致） */
    private record Cond(String where, List<Object> args) {}

    private static Cond cond(String title, List<String> types) {
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String q = title == null ? "" : title.trim();
        if (!q.isEmpty()) {
            parts.add("title LIKE ?");
            // 转义 LIKE 元字符，否则用户输个 % 就等于「匹配全部」（看着像筛选失效）
            args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (types != null && !types.isEmpty()) {
            parts.add("type IN (" + "?,".repeat(types.size() - 1) + "?)");
            args.addAll(types);
        }
        if (parts.isEmpty()) return new Cond("", args);
        return new Cond("WHERE " + String.join(" AND ", parts) + " ", args);
    }

    private static Map<String, Object> rowOf(Map<String, Object> r, boolean withContent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", num(r.get("id")));
        m.put("title", str(r.get("title")));
        m.put("type", str(r.get("type")));
        if (withContent) m.put("content", str(r.get("content")));
        m.put("created_by", num(r.get("created_by")));
        m.put("created_by_name", str(r.get("created_by_name")));
        m.put("updated_by", num(r.get("updated_by")));
        m.put("updated_by_name", str(r.get("updated_by_name")));
        m.put("created_at", str(r.get("created_at")));
        m.put("updated_at", str(r.get("updated_at")));
        return m;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    /** Timestamp/Number 统一转 long，避免 Jackson 把 Timestamp 序列化成毫秒数导致前后端口径不一致 */
    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
