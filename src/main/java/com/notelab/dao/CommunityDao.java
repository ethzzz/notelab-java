package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * C 端社区数据访问（PRD-P9 M1）：cm_boards / cm_posts / cm_comments。
 *
 * <p>为什么走裸 JDBC 而不是 MyBatis-Plus 实体：本模块的查询全部是**列表投影 + JOIN 作者名**，
 * 建实体等于为每张表加一个 model + mapper，收益只有「少写 SQL」，却多出三层要同步的字段。
 * 现有 {@code Db.queryAll} 已保证「LinkedHashMap + 列标签小写 + datetime 格式化」，
 * 正好是前端契约要的形状（与 {@code CUserDao} 的 RowUtil 出口等价）。
 *
 * <p>⚠️ **所有读都显式带 status**：软删是唯一删除方式，漏掉 {@code status} 条件就会
 * 把已删内容漏回列表。这是本类最容易写错的地方。
 *
 * <p>⚠️ **计数一致性**：{@code comment_count} / {@code last_reply_at} 是冗余列（列表排序要用），
 * 与回复插入**必须同一事务**，否则并发下会出现「有回复但列表显示 0 条」。
 * 点赞/收藏（cm_reactions）M2 才进来，其幂等由主键保证，不需要额外事务。
 */
public final class CommunityDao {

    private CommunityDao() {}

    /** 作者显示名：昵称优先，回落用户名，账号已删则「已注销」（LEFT JOIN 后可能为 null） */
    private static final String AUTHOR_NAME =
            "COALESCE(NULLIF(u.nickname, ''), u.username, '已注销') AS author_name";

    /** 列表排序键：置顶永远第一，其次按最后回复时间（无回复回落发帖时间） */
    private static final String ORDER_LATEST =
            "ORDER BY p.is_pinned DESC, COALESCE(p.last_reply_at, p.created_at) DESC, p.id DESC";

    // ================= 板块 =================

    /** 板块列表（含有效帖子数）；归档板块也返回，前端置灰 */
    public static List<Map<String, Object>> listBoards() {
        return Db.queryAll("""
                SELECT b.id, b.slug, b.name, b.description, b.icon, b.sort_no, b.status,
                       (SELECT COUNT(*) FROM cm_posts p
                         WHERE p.board_id = b.id AND p.status = 'normal') AS post_count
                  FROM cm_boards b
                 ORDER BY b.sort_no, b.id""");
    }

    public static Map<String, Object> getBoardBySlug(String slug) {
        return Db.queryOne("""
                SELECT id, slug, name, description, icon, sort_no, status
                  FROM cm_boards WHERE slug = ?""", slug);
    }

    // ================= 帖子 =================

    /**
     * 帖子列表分页。{@code boardId<=0} 表示全站（不限板块）。
     *
     * <p>M1 只有一种排序（置顶 + 最后回复倒序）。热门排序要时间衰减公式，属 M3，
     * 现在加进来会让人误以为已按热度排好 —— 参数先不暴露，Controller 只认 latest。
     */
    public static List<Map<String, Object>> listPosts(long boardId, int limit, long offset) {
        String where = "p.status = 'normal'" + (boardId > 0 ? " AND p.board_id = ?" : "");
        String sql = """
                SELECT p.id, p.board_id, p.title, p.link_type, p.link_key, p.status,
                       p.is_pinned, p.is_featured, p.view_count, p.comment_count, p.like_count,
                       p.last_reply_at, p.created_at,
                       p.author_id, %s, b.slug AS board_slug, b.name AS board_name
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                 WHERE %s
                %s
                 LIMIT ? OFFSET ?""".formatted(AUTHOR_NAME, where, ORDER_LATEST);
        return boardId > 0
                ? Db.queryAll(sql, boardId, limit, offset)
                : Db.queryAll(sql, limit, offset);
    }

