package com.notelab.dao;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博客文章统计：浏览数（按访客去重）与后台改动记录。
 *
 * <p>⚠️ 本类刻意落在 {@code com.notelab.dao}：{@link Db} 的 {@code exec/queryAll} 是**包级私有**，
 * 只有同包类能直接发 SQL（与 {@link AnalyticsDao} / {@link PracticeDao} 同规矩）。
 *
 * <p>⚠️ 这里统计的是博客的**路线文章**（posts，202 篇静态 md），不是练习解答（solutions）。
 * 两者是博客上完全不同的两种内容，别混在一个计数口径里。
 */
public final class BlogStatDao {

    private BlogStatDao() {}

    /**
     * 记一次浏览，返回**计数后**的浏览数。
     *
     * <p>去重靠 {@code (slug, visitor_id)} 主键：{@code INSERT IGNORE} 影响行数 > 0 即「新访客」，
     * 这时才 +1；等于 0 就是老访客，不动计数。
     *
     * <p>⚠️ 用「插入结果」判断而不是「先查再插」：先查后插在并发下会把同一个人算两次
     * （两个请求同时查到"没有"，然后都插、都 +1）。
     */
    public static long recordView(String slug, String visitorId) {
        int inserted = Db.execCount(
                "INSERT IGNORE INTO blog_article_visitor (slug, visitor_id) VALUES (?,?)", slug, visitorId);
        if (inserted > 0) {
            Db.exec("INSERT INTO blog_article_stat (slug, views, last_view_at) VALUES (?,1,NOW()) "
                    + "ON DUPLICATE KEY UPDATE views=views+1, last_view_at=NOW()", slug);
        } else {
            // 老访客不计数，但「最近浏览」要更新 —— 那个时间戳是给后台看的活跃信号，不是计数
            Db.exec("UPDATE blog_article_stat SET last_view_at=NOW() WHERE slug=?", slug);
        }
        return viewsOf(slug);
    }

    /** 单篇浏览数（没有记录 → 0） */
    public static long viewsOf(String slug) {
        Map<String, Object> r = Db.queryOne("SELECT views FROM blog_article_stat WHERE slug=?", slug);
        return r == null ? 0L : num(r.get("views"));
    }

    /**
     * 批量浏览数：slug → views。
     * ⚠️ 一次 {@code IN} 查回来，别在调用方 for 循环里逐条查（列表页 202 篇 = 202 次往返）。
     */
    public static Map<String, Long> viewsOf(List<String> slugs) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (slugs == null || slugs.isEmpty()) return out;
        String ph = String.join(",", Collections.nCopies(slugs.size(), "?"));
        for (Map<String, Object> r : Db.queryAll(
                "SELECT slug, views FROM blog_article_stat WHERE slug IN (" + ph + ")", slugs.toArray())) {
            out.put(String.valueOf(r.get("slug")), num(r.get("views")));
        }
        return out;
    }

    // ==================== 后台改动记录 ====================

    /** 写一条改动记录。只记**经过后台系统**的动作（见 DbSchema 建表处的说明）。 */
    public static void log(String slug, String action, String title, String detail,
                           Long userId, String userName) {
        Db.exec("INSERT INTO blog_article_log (slug, action, title, detail, created_by, created_by_name) "
                        + "VALUES (?,?,?,?,?,?)",
                slug, action, nz(title), nz(detail), userId, nz(userName));
    }

    /** 某篇文章的改动记录（倒序，最近在前） */
    public static List<Map<String, Object>> logsOf(String slug, int limit) {
        return Db.queryAll("SELECT action, title, detail, created_by_name, created_at "
                + "FROM blog_article_log WHERE slug=? ORDER BY id DESC LIMIT ?", slug, limit);
    }

    /**
     * 每篇文章的**最后一条**改动（列表页展示用），slug → {action, by, at}。
     * 用自连接取每组的 MAX(id)，一次查完；不要按 slug 逐条查。
     */
    public static Map<String, Map<String, Object>> lastLogs() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : Db.queryAll(
                "SELECT l.slug, l.action, l.title, l.created_by_name, l.created_at FROM blog_article_log l "
                        + "JOIN (SELECT slug, MAX(id) AS mid FROM blog_article_log GROUP BY slug) m ON m.mid=l.id")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("action", String.valueOf(r.get("action")));
            one.put("title", String.valueOf(r.get("title")));
            one.put("by", String.valueOf(r.get("created_by_name")));
            one.put("at", r.get("created_at") == null ? "" : String.valueOf(r.get("created_at")));
            out.put(String.valueOf(r.get("slug")), one);
        }
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static long num(Object v) { return v instanceof Number n ? n.longValue() : 0L; }
}
