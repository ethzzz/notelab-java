package com.notelab.dao;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 练习实践 · 插图域 DAO：{@code practice_image} 单表读写。
 *
 * <p>图片存库（而不是磁盘）：放盘要额外改 nginx location、让部署脚本保证目录存在、再单独处理备份，
 * 三处都会忘；落库则 {@code mysqldump}（cron 0 3 那条）天然一起走，也不需要动公网入口。
 *
 * <p>⚠️ <b>读走 {@link #getImage(long)} 单独一次查询</b>，绝不在列表 SQL 里 JOIN 出来 ——
 * LONGTEXT/MEDIUMBLOB 一旦混进列表查询，一页记录就会把整张表拖进内存。
 *
 * <p>⚠️ 只增不删：删记录**不**级联删图（同一张图可能被多条记录引用），孤儿图暂不回收。
 */
public final class PracticeImageDao {

    private PracticeImageDao() {}

    public static long insertImage(String mime, int sizeBytes, byte[] data, Long userId) {
        return Db.execInsert(
                "INSERT INTO practice_image (mime, size_bytes, data, created_by) VALUES (?,?,?,?)",
                mime, sizeBytes, data, userId);
    }

    /** 单张图（含字节）。不存在返回 null —— Controller 据此回 404。 */
    public static Map<String, Object> getImage(long id) {
        return mapOf(Db.queryOne("SELECT " + COLS + " FROM practice_image WHERE id=?", id));
    }

    /**
     * 按**公开名**取图（匿名接口用）。不存在返回 null。
     *
     * <p>⚠️ 公开侧一律走这个方法，不要用 {@link #getImage(long)}：id 是自增的，
     * 顺着枚举就能把**未发布**草稿里的图也拉走。public_name 只有发布动作才会写。
     */
    public static Map<String, Object> getByPublicName(String name) {
        if (name == null || name.isBlank()) return null;
        return mapOf(Db.queryOne("SELECT " + COLS + " FROM practice_image WHERE public_name=?", name));
    }

    /**
     * 发布时给插图打公开名（形如 {@code algo-01-ex01-two-sum-ii-1.png}）。
     *
     * <p>这个名字是「这张图允许匿名访问」的唯一凭据。改 slug 重新发布会写下新名字，
     * **旧名字刻意不清**（老链接不失效，代价是一点存储）。
     */
    public static int setPublicName(long id, String name) {
        return Db.execCount("UPDATE practice_image SET public_name=? WHERE id=?",
                name == null ? "" : name, id);
    }

    /** 该插图当前的公开名（空串 = 从未发布过） */
    public static String publicNameOf(long id) {
        Map<String, Object> r = Db.queryOne("SELECT public_name FROM practice_image WHERE id=?", id);
        return r == null ? "" : str(r.get("public_name"));
    }

    public static int deleteImage(long id) {
        return Db.execCount("DELETE FROM practice_image WHERE id=?", id);
    }

    /**
     * 已存图张数与总字节数。
     *
     * <p>为什么要：图片直接进库，**体积增长是看不见的**（不像文件系统 ls 一下就有数）。
     * 页面上把这两个数摊开，撑爆之前至少能看见趋势。
     */
    public static Map<String, Object> summary() {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS c, COALESCE(SUM(size_bytes),0) AS b FROM practice_image");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", r == null ? 0L : num(r.get("c")));
        out.put("bytes", r == null ? 0L : num(r.get("b")));
        return out;
    }

    private static final String COLS = "id, mime, size_bytes, data";

    private static Map<String, Object> mapOf(Map<String, Object> r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", num(r.get("id")));
        m.put("mime", str(r.get("mime")));
        m.put("size_bytes", num(r.get("size_bytes")));
        Object d = r.get("data");
        m.put("data", d instanceof byte[] b ? b : new byte[0]);
        return m;
    }

    private static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0L; }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