    public static long countPosts(long boardId) {
        Map<String, Object> r = boardId > 0
                ? Db.queryOne("SELECT COUNT(*) AS n FROM cm_posts WHERE status='normal' AND board_id=?", boardId)
                : Db.queryOne("SELECT COUNT(*) AS n FROM cm_posts WHERE status='normal'");
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /** 帖子详情（含正文与作者名）。软删/隐藏的帖子**也返回**，由 Controller 决定是否 404。 */
    public static Map<String, Object> getPost(long id) {
        return Db.queryOne("""
                SELECT p.id, p.board_id, p.author_id, p.title, p.content_md,
                       p.link_type, p.link_key, p.status, p.is_pinned, p.is_featured,
                       p.view_count, p.comment_count, p.like_count, p.created_at, p.updated_at,
                       %s, b.slug AS board_slug, b.name AS board_name
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                 WHERE p.id = ?""".formatted(AUTHOR_NAME), id);
    }

    /** 按站内对象反查讨论帖（link_type + link_key）。找不到返回 null。 */
    public static Map<String, Object> getPostByLink(String linkType, String linkKey) {
        return Db.queryOne("""
                SELECT p.id, p.title, p.comment_count, b.slug AS board_slug
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                 WHERE p.link_type = ? AND p.link_key = ? AND p.status = 'normal'
                 ORDER BY p.id LIMIT 1""", linkType, linkKey);
    }

    /** 发帖，返回新 id。 */
    public static long createPost(long boardId, long authorId, String title,
                                  String contentMd, String contentText,
                                  String linkType, String linkKey) {
        return Db.execInsert("""
                INSERT INTO cm_posts (board_id, author_id, title, content_md, content_text,
                                      link_type, link_key, last_reply_at)
                VALUES (?,?,?,?,?,?,?, NULL)""",
                boardId, authorId, title, contentMd, contentText, linkType, linkKey);
    }

    /**
     * 软删帖子（**仅作者本人**，author_id 写进 WHERE 而不是先查后判 —— 少一次往返且无竞态）。
     *
     * @return 受影响行数：0 表示「不是你的帖子」或「已删除」，由 Controller 区分 403/404
     */
    public static int softDeletePost(long id, long authorId) {
        return Db.execCount(
                "UPDATE cm_posts SET status='deleted' WHERE id=? AND author_id=? AND status<>'deleted'",
                id, authorId);
    }

    /** 浏览数 +1（不精确，允许并发丢失；Controller 侧按 IP+帖去重 1 小时） */
    public static void increaseView(long id) {
        Db.exec("UPDATE cm_posts SET view_count = view_count + 1 WHERE id = ?", id);
    }

    // ================= 回复 =================

    /**
     * 回复列表分页（时间正序）。{@code parent_id>0} 的行前端渲染成「回复 @某人」引用块，
     * 被引用者的名字由 {@code parent_author_name} 带出。
     *
     * <p>⚠️ 已删回复**仍然返回**（{@code status='deleted'}），前端渲染「该回复已删除」。
     * 直接过滤掉会让楼中楼的 parent_id 指向空气，看起来像串楼。
     */
    public static List<Map<String, Object>> listComments(long postId, int limit, long offset) {
        return Db.queryAll("""
                SELECT c.id, c.post_id, c.author_id, c.parent_id, c.content_md, c.status,
                       c.like_count, c.created_at,
                       %s,
                       pc.author_id AS parent_author_id,
                       COALESCE(NULLIF(pu.nickname, ''), pu.username, '已注销') AS parent_author_name
                  FROM cm_comments c
                  LEFT JOIN c_users u  ON u.id  = c.author_id
                  LEFT JOIN cm_comments pc ON pc.id = c.parent_id
                  LEFT JOIN c_users pu ON pu.id = pc.author_id
                 WHERE c.post_id = ?
                 ORDER BY c.created_at, c.id
                 LIMIT ? OFFSET ?""".formatted(AUTHOR_NAME), postId, limit, offset);
    }

    public static long countComments(long postId) {
        Map<String, Object> r = Db.queryOne("SELECT COUNT(*) AS n FROM cm_comments WHERE post_id=?", postId);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /**
     * 发回复 + 同步帖子的冗余计数，**同一事务**。
     *
     * <p>为什么必须一个事务：{@code comment_count} 用于列表显示与排序，若插入成功后更新失败
     * （或反过来），列表会长期显示错误的条数，而两边都没有自愈路径 —— 这种"看起来正常"的
     * 数据漂移最难被用户报告。M1 只有 5 个用户也能撞上：双击提交两次就是并发。
     *
     * @return 新回复 id
     */
    public static long createComment(long postId, long authorId, long parentId,
                                     String contentMd, String contentText) {
        Connection c = null;
        try {
            c = Db.conn();
            c.setAutoCommit(false);
            long commentId;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO cm_comments (post_id, author_id, parent_id, content_md, content_text)
                    VALUES (?,?,?,?,?)""", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, postId);
                ps.setLong(2, authorId);
                ps.setLong(3, parentId);
                ps.setString(4, contentMd);
                ps.setString(5, contentText);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    commentId = rs.next() ? rs.getLong(1) : -1;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cm_posts SET comment_count = comment_count + 1, last_reply_at = NOW(), last_reply_uid = ? WHERE id = ?")) {
                ps.setLong(1, authorId);
                ps.setLong(2, postId);
                ps.executeUpdate();
            }
            c.commit();
            return commentId;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new RuntimeException(e);
        } finally {
            closeQuietly(c);
        }
    }

    /** 软删回复（仅作者本人）+ 帖子 comment_count -1，同一事务。 */
    public static int softDeleteComment(long id, long authorId) {
        Connection c = null;
        try {
            c = Db.conn();
            c.setAutoCommit(false);
            long postId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT post_id FROM cm_comments WHERE id=? AND author_id=? AND status<>'deleted'")) {
                ps.setLong(1, id);
                ps.setLong(2, authorId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {   // 不是本人或已删：不进入后续更新
                        c.rollback();
                        return 0;
                    }
                    postId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE cm_comments SET status='deleted' WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            // GREATEST(...,0) 兜底：历史漂移时宁可停在 0，也不要出现 -1
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE cm_posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id=?")) {
                ps.setLong(1, postId);
                ps.executeUpdate();
            }
            c.commit();
            return 1;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new RuntimeException(e);
        } finally {
            closeQuietly(c);
        }
    }

    /** 回复是否存在且属于该帖（校验 parent_id 不能跨帖串楼）。 */
    public static boolean commentBelongsToPost(long commentId, long postId) {
        Map<String, Object> r = Db.queryOne(
                "SELECT id FROM cm_comments WHERE id=? AND post_id=?", commentId, postId);
        return r != null;
    }

    // ================= 内部工具 =================

    private static void rollbackQuietly(Connection c) {
        if (c == null) return;
        try { c.rollback(); } catch (SQLException ignored) { /* 已被主异常覆盖 */ }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try { c.setAutoCommit(true); c.close(); } catch (SQLException ignored) { /* 归还池失败不改变结果 */ }
    }

    /** 超管对账用：按 cm_comments 实际行数重算 comment_count（PRD-P9 §4.9 的兜底） */
    public static int recountAll() {
        return Db.execCount("""
                UPDATE cm_posts p SET p.comment_count =
                  (SELECT COUNT(*) FROM cm_comments c
                    WHERE c.post_id = p.id AND c.status <> 'deleted')""");
    }
}
