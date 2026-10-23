package com.notelab.controller;

import com.notelab.common.ClientIp;
import com.notelab.dao.CommunityDao;
import com.notelab.service.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C 端社区（PRD-P9 M1）：{@code /api/c/community} 下的板块 / 帖子 / 回复。
 *
 * <p><b>鉴权口径：全部接口都要求登录（内部模式，PRD-P9 §10.1 Q6）</b>。社区定位是
 * 「站内 5 人讨论区」，读也需登录 —— 否则内部对话就是公网可见、可被搜索引擎收录。
 * 想要「公开只读」只需把本类的读方法改成允许匿名，并把前缀从
 * {@code CPermGuard.C_MEMBER_PREFIXES} 挪到 {@code PermGuard.PUBLIC_PREFIXES}（②位）。
 *
 * <p>⚠️ 前缀已在 {@link com.notelab.common.CPermGuard} 的 {@code C_MEMBER_PREFIXES} 里豁免，
 * 不查用户组授权。删掉那条豁免 = 所有既有用户组 403（新增 C 端接口的默认行为），见该类注释。
 *
 * <p>M1 范围：板块列表 / 帖子列表 / 详情 / 发帖 / 回复 / 软删 / link 反查。
 * **不做**点赞、收藏、通知、搜索、编辑、版主（M2/M3/M4）。
 */
@RestController
@RequestMapping("/api/c/community")
public class CommunityController {

    /** 标题上限（cm_posts.title VARCHAR(120)） */
    private static final int TITLE_MAX = 120;
    /** 正文上限（PRD-P9 §6.2）。20K 字符约 60KB utf8mb4，MEDIUMTEXT 余量充足 */
    private static final int BODY_MAX = 20000;
    /** 回复上限 */
    private static final int COMMENT_MAX = 5000;
    /** 分页上限：超过 50 直接截断而不是报错，避免前端传 size=99999 拖垮库 */
    private static final int SIZE_MAX = 50;
    private static final int SIZE_DEFAULT = 20;

    /** link_type 白名单：只认这四种，其余一律拒（防止前端塞任意值污染反查索引） */
    private static final Set<String> LINK_TYPES = Set.of("none", "blog", "game", "tool");

    public static class PostReq {
        public String board_slug;
        public String title;
        public String content_md;
        public String link_type;
        public String link_key;
    }

    public static class CommentReq {
        public String content_md;
        public Integer parent_id;
    }

    // ================= 读 =================

    /** 板块列表（含帖子数） */
    @GetMapping("/boards")
    public ResponseEntity<Map<String, Object>> boards(HttpServletRequest request) {
        if (CAuthUtil.user(request) == null) return CAuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("boards", CommunityDao.listBoards());
        return ResponseEntity.ok(body);
    }

