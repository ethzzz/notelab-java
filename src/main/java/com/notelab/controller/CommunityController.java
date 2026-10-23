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
        body.put("can_edit", CAuthUtil.userId(user) == ((Number) row.get("author_id")).longValue());
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comments", CommunityDao.listComments(id, limit, (long) (p - 1) * limit));
        body.put("total", CommunityDao.countComments(id));
        body.put("page", p);
        body.put("size", limit);
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", cid);
        return ResponseEntity.ok(body);
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
