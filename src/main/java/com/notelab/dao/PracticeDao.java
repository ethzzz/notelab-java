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
 * <p>字段分三组，别混：
 * <ul>
 *   <li><b>展示字段</b> title / type —— 页面上直接看的那两个；</li>
 *   <li><b>发布元数据</b> module / chapter / orderNo / slugName / difficulty / tags / description ——
 *       对齐博客 {@code src/content/solutions} 的 frontmatter，由 {@link Fields} 一起传；</li>
 *   <li><b>发布态</b> published_at / published_slug / published_commit —— **只由发布动作写**，
 *       {@link #updateFields} 刻意不碰它们（否则用户改个标题就把「发过没」的记录抹了）。</li>
 * </ul>
 *
 * <p>⚠️ <b>列表查询刻意不返回 {@code content}</b>：正文是 LONGTEXT，一页 20 条整包吐出来纯属浪费
 * （列表页一个字都不显示）。要正文必须走 {@link #getRecord(long)} 单条读。
 */
public final class PracticeDao {

    private PracticeDao() {}

    /** 列表默认/上限条数（Controller 用它 clamp 前端传的 size） */
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    /**
     * 一条记录的「非内容数据」（正文之外的字段），新建与修改共用。
     *
     * <p>打包成一个 record 而不是九个位置参数：DAO 的 SQL 是**按位置绑定**的，
     * 调用处把 module 和 chapter 传反了编译一样通过，只有发到博客上才会看出「分组不对」。
     */
    public record Fields(String title, String type, String module, String chapter, int orderNo,
                         String slugName, String difficulty, String tags, String description) {
        /** 只有 title/type 有意义时的快捷构造（其余取默认值） */
        public static Fields of(String title, String type) {
            return new Fields(title, type, "", "", 0, "", "中等", "", "");
        }
    }

    // ==================== 写入 ====================

    /** 新建一条记录（正文留空，进编辑页后再写）。@return 新记录 id —— 前端要靠它跳到二级页面 */
    public static long insertRecord(Fields f, String content, Long userId, String userName) {
        return Db.execInsert(
                "INSERT INTO practice_record (title, type, module, chapter, order_no, slug_name, "
                        + "difficulty, tags, description, content, created_by, created_by_name, "
                        + "updated_by, updated_by_name) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                f.title(), f.type(), f.module(), f.chapter(), f.orderNo(), f.slugName(),
                f.difficulty(), f.tags(), f.description(), content == null ? "" : content,
                userId, nn(userName), userId, nn(userName));
    }

    /**
     * 只改「非内容数据」（标题 / 类型 / 发布元数据）。正文与**发布态**一个字都不碰 ——
     * 这正是编辑页把两类数据分开保存的落点：用户在顶部改个标题不该把下面正在写的正文一起提交。
     *
     * <p>⚠️ 刻意**不在 UPDATE 里写 updated_at**：列上有 {@code ON UPDATE CURRENT_TIMESTAMP}，
     * 手工赋值会把它钉死成应用侧的时间，与 DB 时钟不一致。
     *
     * @return 受影响行数（0 = 记录不存在）
     */
    public static int updateFields(long id, Fields f, Long userId, String userName) {
        return Db.execCount(
                "UPDATE practice_record SET title=?, type=?, module=?, chapter=?, order_no=?, "
                        + "slug_name=?, difficulty=?, tags=?, description=?, "
                        + "updated_by=?, updated_by_name=? WHERE id=?",
                f.title(), f.type(), f.module(), f.chapter(), f.orderNo(), f.slugName(),
                f.difficulty(), f.tags(), f.description(), userId, nn(userName), id);
    }

    /** 只改「内容数据」（Markdown 正文）。@return 受影响行数（0 = 记录不存在） */
    public static int updateContent(long id, String content, Long userId, String userName) {
        return Db.execCount(
                "UPDATE practice_record SET content=?, updated_by=?, updated_by_name=? WHERE id=?",
                content == null ? "" : content, userId, nn(userName), id);
    }

    /**
     * 回写发布态（发布成功后调用）。
     *
     * <p>发布态**不复用 updated_at**：「最后一次编辑草稿」与「最后一次推到博客」是两件事，
     * 合成一个时间戳后，改完草稿没发也会显示成刚发过。
     */
    public static int markPublished(long id, String slug, String commit) {
        return Db.execCount(
                "UPDATE practice_record SET published_at=CURRENT_TIMESTAMP, published_slug=?, "
                        + "published_commit=? WHERE id=?",
                nn(slug), nn(commit), id);
    }

    /** 按 id 删除。返回删除行数（0 = 已不存在，属正常，不该报 404） */
    public static int deleteRecord(long id) {
        return Db.execCount("DELETE FROM practice_record WHERE id=?", id);
    }

    // ==================== 查询 ====================

    /** 单条记录（**含正文**）。不存在返回 null。 */
    public static Map<String, Object> getRecord(long id) {
        Map<String, Object> r = Db.queryOne("SELECT " + COLS_ALL + " FROM practice_record WHERE id=?", id);
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
        String sql = "SELECT " + COLS_LIST + " FROM practice_record " + c.where
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

    // ==================== 公开侧（匿名只读，只认已发布的）====================

    /**
     * 已发布的全部条目（**不含正文**），按模块 → 篇章 → 题号排序。
     *
     * <p>⚠️ 判据是 {@code published_slug <> ''} 而不是某个 boolean 列：
     * slug 既是「发布过」的标记，也是对外 URL 的一段 —— 两件事用同一份数据就不可能不一致。
     */
    public static List<Map<String, Object>> listPublished() {
        List<Map<String, Object>> rows = Db.queryAll(
                "SELECT " + COLS_LIST + " FROM practice_record WHERE published_slug <> '' "
                        + "ORDER BY module, chapter, order_no, id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(rowOf(r, false));
        return out;
    }

    /** 按对外 slug 取一条（**含正文**）。不存在返回 null。 */
    public static Map<String, Object> getByPublishedSlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        Map<String, Object> r = Db.queryOne(
                "SELECT " + COLS_ALL + " FROM practice_record WHERE published_slug=?", slug);
        return r == null ? null : rowOf(r, true);
    }

    /**
     * 同模块 + 同篇章的已发布条目（按题号排）—— 详情页的「上一篇 / 下一篇」用。
     *
     * <p>为什么不按 relatedPost 分组：记录里**没有**这个字段（它是发布时由 module + 篇章号
     * 推导出来的，见 PracticeController）。同一对 (module, chapter) 恰好等价于同一篇原文，
     * 所以这里直接按这两列分组，不必再把它落库成第三份数据。
     */
    public static List<Map<String, Object>> listPublishedChapter(String module, String chapter) {
        if (module == null || module.isBlank() || chapter == null || chapter.isBlank()) return List.of();
        List<Map<String, Object>> rows = Db.queryAll(
                "SELECT " + COLS_LIST + " FROM practice_record "
                        + "WHERE published_slug <> '' AND module=? AND chapter=? ORDER BY order_no, id",
                module, chapter);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(rowOf(r, false));
        return out;
    }

    // ==================== 内部 ====================

    /** 列表列（不含正文） */
    private static final String COLS_LIST =
            "id, title, type, module, chapter, order_no, slug_name, difficulty, tags, description, "
                    + "published_at, published_slug, published_commit, "
                    + "created_by, created_by_name, updated_by, updated_by_name, created_at, updated_at";

    /** 详情列（在 id 之后插入 content） */
    private static final String COLS_ALL = COLS_LIST.replace("id,", "id, content,");

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
        // 发布元数据
        m.put("module", str(r.get("module")));
        m.put("chapter", str(r.get("chapter")));
        m.put("order_no", num(r.get("order_no")));
        m.put("slug_name", str(r.get("slug_name")));
        m.put("difficulty", str(r.get("difficulty")));
        m.put("tags", str(r.get("tags")));
        m.put("description", str(r.get("description")));
        // 发布态
        m.put("published_at", str(r.get("published_at")));
        m.put("published_slug", str(r.get("published_slug")));
        m.put("published_commit", str(r.get("published_commit")));
        // 审计
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
        return o == null ? "" : o.toString();
    }
}