    /**
     * 帖子列表。{@code board=<slug>} 可选（缺省=全站）。
     *
     * <p>⚠️ M1 **只有 latest 一种排序**（置顶 > 最后回复倒序）。{@code sort=hot} 会被忽略而不是
     * 报错 —— 前端 M3 才传它。这里显式写成"忽略"，免得将来以为"传了就是热度排序"。
     */
    @GetMapping("/posts")
    public ResponseEntity<Map<String, Object>> posts(@RequestParam(required = false) String board,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     HttpServletRequest request) {
        if (CAuthUtil.user(request) == null) return CAuthUtil.unauth();
        int limit = clampSize(size);
        int p = page < 1 ? 1 : page;
        long offset = (long) (p - 1) * limit;

        long boardId = 0;
        if (board != null && !board.isBlank()) {
            Map<String, Object> b = CommunityDao.getBoardBySlug(board.trim());
            if (b == null) return ResponseEntity.status(404).body(Map.of("error", "板块不存在"));
            boardId = ((Number) b.get("id")).longValue();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("posts", CommunityDao.listPosts(boardId, limit, offset));
        body.put("total", CommunityDao.countPosts(boardId));
        body.put("page", p);
        body.put("size", limit);
        return ResponseEntity.ok(body);
    }

    /**
     * 帖子详情。{@code view=1} 时浏览数 +1（按 IP+帖 1 小时去重，用限流器的计数桶实现）。
     *
     * <p>已删/已隐藏的帖子对普通用户一律 **404**（不是 403）：403 等于告知"这里曾经有东西"。
     */
    @GetMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> post(@PathVariable long id,
                                                    @RequestParam(defaultValue = "0") int view,
                                                    HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        Map<String, Object> row = CommunityDao.getPost(id);
        if (row == null || !"normal".equals(String.valueOf(row.get("status")))) return notFound();
        if (view == 1) {
            // rateOk(key,1,3600) 首次为 true —— 借用计数桶做「1 小时一次」的去重
            if (RateLimit.rateOk("cm-view:" + id + ":" + ClientIp.of(request), 1, 3600)) {
                CommunityDao.increaseView(id);
                row.put("view_count", ((Number) row.get("view_count")).intValue() + 1);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("post", row);
        long uid = CAuthUtil.userId(user);
        body.put("can_edit", uid == ((Number) row.get("author_id")).longValue());
        // M2b：把「我有没有点过赞/收藏」回填给详情页 —— 列表不填（一页 20 条要多 20 次查询），
        // 点赞交互只做在详情页。
        body.put("liked", CommunityDao.isReacted(uid, "post", id, "like"));
        body.put("bookmarked", CommunityDao.isReacted(uid, "post", id, "bookmark"));
        return ResponseEntity.ok(body);
    }

    /** 回复分页（时间正序） */
    @GetMapping("/posts/{id}/comments")
    public ResponseEntity<Map<String, Object>> comments(@PathVariable long id,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        HttpServletRequest request) {
        if (CAuthUtil.user(request) == null) return CAuthUtil.unauth();
        Map<String, Object> post = CommunityDao.getPost(id);
        if (post == null || !"normal".equals(String.valueOf(post.get("status")))) return notFound();
        int limit = clampSize(size);
        int p = page < 1 ? 1 : page;
        long uid = CAuthUtil.userId(CAuthUtil.user(request));
        List<Map<String, Object>> rows = CommunityDao.listComments(id, limit, (long) (p - 1) * limit);
        // 批量回填「我点赞了哪些回复」：一页最多 50 条，逐条查就是 50 次往返
        List<Long> ids = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) ids.add(((Number) r.get("id")).longValue());
        java.util.Set<Long> liked = CommunityDao.reactedIds(uid, "comment", "like", ids);
        for (Map<String, Object> r : rows) {
            r.put("liked", liked.contains(((Number) r.get("id")).longValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comments", rows);
        body.put("total", CommunityDao.countComments(id));
        body.put("page", p);
        body.put("size", limit);
        return ResponseEntity.ok(body);
    }

    // ================= 互动（M2b：点赞 / 收藏 / 用户资料） =================

    public static class ReactionReq {
        public String target_type;
        public Integer target_id;
        public String kind;
    }

    /**
     * 点赞 / 收藏切换（同一个端点，靠 {@code kind} 区分）。
     *
     * <p>幂等由 {@code cm_reactions} 的主键保证：同一用户对同一目标重复点只会有 1 行。
     * 返回 {@code {active, count}} —— 前端据此把按钮画成实心/空心，不需要再查一次。
     */
    @PostMapping("/reactions")
    public ResponseEntity<Map<String, Object>> react(@RequestBody(required = false) ReactionReq req,
                                                    HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        if (req == null || req.target_id == null) return badField("缺少 target_type / target_id");
        String type = req.target_type == null ? "" : req.target_type.trim();
        if (!"post".equals(type) && !"comment".equals(type)) return badField("target_type 只能是 post / comment");
        String kind = req.kind == null || req.kind.isBlank() ? "like" : req.kind.trim();
        if (!"like".equals(kind) && !"bookmark".equals(kind)) return badField("kind 只能是 like / bookmark");
        if (!RateLimit.rateOk("cm-react:" + CAuthUtil.userId(user), 120, 60)) {
            return ResponseEntity.status(429).body(Map.of("error", "操作过于频繁"));
        }

        long tid = req.target_id.longValue();
        // 目标必须存在：否则会给一条不存在的 id 累加计数，且永远查不出来
        if ("post".equals(type)) {
            Map<String, Object> p = CommunityDao.getPost(tid);
            if (p == null) return notFound();
        } else {
            // 回复是否存在：commentAuthor 返回 null 即不存在（它本来就是通知要用的同一查询）
            if (CommunityDao.commentAuthor(tid) == null) return notFound();
        }

        Map<String, Object> r = CommunityDao.toggleReaction(CAuthUtil.userId(user), type, tid, kind);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("active", r.get("active"));
        body.put("count", r.get("count"));
        return ResponseEntity.ok(body);
    }

    /** 我的收藏（帖子） */
    @GetMapping("/me/bookmarks")
    public ResponseEntity<Map<String, Object>> myBookmarks(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);
        int limit = clampSize(size);
        int p = page < 1 ? 1 : page;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", CommunityDao.listBookmarks(uid, limit, (long) (p - 1) * limit));
        body.put("total", CommunityDao.countBookmarks(uid));
        body.put("page", p);
        body.put("size", limit);
        return ResponseEntity.ok(body);
    }

    public static class CommunityProfileReq {
        public String bio;
        public String avatar_url;
    }

    /**
     * 保存我的社区资料（bio / 头像 URL）。
     *
     * <p>⚠️ 头像**只接受 http(s) 或空串**：这是会写进 {@code <img src>} 的字段。
     * 虽然 React 设 src 不会执行脚本，但 `javascript:` 在这个位置毫无意义、只会是脏数据，
     * 在入口拒掉比在渲染侧再防一层更省心。
     */
    @PutMapping("/me/profile")
    public ResponseEntity<Map<String, Object>> saveMyProfile(@RequestBody(required = false) CommunityProfileReq req,
                                                             HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        if (req == null) return badField("缺少请求体");
        String bio = req.bio == null ? "" : req.bio.trim();
        String avatar = req.avatar_url == null ? "" : req.avatar_url.trim();
        if (charLen(bio) > 200) return badField("简介最长 200 个字符");
        if (charLen(avatar) > 300) return badField("头像地址最长 300 个字符");
        if (!avatar.isEmpty() && !avatar.matches("(?i)^https?://.+")) {
            return badField("头像地址必须以 http:// 或 https:// 开头");
        }
        CommunityDao.saveProfile(CAuthUtil.userId(user), bio, avatar);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 用户公开资料 + 最近主题 */
    @GetMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> userProfile(@PathVariable long id, HttpServletRequest request) {
        if (CAuthUtil.user(request) == null) return CAuthUtil.unauth();
        Map<String, Object> u = CommunityDao.userPublic(id);
        if (u == null) return notFound();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", u);
        body.put("posts", CommunityDao.listPostsByAuthor(id, 10));
        return ResponseEntity.ok(body);
    }

    /**
     * 按站内对象反查讨论帖（给博客/游戏/工具页挂「💬 讨论 (N)」用）。
     * 没有讨论帖时返回 {@code {"post_id":0}} 而不是 404 —— 前端要拿它决定显示「发起讨论」。
     */
    @GetMapping("/link")
    public ResponseEntity<Map<String, Object>> byLink(@RequestParam String type,
                                                      @RequestParam String key,
                                                      HttpServletRequest request) {
        if (CAuthUtil.user(request) == null) return CAuthUtil.unauth();
        if (!LINK_TYPES.contains(type) || "none".equals(type)) {
            return ResponseEntity.status(400).body(Map.of("error", "type 只能是 blog/game/tool"));
        }
        Map<String, Object> row = CommunityDao.getPostByLink(type, key);
        Map<String, Object> body = new LinkedHashMap<>();
        if (row == null) {
            body.put("post_id", 0);
        } else {
            body.put("post_id", row.get("id"));
            body.put("title", row.get("title"));
            body.put("comment_count", row.get("comment_count"));
        }
        return ResponseEntity.ok(body);
    }

    // ================= 写 =================

    /** 发帖。限流 5 篇 / 小时 / 用户（对齐 PRD-P9 §5.2）。 */
    @PostMapping("/posts")
    public ResponseEntity<Map<String, Object>> createPost(@RequestBody(required = false) PostReq req,
                                                          HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);
        if (!RateLimit.rateOk("cm-post:" + uid, 5, 3600)) {
            return ResponseEntity.status(429).body(Map.of("error", "发帖过于频繁，请稍后再试"));
        }
        if (req == null) return ResponseEntity.status(400).body(Map.of("error", "缺少请求体"));

        String title = req.title == null ? "" : req.title.trim();
        String md = req.content_md == null ? "" : req.content_md.trim();

        String linkType = req.link_type == null || req.link_type.isBlank() ? "none" : req.link_type.trim();
        if (!LINK_TYPES.contains(linkType)) return badField("link_type 只能是 none/blog/game/tool");
        String linkKey = req.link_key == null ? "" : req.link_key.trim();

        if (title.isEmpty()) return badField("标题不能为空");
        if (charLen(title) > TITLE_MAX) return badField("标题最长 " + TITLE_MAX + " 个字符");
        // 关联站内对象的主题**允许空正文**（它本质是「某篇文章/某个游戏的讨论区」，
        // 标题即上下文）；纯发帖必须有正文，否则会攒出一串只有标题的空帖。
        if (md.isEmpty() && "none".equals(linkType)) return badField("正文不能为空");
        if (charLen(md) > BODY_MAX) return badField("正文最长 " + BODY_MAX + " 个字符");

        String boardSlug = req.board_slug == null ? "" : req.board_slug.trim();
        Map<String, Object> board = CommunityDao.getBoardBySlug(boardSlug);
        if (board == null) return badField("板块不存在");
        if (!"active".equals(String.valueOf(board.get("status")))) {
            return ResponseEntity.status(403).body(Map.of("error", "该板块已归档，不能发帖"));
        }

        if (!"none".equals(linkType)) {
            if (linkKey.isEmpty()) return badField("link_key 不能为空");
            // 同一对象只允许一条讨论帖：先查后建（业务层去重，未加唯一索引 —— 见 PRD-P9 §4.3）
            Map<String, Object> exist = CommunityDao.getPostByLink(linkType, linkKey);
            if (exist != null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", false);
                body.put("post_id", exist.get("id"));
                body.put("error", "该内容的讨论帖已存在");
                return ResponseEntity.status(409).body(body);
            }
        }

        long id = CommunityDao.createPost(((Number) board.get("id")).longValue(), uid, title,
                md, stripMarkdown(md), linkType, linkKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        return ResponseEntity.ok(body);
    }

    /** 软删帖子（仅作者本人）。0 行受影响时区分 404/403，别一律 403 —— 否则删别人的帖等于探测存在性。 */
    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> deletePost(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        int n = CommunityDao.softDeletePost(id, CAuthUtil.userId(user));
        if (n > 0) return ResponseEntity.ok(Map.of("ok", true));
        Map<String, Object> row = CommunityDao.getPost(id);
        if (row == null) return notFound();
        return ResponseEntity.status(403).body(Map.of("error", "只能删除自己的帖子"));
    }

    /** 回复。限流 20 条 / 小时 / 用户。{@code parent_id} 必须属于本帖（防跨帖串楼）。 */
    @PostMapping("/posts/{id}/comments")
    public ResponseEntity<Map<String, Object>> createComment(@PathVariable long id,
                                                             @RequestBody(required = false) CommentReq req,
                                                             HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);
        if (!RateLimit.rateOk("cm-cmt:" + uid, 20, 3600)) {
            return ResponseEntity.status(429).body(Map.of("error", "回复过于频繁，请稍后再试"));
        }
        if (req == null) return ResponseEntity.status(400).body(Map.of("error", "缺少请求体"));
        String md = req.content_md == null ? "" : req.content_md.trim();
        if (md.isEmpty()) return badField("回复内容不能为空");
        if (charLen(md) > COMMENT_MAX) return badField("回复最长 " + COMMENT_MAX + " 个字符");

        Map<String, Object> post = CommunityDao.getPost(id);
        if (post == null || !"normal".equals(String.valueOf(post.get("status")))) return notFound();

        long parentId = req.parent_id == null ? 0 : req.parent_id.longValue();
        if (parentId > 0 && !CommunityDao.commentBelongsToPost(parentId, id)) {
            return badField("被回复的楼层不属于本帖");
        }
        long cid = CommunityDao.createComment(id, uid, parentId, md, stripMarkdown(md));
        notifyReply(post, parentId, uid, id, cid, md);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", cid);
        return ResponseEntity.ok(body);
    }

    /**
     * 回复后发通知（PRD-P9 M3）。
     *
     * <p>收件人候选：楼主 + 被回复楼层的作者。三条过滤：
     * ① **不通知自己**（自己回复自己的帖子是最常见的通知噪音源）；
     * ② **不通知 {@code author_id=0}**（官方/种子帖没有真人收件人，往 uid=0 写通知等于丢弃）；
     * ③ 候选用 {@link java.util.LinkedHashSet} 去重 —— 回复楼主自己的楼层时两个候选是同一个人，
     *    不去重会收到两条一模一样的提醒。
     *
     * <p>通知写失败**不影响回复成功**：回复已经落库，少一条提醒是可接受的降级；
     * 反过来（因为通知失败而回滚回复）才是真正的数据损失。
     */
    private static void notifyReply(Map<String, Object> post, long parentId, long actorId,
                                    long postId, long commentId, String md) {
        try {
            java.util.Set<Long> targets = new java.util.LinkedHashSet<>();
            long postAuthor = ((Number) post.get("author_id")).longValue();
            if (postAuthor > 0) targets.add(postAuthor);
            if (parentId > 0) {
                Long parentAuthor = CommunityDao.commentAuthor(parentId);
                if (parentAuthor != null && parentAuthor > 0) targets.add(parentAuthor);
            }
            targets.remove(actorId);
            if (targets.isEmpty()) return;

            String excerpt = clampText(stripMarkdown(md), 80);
            for (long t : targets) {
                CommunityDao.createNotification(t, "reply", actorId, postId, commentId, excerpt);
            }
        } catch (RuntimeException e) {
            // 只记日志，不影响已成功的回复
            org.slf4j.LoggerFactory.getLogger(CommunityController.class)
                    .warn("社区通知写入失败 post={} comment={}", postId, commentId, e);
        }
    }

    // ================= 通知（M3） =================

    /** 我的通知（分页，新→旧） */
    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Object>> notifications(@RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "20") int size,
                                                             HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);
        int limit = clampSize(size);
        int p = page < 1 ? 1 : page;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", CommunityDao.listNotifications(uid, limit, (long) (p - 1) * limit));
        body.put("total", CommunityDao.countNotifications(uid));
        body.put("unread", CommunityDao.countUnreadNotifications(uid));
        body.put("page", p);
        body.put("size", limit);
        return ResponseEntity.ok(body);
    }

    /** 未读数（Shell 红点，30s 轮询；只回一个整数，响应体很小） */
    @GetMapping("/notifications/unread-count")
    public ResponseEntity<Map<String, Object>> unreadCount(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        return ResponseEntity.ok(Map.of("unread", CommunityDao.countUnreadNotifications(CAuthUtil.userId(user))));
    }

    /**
     * 标记已读。{@code {"ids":[1,2]}} 只标这几条；缺省或空数组 = **全部标为已读**。
     *
     * <p>DAO 侧两个分支都带 {@code user_id} 条件，传别人的通知 id 也改不动。
     */
    @PostMapping("/notifications/read")
    public ResponseEntity<Map<String, Object>> markRead(@RequestBody(required = false) ReadReq req,
                                                       HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        int n = CommunityDao.markNotificationsRead(CAuthUtil.userId(user),
                req == null ? null : req.ids);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("updated", n);
        body.put("unread", CommunityDao.countUnreadNotifications(CAuthUtil.userId(user)));
        return ResponseEntity.ok(body);
    }

    public static class ReadReq {
        public List<Long> ids;
    }

    // ================= 搜索（M3） =================

    /** 搜索标题与正文（LIKE；见 CommunityDao.countSearch 的取舍说明） */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      HttpServletRequest request) {
        if (CAuthUtil.user(request) == null) return CAuthUtil.unauth();
        String kw = q == null ? "" : q.trim();
        if (kw.isEmpty()) return badField("请输入搜索词");
        int limit = clampSize(size);
        int p = page < 1 ? 1 : page;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("posts", CommunityDao.searchPosts(kw, limit, (long) (p - 1) * limit));
        body.put("total", CommunityDao.countSearch(kw));
        body.put("page", p);
        body.put("size", limit);
        body.put("q", kw);
        return ResponseEntity.ok(body);
    }

    /** 按 code point 截断（emoji 不被切成半个） */
    private static String clampText(String s, int max) {
        if (s == null) return "";
        int n = s.codePointCount(0, s.length());
        if (n <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }

    /** 软删回复（仅作者本人），同事务回落帖子 comment_count。 */
    @DeleteMapping("/comments/{id}")
    public ResponseEntity<Map<String, Object>> deleteComment(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        int n = CommunityDao.softDeleteComment(id, CAuthUtil.userId(user));
        if (n > 0) return ResponseEntity.ok(Map.of("ok", true));
        return ResponseEntity.status(403).body(Map.of("error", "只能删除自己的回复，或该回复已删除"));
    }

    // ================= 内部工具 =================

    private static int clampSize(int size) {
        if (size <= 0) return SIZE_DEFAULT;
        return Math.min(size, SIZE_MAX);
    }

    /**
     * 字符数（按 code point 计）。用 {@code String.length()} 会把 emoji 算 2 个，
     * 用户看到"还能打 3 个字"却提示超限 —— 用 codePointCount 与用户直觉一致。
     */
    private static int charLen(String s) {
        return s.codePointCount(0, s.length());
    }

    /** 缺字段/超限统一 400（对齐 CMeController 的 error 文案风格，不用 422 那套 FastAPI 形状） */
    private static ResponseEntity<Map<String, Object>> badField(String msg) {
        return ResponseEntity.status(400).body(Map.of("error", msg));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "内容不存在或已删除"));
    }

    /**
     * Markdown → 纯文本，存入 {@code content_text} 供 M3 的 LIKE 搜索。
     *
     * <p>不是渲染器（渲染在前端做，且**永不生成 HTML**）。这里只做「去掉标记符号」：
     * 代码围栏内容保留（用户搜代码片段是合理需求）、链接保留文字丢掉 URL（避免搜 URL 命中一堆无关帖）。
     */
    static String stripMarkdown(String md) {
        if (md == null) return "";
        return md
                .replaceAll("```[\\s\\S]*?```", " ")      // 整个围栏块压成空格（保留正文可读性优先）
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")     // 图片整段去掉（alt 也不留）
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")   // 链接保留文字
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")       // 标题符号
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")            // 引用符号
                .replaceAll("[`*_~]", "")                        // 行内标记符
                .replaceAll("\\s+", " ")
                .trim();
    }
}
