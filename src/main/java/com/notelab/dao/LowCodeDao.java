package com.notelab.dao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 低代码平台 · 表单运行时的提交记录域 DAO：lowcode_records 单表读写。
 *
 * <p>⚠️ 本类必须落在 {@code com.notelab.dao}：{@link Db} 的 {@code exec/queryAll/queryOne} 是**包级私有**，
 * 只有同包类能直接发 SQL（同 {@link AnalyticsDao} 的约定）。
 *
 * <p>⚠️ 设计稿（forms / flows / models / pages）不在这里 —— 那是**配置**，走
 * {@code UiConfigService} 的 {@code lowcode} 键（见 {@code LowCodeController}）。
 * 这里只管**运行时数据**：用户提交的表单内容。两者分开的原因见 DbSchema 里
 * {@code lowcode_records} 的建表注释（提交会无限增长，不能塞进单行配置）。
 */
public final class LowCodeDao {

    private LowCodeDao() {}

    /** 列表默认条数；前端传的 limit 由调用方 clamp（见 LowCodeController）。public：Controller 要用它做 clamp */
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** 提交来源：后台内部试填 */
    public static final String SRC_ADMIN = "admin";
    /** 提交来源：公开链接（匿名） */
    public static final String SRC_PUBLIC = "public";

    // ==================== 写入 ====================

    public static void insertRecord(String formId, String formName, String dataJson,
                                    Long userId, String userName) {
        insertRecord(formId, formName, dataJson, userId, userName, SRC_ADMIN);
    }

    /**
     * @param source 见 {@link #SRC_ADMIN} / {@link #SRC_PUBLIC}。非法值一律归为 admin ——
     *               来源只是标注，写错不该让这一条提交丢掉，所以这里静默纠正而不是抛错。
     */
    public static void insertRecord(String formId, String formName, String dataJson,
                                    Long userId, String userName, String source) {
        String src = SRC_PUBLIC.equals(source) ? SRC_PUBLIC : SRC_ADMIN;
        Db.exec("INSERT INTO lowcode_records (form_id, form_name, data, created_by, created_by_name, source) "
                        + "VALUES (?,?,?,?,?,?)",
                formId, formName, dataJson, userId, userName == null ? "" : userName, src);
    }

    /** 按 id 删除单条。返回删除行数（0 = 不存在，属正常，不该报 404）。 */
    public static int deleteRecord(long id) {
        return Db.execCount("DELETE FROM lowcode_records WHERE id=?", id);
    }

    // ==================== 查询 ====================

    /**
     * 某表单的提交记录（新的在前）。formId 为空时返回全部表单的最近记录。
     *
     * <p>⚠️ {@code data} 是 MEDIUMTEXT，单条可能很大；列表接口靠 {@code limit} 兜底，
     * 且 SQL 带 {@code idx_form}（form_id 有索引）+ {@code ORDER BY id DESC} 走主键倒序，不做全表排序。
     */
    public static List<Map<String, Object>> listRecords(String formId, int limit) {
        return listRecords(formId, null, limit);
    }

    /** source 为空 = 不区分来源；否则只取该来源（见 {@link #SRC_ADMIN} / {@link #SRC_PUBLIC}） */
    public static List<Map<String, Object>> listRecords(String formId, String source, int limit) {
        boolean all = formId == null || formId.isEmpty();
        boolean anySrc = source == null || source.isEmpty();
        StringBuilder w = new StringBuilder();
        if (!all) w.append("form_id=? ");
        if (!anySrc) { if (w.length() > 0) w.append("AND "); w.append("source=? "); }
        String sql = "SELECT id, form_id, form_name, data, created_by, created_by_name, source, created_at "
                + "FROM lowcode_records "
                + (w.length() > 0 ? "WHERE " + w : "")
                + "ORDER BY id DESC LIMIT ?";
        Object[] args;
        if (!all && !anySrc) args = new Object[]{formId, source, limit};
        else if (!all) args = new Object[]{formId, limit};
        else if (!anySrc) args = new Object[]{source, limit};
        else args = new Object[]{limit};
        List<Map<String, Object>> rows = Db.queryAll(sql, args);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", num(r.get("id")));
            m.put("form_id", str(r.get("form_id")));
            m.put("form_name", str(r.get("form_name")));
            m.put("data", str(r.get("data")));
            m.put("created_by", num(r.get("created_by")));
            m.put("created_by_name", str(r.get("created_by_name")));
            m.put("source", str(r.get("source")));
            m.put("created_at", str(r.get("created_at")));
            out.add(m);
        }
        return out;
    }

    public static long countRecords(String formId) {
        return countRecords(formId, null);
    }

    /**
     * 按来源计数（source 为空 = 不区分来源）。
     * 用途：B 端发布面板展示「已收到 N 份公开提交」—— 运营只关心外部数据，不该被内部试填污染。
     */
    public static long countRecords(String formId, String source) {
        boolean all = formId == null || formId.isEmpty();
        boolean anySrc = source == null || source.isEmpty();
        // 组装 WHERE：四态（无条件 / 仅表单 / 仅来源 / 表单+来源）
        StringBuilder w = new StringBuilder();
        if (!all) w.append("form_id=? ");
        if (!anySrc) { if (w.length() > 0) w.append("AND "); w.append("source=? "); }
        String cond = w.length() > 0 ? "WHERE " + w : "";
        Object[] args;
        if (!all && !anySrc) args = new Object[]{formId, source};
        else if (!all) args = new Object[]{formId};
        else if (!anySrc) args = new Object[]{source};
        else args = new Object[0];
        Map<String, Object> r = Db.queryOne("SELECT COUNT(*) AS c FROM lowcode_records " + cond, args);
        return r == null ? 0 : num(r.get("c"));
    }

    // ==================== 内部 ====================

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
