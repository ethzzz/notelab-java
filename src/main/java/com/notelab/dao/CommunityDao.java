package com.notelab.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 作者显示名：昵称优先，回落用户名，账号已删则「已注销」。
     *
     * <p>⚠️ {@code author_id = 0} 是**系统种子账号**（M1 从博客导入的「文章讨论区」）：
     * 它的作者不是任何人，显示成「已注销」会让人以为作者跑路了，所以单独给个 NoteLab。
     */
    private static final String AUTHOR_NAME =
            "CASE WHEN p.author_id = 0 THEN 'NoteLab' "
            + "ELSE COALESCE(NULLIF(u.nickname, ''), u.username, '已注销') END AS author_name";

    /**
     * 列表可见性：**「文章讨论区」这种空壳不进列表，除非已经有人讨论**。
     *
     * <p>为什么：M1 会把博客文章导入成「讨论区」（{@code author_id=0, link_type='blog'}），
     * 它们正文为空、回复为 0。若全量铺进列表，社区首页会变成几百行「0 回复」的空壳，
     * 比空列表更劝退。
     *
     * <p>⚠️ 判定依据是**内容类型**，不是作者：
     * <ul>
     *   <li>用户发的帖（{@code author_id<>0}）→ 显示；</li>
     *   <li>管理员主动发布的官方主题（{@code link_type='none'}）→ **显示**；</li>
     *   <li>文章讨论区（{@code link_type='blog'}）→ 只有被回复过才显示。</li>
     * </ul>
     * 第一版只写了 {@code author_id<>0 OR comment_count>0}，结果把管理员发的官方主题也一起藏了
     * （2026-10-23 M2 验收第 9 项发现）—— 官方内容恰恰是最该露出来的。
     */
    private static final String VISIBLE =
            "p.status = 'normal' AND (p.author_id <> 0 OR p.link_type = 'none' OR p.comment_count > 0)";

    /** 列表排序键：置顶永远第一，其次按最后回复时间（无回复回落发帖时间） */
    private static final String ORDER_LATEST =
            "ORDER BY p.is_pinned DESC, COALESCE(p.last_reply_at, p.created_at) DESC, p.id DESC";

    // ================= 板块 =================

    /**
     * 板块列表（含**与列表同口径**的帖子数）。
     *
     * <p>⚠️ 这里的 post_count 必须用 {@link #VISIBLE}，不能用「未删除」：
     * 否则会出现「板块上写 234 个主题、点进去只有 1 条」——用户会以为内容丢了。
     * 管理端要的是另一个数（含种子与隐藏），见 {@link #listBoardsForAdmin()}。
     */
    public static List<Map<String, Object>> listBoards() {
        return Db.queryAll("""
                SELECT b.id, b.slug, b.name, b.description, b.icon, b.sort_no, b.status,
                       (SELECT COUNT(*) FROM cm_posts p
                         WHERE p.board_id = b.id AND %s) AS post_count
                  FROM cm_boards b
                 ORDER BY b.sort_no, b.id""".formatted(VISIBLE));
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
        String where = VISIBLE + (boardId > 0 ? " AND p.board_id = ?" : "");
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
        // ⚠️ 必须与 listPosts 的 VISIBLE 完全一致，否则「共 N 个主题」与实际能翻到的条数不符
        Map<String, Object> r = boardId > 0
                ? Db.queryOne("SELECT COUNT(*) AS n FROM cm_posts p WHERE " + VISIBLE + " AND p.board_id = ?", boardId)
                : Db.queryOne("SELECT COUNT(*) AS n FROM cm_posts p WHERE " + VISIBLE);
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
        // ⚠️ 这里**不能复用 {@link #AUTHOR_NAME}**：那个常量带 `p.` 前缀（帖子表的别名），
        //    而本查询的评论表别名是 `c` —— 直接拼进去会得到
        //    "Unknown column 'p.author_id' in 'field list'"，接口 500。
        //    2026-10-23 M2 给 AUTHOR_NAME 加 CASE 时就这么打坏过一次（M1 能跑、M2 报 500）。
        //    评论作者不可能是 0（系统身份不发回复），所以不需要 NoteLab 的分支。
        return Db.queryAll("""
                SELECT c.id, c.post_id, c.author_id, c.parent_id, c.content_md, c.status,
                       c.like_count, c.created_at,
                       COALESCE(NULLIF(u.nickname, ''), u.username, '已注销') AS author_name,
                       pc.author_id AS parent_author_id,
                       COALESCE(NULLIF(pu.nickname, ''), pu.username, '已注销') AS parent_author_name
                  FROM cm_comments c
                  LEFT JOIN c_users u  ON u.id  = c.author_id
                  LEFT JOIN cm_comments pc ON pc.id = c.parent_id
                  LEFT JOIN c_users pu ON pu.id = pc.author_id
                 WHERE c.post_id = ?
                 ORDER BY c.created_at, c.id
                 LIMIT ? OFFSET ?""", postId, limit, offset);
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

    /** 被回复楼层的作者 id（通知用）；楼层不存在返回 null */
    public static Long commentAuthor(long commentId) {
        Map<String, Object> r = Db.queryOne("SELECT author_id FROM cm_comments WHERE id=?", commentId);
        return r == null ? null : ((Number) r.get("author_id")).longValue();
    }

    // ================= 管理端（B 端 /api/community-admin） =================
    //
    // ⚠️ 与上面的 C 端读方法**刻意分开**：管理端要看见全部状态（含种子帖、隐藏、已删），
    // 而且不受 VISIBLE 规则约束。合并成一个方法再加 `boolean admin` 参数会让
    // 「这条 SQL 到底给谁看」变成运行时才知道，是最容易漏权限的写法。

    /** 板块列表（管理用）：含归档，帖子数按「未删」统计 */
    public static List<Map<String, Object>> listBoardsForAdmin() {
        return Db.queryAll("""
                SELECT b.id, b.slug, b.name, b.description, b.icon, b.sort_no, b.status, b.created_at,
                       (SELECT COUNT(*) FROM cm_posts p
                         WHERE p.board_id = b.id AND p.status <> 'deleted') AS post_count
                  FROM cm_boards b
                 ORDER BY b.sort_no, b.id""");
    }

    public static long createBoard(String slug, String name, String description, String icon, int sortNo) {
        return Db.execInsert(
                "INSERT INTO cm_boards (slug,name,description,icon,sort_no) VALUES (?,?,?,?,?)",
                slug, name, description, icon, sortNo);
    }

    /** 板块部分更新：null = 不改。slug 允许改（会改 C 端 URL），由 Controller 提示风险 */
    public static void updateBoard(long id, String slug, String name, String description,
                                   String icon, Integer sortNo, String status) {
        Db.exec("""
                UPDATE cm_boards SET
                  slug        = COALESCE(?, slug),
                  name        = COALESCE(?, name),
                  description = COALESCE(?, description),
                  icon        = COALESCE(?, icon),
                  sort_no     = COALESCE(?, sort_no),
                  status      = COALESCE(?, status)
                WHERE id = ?""", slug, name, description, icon, sortNo, status, id);
    }

    public static int deleteBoard(long id) {
        return Db.execCount("DELETE FROM cm_boards WHERE id = ?", id);
    }

    /** 板块下未删除的帖子数（删板块前的拦截依据：有帖子就不许删，避免内容变孤儿） */
    public static long countPostsInBoard(long boardId) {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM cm_posts WHERE board_id = ? AND status <> 'deleted'", boardId);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /**
     * 管理端帖子列表：**不套 VISIBLE**，含种子帖与已删/已隐藏；支持关键词与状态筛选。
     *
     * @param status 空 = 全部；否则精确匹配（normal/closed/hidden/deleted）
     */
    public static List<Map<String, Object>> adminListPosts(long boardId, String q, String status,
                                                           int limit, long offset) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (boardId > 0) { where.append(" AND p.board_id = ?"); args.add(boardId); }
        if (status != null && !status.isBlank()) { where.append(" AND p.status = ?"); args.add(status); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (p.title LIKE ? OR p.content_text LIKE ?)");
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        String sql = """
                SELECT p.id, p.board_id, p.title, p.status, p.author_id, p.link_type, p.link_key,
                       p.is_pinned, p.is_featured, p.view_count, p.comment_count, p.like_count,
                       p.last_reply_at, p.created_at, p.updated_at,
                       %s, b.name AS board_name, b.slug AS board_slug
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                """ .formatted(AUTHOR_NAME) + where + " ORDER BY p.is_pinned DESC, p.id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return Db.queryAll(sql, args.toArray());
    }

    public static long adminCountPosts(long boardId, String q, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (boardId > 0) { where.append(" AND p.board_id = ?"); args.add(boardId); }
        if (status != null && !status.isBlank()) { where.append(" AND p.status = ?"); args.add(status); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (p.title LIKE ? OR p.content_text LIKE ?)");
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        Map<String, Object> r = Db.queryOne("SELECT COUNT(*) AS n FROM cm_posts p" + where, args.toArray());
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /** 管理端详情：任何状态都能读（含正文），用于编辑与排障 */
    public static Map<String, Object> adminGetPost(long id) {
        return Db.queryOne("""
                SELECT p.id, p.board_id, p.author_id, p.title, p.content_md, p.content_text,
                       p.link_type, p.link_key, p.status, p.is_pinned, p.is_featured,
                       p.view_count, p.comment_count, p.like_count, p.created_at, p.updated_at,
                       %s, b.name AS board_name, b.slug AS board_slug
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                 WHERE p.id = ?""".formatted(AUTHOR_NAME), id);
    }

    /** 管理端编辑：标题/正文/板块（link_* 不允许改，改了会让「讨论入口」指向另一条内容） */
    public static void adminUpdatePost(long id, long boardId, String title, String contentMd, String contentText) {
        Db.exec("UPDATE cm_posts SET board_id=?, title=?, content_md=?, content_text=? WHERE id=?",
                boardId, title, contentMd, contentText, id);
    }

    /**
     * 置顶 / 精华 / 锁帖。三个参数为 null 表示「不改」。
     *
     * <p>⚠️ 锁帖写的是 {@code status}：closed 与 normal 互转。这个接口**不碰** hidden/deleted
     * —— 否则「取消锁帖」会把一条被折叠的帖子悄悄恢复正常，审核痕迹就丢了。
     */
    public static void setPostFlags(long id, Integer pinned, Integer featured, Boolean closed) {
        if (pinned != null || featured != null) {
            Db.exec("UPDATE cm_posts SET is_pinned=COALESCE(?,is_pinned), is_featured=COALESCE(?,is_featured) WHERE id=?",
                    pinned, featured, id);
        }
        if (closed != null) {
            Db.exec("UPDATE cm_posts SET status=? WHERE id=? AND status IN ('normal','closed')",
                    closed ? "closed" : "normal", id);
        }
    }

    /** 管理员软删（不限作者）。与 C 端 `softDeletePost` 的区别只有「不校验 author_id」。 */
    public static int adminSoftDeletePost(long id) {
        return Db.execCount("UPDATE cm_posts SET status='deleted' WHERE id=? AND status<>'deleted'", id);
    }

    /**
     * 已存在的种子帖：link_key → 当前标题（导入时据此区分「跳过」与「标题需同步」）。
     *
     * <p>⚠️ **刻意不过滤 status**：管理员软删过的讨论区也要算「存在」，
     * 否则每次导入都会把被删掉的讨论区重新建出来 —— 管理员删了它是有意的。
     */
    public static Map<String, String> seedTitles() {
        Map<String, String> out = new java.util.HashMap<>();
        for (Map<String, Object> r : Db.queryAll(
                "SELECT link_key, title FROM cm_posts WHERE link_type='blog'")) {
            Object k = r.get("link_key");
            if (k == null) continue;
            out.put(String.valueOf(k), String.valueOf(r.get("title")));
        }
        return out;
    }

    /** 文章改了标题 → 同步种子帖标题。只动系统种子帖（用户自己发的关联帖标题是他们写的，不该被覆盖）。 */
    public static int updateSeedTitle(String linkKey, String title) {
        return Db.execCount(
                "UPDATE cm_posts SET title=? WHERE author_id=0 AND link_type='blog' AND link_key=?",
                title, linkKey);
    }

    /** 插入一条种子帖（官方身份 author_id=0，正文留空，等真人来讨论） */
    public static void insertSeedPost(long boardId, String title, String linkKey) {
        Db.exec("""
                INSERT INTO cm_posts (board_id, author_id, title, content_md, content_text, link_type, link_key)
                VALUES (?, 0, ?, '', '', 'blog', ?)""", boardId, title, linkKey);
    }

    /**
     * 删除「没有回复、且对应文章已不在 RSS 里」的系统种子帖。
     *
     * <p>⚠️ **不能简单写成「删掉所有空种子帖再重插」** —— 那样每次导入都会重建全部行，
     * 帖子 id 全变，用户已经打开的 {@code /community/p/<id>} 链接、以及别人分享的链接立刻失效。
     * 「幂等」不只是"最终状态一致"，还包括**不破坏稳定标识**。
     * 所以这里保留仍在 RSS 里的 key，只清理文章已被删掉的那些。
     */
    public static int deleteEmptySeedPostsExcept(java.util.Set<String> keepKeys) {
        if (keepKeys == null || keepKeys.isEmpty()) {
            return Db.execCount("DELETE FROM cm_posts WHERE author_id=0 AND link_type='blog' AND comment_count=0");
        }
        StringBuilder ph = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (String k : keepKeys) {
            if (ph.length() > 0) ph.append(',');
            ph.append('?');
            args.add(k);
        }
        return Db.execCount(
                "DELETE FROM cm_posts WHERE author_id=0 AND link_type='blog' AND comment_count=0 "
                + "AND link_key NOT IN (" + ph + ")", args.toArray());
    }

    // ================= 互动（M2b：点赞 / 收藏 / 社区资料） =================

    /** 目标类型 → 表名。**只在这两个常量之间选**，不拼用户输入（拼表名是最典型的注入面）。 */
    private static String reactionTable(String targetType) {
        return "comment".equals(targetType) ? "cm_comments" : "cm_posts";
    }

    /**
     * 切换点赞/收藏，返回 {@code {active, count}}。
     *
     * <p>三步都在一个事务里：删（或插）反应行 → 更新目标表的冗余计数 → 读回计数。
     *
     * <p>⚠️ 计数只由 {@code kind='like'} 驱动；收藏不进 {@code like_count}
     * （收藏是私人的，不该出现在公开的热度里）。
     * <p>⚠️ 用 {@code INSERT IGNORE} 而不是普通 INSERT：同一用户双击/并发提交时，
     * 第二个请求会撞主键 —— 普通 INSERT 会抛异常把整个事务回滚，用户看到"操作失败"；
     * IGNORE 则返回 0 行受影响，据此**不再重复加计数**。
     */
    public static Map<String, Object> toggleReaction(long userId, String targetType, long targetId, String kind) {
        String table = reactionTable(targetType);
        Connection c = null;
        try {
            c = Db.conn();
            c.setAutoCommit(false);

            boolean active;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM cm_reactions WHERE user_id=? AND target_type=? AND target_id=? AND kind=?")) {
                ps.setLong(1, userId);
                ps.setString(2, targetType);
                ps.setLong(3, targetId);
                ps.setString(4, kind);
                active = ps.executeUpdate() == 0;   // 没删到 = 原本没有，要走新增
            }

            int delta = 0;
            if (active) {
                int ins;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT IGNORE INTO cm_reactions (user_id, target_type, target_id, kind) VALUES (?,?,?,?)")) {
                    ps.setLong(1, userId);
                    ps.setString(2, targetType);
                    ps.setLong(3, targetId);
                    ps.setString(4, kind);
                    ins = ps.executeUpdate();
                }
                if (ins == 1) delta = 1;   // 并发下本来就是「已点赞」→ 不动计数
            } else {
                delta = -1;
            }

            if ("like".equals(kind) && delta != 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + table + " SET like_count = GREATEST(like_count + ?, 0) WHERE id=?")) {
                    ps.setInt(1, delta);
                    ps.setLong(2, targetId);
                    ps.executeUpdate();
                }
            }

            long count;
            try (PreparedStatement ps = c.prepareStatement("SELECT like_count FROM " + table + " WHERE id=?")) {
                ps.setLong(1, targetId);
                try (ResultSet rs = ps.executeQuery()) {
                    count = rs.next() ? rs.getLong(1) : 0L;
                }
            }

            c.commit();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("active", active);
            out.put("count", count);
            return out;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new RuntimeException(e);
        } finally {
            closeQuietly(c);
        }
    }

    /** 当前用户对某个目标是否已点赞/收藏 */
    public static boolean isReacted(long userId, String targetType, long targetId, String kind) {
        return Db.queryOne(
                "SELECT 1 AS x FROM cm_reactions WHERE user_id=? AND target_type=? AND target_id=? AND kind=?",
                userId, targetType, targetId, kind) != null;
    }

    /**
     * 批量查「这批目标里我点赞了哪些」。
     *
     * <p>为什么必须批量：回复列表一页 50 条，逐条查就是 50 次往返。做成一次 IN 查询，
     * 前端拿到 id 集合直接标实心。
     */
    public static java.util.Set<Long> reactedIds(long userId, String targetType, String kind, List<Long> ids) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (ids == null || ids.isEmpty()) return out;
        StringBuilder ph = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(targetType);
        args.add(kind);
        for (Long id : ids) {
            if (ph.length() > 0) ph.append(',');
            ph.append('?');
            args.add(id);
        }
        for (Map<String, Object> r : Db.queryAll(
                "SELECT target_id FROM cm_reactions WHERE user_id=? AND target_type=? AND kind=? AND target_id IN (" + ph + ")",
                args.toArray())) {
            out.add(((Number) r.get("target_id")).longValue());
        }
        return out;
    }

    /** 我的收藏（按收藏时间倒序；已删除/隐藏的帖子不出现） */
    public static List<Map<String, Object>> listBookmarks(long userId, int limit, long offset) {
        return Db.queryAll("""
                SELECT p.id, p.board_id, p.title, p.link_type, p.link_key, p.status,
                       p.is_pinned, p.is_featured, p.view_count, p.comment_count, p.like_count,
                       p.last_reply_at, p.created_at,
                       p.author_id, %s, b.slug AS board_slug, b.name AS board_name,
                       r.created_at AS bookmarked_at
                  FROM cm_reactions r
                  JOIN cm_posts p ON p.id = r.target_id
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                 WHERE r.user_id = ? AND r.target_type = 'post' AND r.kind = 'bookmark'
                   AND p.status = 'normal'
                 ORDER BY r.created_at DESC
                 LIMIT ? OFFSET ?""".formatted(AUTHOR_NAME), userId, limit, offset);
    }

    public static long countBookmarks(long userId) {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM cm_reactions r JOIN cm_posts p ON p.id=r.target_id "
                + "WHERE r.user_id=? AND r.target_type='post' AND r.kind='bookmark' AND p.status='normal'", userId);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /** 社区资料（可能为 null：用户从没进过社区设置页） */
    public static Map<String, Object> getProfile(long userId) {
        return Db.queryOne(
                "SELECT user_id, bio, avatar_url, muted_until FROM cm_user_profile WHERE user_id=?", userId);
    }

    /** 保存社区资料（没有则建行）。空串是合法值 = 清空。 */
    public static void saveProfile(long userId, String bio, String avatarUrl) {
        Db.exec("""
                INSERT INTO cm_user_profile (user_id, bio, avatar_url) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE bio=VALUES(bio), avatar_url=VALUES(avatar_url)""",
                userId, bio, avatarUrl);
    }

    /**
     * 用户公开资料页的数据。
     *
     * <p>三个统计**实时 COUNT**，不做冗余列：5 个用户的规模下，这三次子查询走
     * {@code idx_author} 是微秒级；存冗余列反而要维护、会漂移（见 PRD-P9 §4.9）。
     */
    public static Map<String, Object> userPublic(long userId) {
        return Db.queryOne("""
                SELECT u.id, u.username,
                       COALESCE(NULLIF(u.nickname, ''), u.username) AS nickname,
                       u.created_at,
                       COALESCE(pf.bio, '') AS bio,
                       COALESCE(pf.avatar_url, '') AS avatar_url,
                       (SELECT COUNT(*) FROM cm_posts x WHERE x.author_id=u.id AND x.status='normal') AS post_count,
                       (SELECT COUNT(*) FROM cm_comments y WHERE y.author_id=u.id AND y.status<>'deleted') AS comment_count,
                       (SELECT COALESCE(SUM(z.like_count),0) FROM cm_posts z WHERE z.author_id=u.id AND z.status='normal') AS like_received
                  FROM c_users u
                  LEFT JOIN cm_user_profile pf ON pf.user_id = u.id
                 WHERE u.id = ? AND u.status = 'active'""", userId);
    }

    /**
     * 某用户的公开主题。
     *
     * <p>⚠️ **不套 {@link #VISIBLE}**：那条规则的作用是「别人发起的 0 回复讨论区不占信息流」，
     * 而这里查的是**这个人自己发的东西**——他自己发起的讨论（即使没人回）应该出现在他的主页上。
     */
    public static List<Map<String, Object>> listPostsByAuthor(long userId, int limit) {
        return Db.queryAll("""
                SELECT p.id, p.board_id, p.title, p.link_type, p.link_key, p.status,
                       p.is_pinned, p.is_featured, p.view_count, p.comment_count, p.like_count,
                       p.last_reply_at, p.created_at,
                       p.author_id, %s, b.slug AS board_slug, b.name AS board_name
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                 WHERE p.status = 'normal' AND p.author_id = ?
                %s
                 LIMIT ?""".formatted(AUTHOR_NAME, ORDER_LATEST), userId, limit);
    }

    // ================= 通知（M3） =================

    /**
     * 写一条通知。
     *
     * <p>⚠️ 调用方负责三件事（放这里会变成"三个 if 散落各处"）：
     * ① 不给自己发；② 不给 author_id=0（官方/种子帖没有真人收件人）发；
     * ③ 同一动作的多条候选收件人要先去重。
     */
    public static long createNotification(long userId, String kind, long actorId,
                                          long postId, long commentId, String excerpt) {
        return Db.execInsert("""
                INSERT INTO cm_notifications (user_id, kind, actor_id, post_id, comment_id, excerpt)
                VALUES (?,?,?,?,?,?)""", userId, kind, actorId, postId, commentId, excerpt);
    }

    /** 我的通知（含 actor 名与帖子标题；帖子被删时 post_title 为 null，前端仍可点进去看 404） */
    public static List<Map<String, Object>> listNotifications(long userId, int limit, long offset) {
        return Db.queryAll("""
                SELECT n.id, n.kind, n.actor_id, n.post_id, n.comment_id, n.excerpt,
                       n.is_read, n.created_at,
                       COALESCE(NULLIF(u.nickname, ''), u.username, '已注销') AS actor_name,
                       p.title AS post_title
                  FROM cm_notifications n
                  LEFT JOIN c_users u ON u.id = n.actor_id
                  LEFT JOIN cm_posts p ON p.id = n.post_id
                 WHERE n.user_id = ?
                 ORDER BY n.created_at DESC, n.id DESC
                 LIMIT ? OFFSET ?""", userId, limit, offset);
    }

    public static long countNotifications(long userId) {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM cm_notifications WHERE user_id = ?", userId);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
    }

    /** 未读数（Shell 红点用，30s 轮询一次；走 idx_user_unread 覆盖索引） */
    public static int countUnreadNotifications(long userId) {
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM cm_notifications WHERE user_id = ? AND is_read = 0", userId);
        return r == null ? 0 : ((Number) r.get("n")).intValue();
    }

    /**
     * 标记已读。{@code ids} 为空表示「全部标为已读」。
     *
     * <p>⚠️ 两个分支都必须带 {@code user_id = ?} —— 否则传别人的通知 id 就能改别人的已读态。
     */
    public static int markNotificationsRead(long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Db.execCount(
                    "UPDATE cm_notifications SET is_read=1 WHERE user_id=? AND is_read=0", userId);
        }
        StringBuilder ph = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        for (Long id : ids) {
            if (ph.length() > 0) ph.append(',');
            ph.append('?');
            args.add(id);
        }
        return Db.execCount(
                "UPDATE cm_notifications SET is_read=1 WHERE user_id=? AND id IN (" + ph + ")",
                args.toArray());
    }

    // ================= 搜索（M3） =================

    /**
     * 搜索标题 + 正文纯文本（{@code content_text} 是发帖时算好的去标记文本）。
     *
     * <p>用 LIKE 而不是 FULLTEXT：主题数量还很小，LIKE 已经够快，而 MySQL 的 ngram 分词器
     * 对中文短语的召回需要调参（默认 token 长度 2，短词搜不到），为这个规模的查询引调参不值。
     *
     * <p>⚠️ **刻意不套 {@link #VISIBLE}**（与信息流相反）：文章讨论区因为没有回复而不进信息流，
     * 但它**是真实可达的内容**（从文章页可以进来）。搜索的语义是「找到我知道存在的东西」，
     * 用户搜「Java」很可能就是想找某篇文章的讨论区去发起讨论 —— 过滤掉它等于告诉用户"没这篇"。
     * 代价是搜索结果里会出现一批空讨论区，所以下面按「有回复的排前面」排序。
     */
    public static List<Map<String, Object>> searchPosts(String q, int limit, long offset) {
        String kw = "%" + q.trim() + "%";
        return Db.queryAll("""
                SELECT p.id, p.board_id, p.title, p.link_type, p.link_key, p.status,
                       p.is_pinned, p.is_featured, p.view_count, p.comment_count, p.like_count,
                       p.last_reply_at, p.created_at,
                       p.author_id, %s, b.slug AS board_slug, b.name AS board_name
                  FROM cm_posts p
                  JOIN cm_boards b ON b.id = p.board_id
                  LEFT JOIN c_users u ON u.id = p.author_id
                 WHERE p.status = 'normal' AND (p.title LIKE ? OR p.content_text LIKE ?)
                 ORDER BY (p.comment_count > 0) DESC, p.is_pinned DESC,
                          COALESCE(p.last_reply_at, p.created_at) DESC, p.id DESC
                 LIMIT ? OFFSET ?""".formatted(AUTHOR_NAME), kw, kw, limit, offset);
    }

    /** 计数口径必须与 {@link #searchPosts} 一致（同样不套 VISIBLE），否则分页总数与结果对不上 */
    public static long countSearch(String q) {
        String kw = "%" + q.trim() + "%";
        Map<String, Object> r = Db.queryOne(
                "SELECT COUNT(*) AS n FROM cm_posts p WHERE p.status = 'normal'"
                + " AND (p.title LIKE ? OR p.content_text LIKE ?)", kw, kw);
        return r == null ? 0 : ((Number) r.get("n")).longValue();
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
