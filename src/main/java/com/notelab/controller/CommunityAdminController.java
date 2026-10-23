package com.notelab.controller;

import com.notelab.dao.CommunityDao;
import com.notelab.service.CommunitySeedService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B 端社区管理（PRD-P9 M2）：{@code /api/community-admin} 下的板块 / 帖子 / 内容导入。
 *
 * <h3>为什么需要这个类</h3>
 * C 端只有 5 个用户，社区内容不可能靠 UGC 自发长起来 —— 内容供给的主体是**管理员**
 * （写主题、整理板块、把已有博客导成讨论区）。所以「后台能配置社区」比「用户能点赞」
 * 更早需要，这也正是把 M2 从「用户互动」改期为「官方内容供给」的原因（2026-10-23 定）。
 *
 * <h3>鉴权：只有 B 端登录，没有按用户组细分</h3>
 * 与 {@link CAdminController} 同档：B 端 {@code api:*} 权限码目前**只登记、不强制校验**，
 * 真正的门槛是 {@code (admin)/layout.tsx} 的页面守卫。这里只做「未登录 → 401」。
 * ⚠️ 这意味着**任何 B 端账号**都能调这些接口 —— 现网 B 端账号只有站主本人，可接受；
 * 将来若开了多个 B 端角色，要在这里补 `isSuperAdmin` 判断（别只靠菜单隐藏）。
 *
 * <h3>官方身份</h3>
 * 管理员发的帖子 {@code author_id = 0}，C 端显示为「NoteLab 官方」。
 * **不能**用 B 端 {@code users.id} —— {@code cm_posts.author_id} 的语义是 {@code c_users.id}，
 * 两套 id 空间会撞号，把某个 C 端用户变成"管理员发言"。
 */
@RestController
@RequestMapping("/api/community-admin")
public class CommunityAdminController {

    private static final int TITLE_MAX = 120;
    private static final int BODY_MAX = 20000;
    private static final Pattern SLUG_RE = Pattern.compile("[a-z0-9][a-z0-9-]{1,48}");
    private static final Set<String> BOARD_STATUS = Set.of("active", "archived");

    public static class BoardReq {
        public String slug;
        public String name;
        public String description;
        public String icon;
        public Integer sort_no;
        public String status;
    }

    public static class PostReq {
        public String board_slug;
        public String title;
        public String content_md;
    }

    public static class FlagsReq {
        public Boolean pinned;
        public Boolean featured;
        public Boolean closed;
    }

    // ================= 板块 =================

    @GetMapping("/boards")
    public ResponseEntity<Map<String, Object>> boards(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("boards", CommunityDao.listBoardsForAdmin());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/boards")
    public ResponseEntity<Map<String, Object>> createBoard(@RequestBody(required = false) BoardReq req,
                                                           HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null) return bad("缺少请求体");
        String slug = req.slug == null ? "" : req.slug.trim().toLowerCase();
        String name = req.name == null ? "" : req.name.trim();
        if (!SLUG_RE.matcher(slug).matches()) {
            return bad("slug 只能是 2-49 位小写字母/数字/短横线，且以字母或数字开头");
        }
        if (name.isEmpty() || name.length() > 50) return bad("板块名必填，最长 50 字符");
        if (CommunityDao.getBoardBySlug(slug) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "slug 已被占用"));
        }
        long id = CommunityDao.createBoard(slug, name,
                nz(req.description), nz(req.icon), req.sort_no == null ? 100 : req.sort_no);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        return ResponseEntity.ok(body);
    }

    /**
     * 部分更新。{@code slug} 允许改，但它是 C 端 URL 的一部分（{@code /community/b/<slug>}）
     * —— 改完旧书签会 404，所以只在显式传了才动。
     */
    @PutMapping("/boards/{id}")
    public ResponseEntity<Map<String, Object>> updateBoard(@PathVariable long id,
                                                           @RequestBody(required = false) BoardReq req,
                                                           HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null) return bad("缺少请求体");
        String slug = req.slug == null ? null : req.slug.trim().toLowerCase();
        if (slug != null) {
            if (!SLUG_RE.matcher(slug).matches()) return bad("slug 格式不合法");
            Map<String, Object> other = CommunityDao.getBoardBySlug(slug);
            if (other != null && ((Number) other.get("id")).longValue() != id) {
                return ResponseEntity.status(409).body(Map.of("error", "slug 已被其他板块占用"));
            }
        }
        if (req.status != null && !BOARD_STATUS.contains(req.status)) {
            return bad("status 只能是 active / archived");
        }
        CommunityDao.updateBoard(id,
                slug,
                req.name == null ? null : req.name.trim(),
                req.description,
                req.icon,
                req.sort_no,
                req.status);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 删除板块：**有帖子就拒绝**（否则帖子会挂在一个不存在的板块上，C 端 JOIN 直接查不到） */
    @DeleteMapping("/boards/{id}")
    public ResponseEntity<Map<String, Object>> deleteBoard(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        long n = CommunityDao.countPostsInBoard(id);
        if (n > 0) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "该板块下还有 " + n + " 个主题，先把它们移走或删除"));
        }
        CommunityDao.deleteBoard(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 帖子 =================

    /** 列表：**含种子帖与已删/已隐藏**，可按板块、状态、关键词筛。分页用 limit/offset（对齐后台其他列表页） */
    @GetMapping("/posts")
    public ResponseEntity<Map<String, Object>> posts(@RequestParam(required = false) String board,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "20") int limit,
                                                     @RequestParam(defaultValue = "0") long offset,
                                                     HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int size = Math.min(Math.max(limit, 1), 100);
        long off = Math.max(offset, 0);
        long boardId = 0;
        if (board != null && !board.isBlank()) {
            Map<String, Object> b = CommunityDao.getBoardBySlug(board.trim());
            if (b == null) return ResponseEntity.status(404).body(Map.of("error", "板块不存在"));
            boardId = ((Number) b.get("id")).longValue();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", CommunityDao.adminListPosts(boardId, q, status, size, off));
        body.put("total", CommunityDao.adminCountPosts(boardId, q, status));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> post(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> row = CommunityDao.adminGetPost(id);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "帖子不存在"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("post", row);
        return ResponseEntity.ok(body);
    }

    /** 官方发帖：author_id=0；不允许带 link_*（那是「某篇文章的讨论区」，由导入或 C 端入口产生） */
    @PostMapping("/posts")
    public ResponseEntity<Map<String, Object>> createPost(@RequestBody(required = false) PostReq req,
                                                          HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null) return bad("缺少请求体");
        String title = req.title == null ? "" : req.title.trim();
        String md = req.content_md == null ? "" : req.content_md.trim();
        if (title.isEmpty()) return bad("标题不能为空");
        if (title.length() > TITLE_MAX) return bad("标题最长 " + TITLE_MAX + " 个字符");
        if (md.isEmpty()) return bad("正文不能为空");
        if (md.length() > BODY_MAX) return bad("正文最长 " + BODY_MAX + " 个字符");

        Map<String, Object> board = CommunityDao.getBoardBySlug(nz(req.board_slug).trim());
        if (board == null) return bad("板块不存在");
        long id = CommunityDao.createPost(((Number) board.get("id")).longValue(), 0,
                title, md, CommunityController.stripMarkdown(md), "none", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        return ResponseEntity.ok(body);
    }

    /** 编辑：只改板块/标题/正文。{@code link_*} 与计数不动（改 key 会让「讨论入口」指向别的内容） */
    @PutMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> updatePost(@PathVariable long id,
                                                          @RequestBody(required = false) PostReq req,
                                                          HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null) return bad("缺少请求体");
        Map<String, Object> cur = CommunityDao.adminGetPost(id);
        if (cur == null) return ResponseEntity.status(404).body(Map.of("error", "帖子不存在"));

        String title = req.title == null ? null : req.title.trim();
        String md = req.content_md == null ? null : req.content_md.trim();
        if (title != null && title.isEmpty()) return bad("标题不能为空");
        if (title != null && title.length() > TITLE_MAX) return bad("标题最长 " + TITLE_MAX + " 个字符");
        if (md != null && md.length() > BODY_MAX) return bad("正文最长 " + BODY_MAX + " 个字符");

        long boardId = ((Number) cur.get("board_id")).longValue();
        if (req.board_slug != null && !req.board_slug.isBlank()) {
            Map<String, Object> b = CommunityDao.getBoardBySlug(req.board_slug.trim());
            if (b == null) return bad("板块不存在");
            boardId = ((Number) b.get("id")).longValue();
        }
        String finalTitle = title != null ? title : String.valueOf(cur.get("title"));
        // content_md 为 null 表示不改正文：复用库里那份再算 content_text，避免把搜索索引清空
        String finalMd = md != null ? md : String.valueOf(cur.get("content_md"));
        CommunityDao.adminUpdatePost(id, boardId, finalTitle, finalMd,
                CommunityController.stripMarkdown(finalMd));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 置顶 / 精华 / 锁帖。三个字段都可单独传，缺省表示不改。 */
    @PostMapping("/posts/{id}/flags")
    public ResponseEntity<Map<String, Object>> flags(@PathVariable long id,
                                                     @RequestBody(required = false) FlagsReq req,
                                                     HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null) return bad("缺少请求体");
        if (req.pinned == null && req.featured == null && req.closed == null) {
            return bad("至少要传 pinned / featured / closed 之一");
        }
        CommunityDao.setPostFlags(id,
                req.pinned == null ? null : (req.pinned ? 1 : 0),
                req.featured == null ? null : (req.featured ? 1 : 0),
                req.closed);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> deletePost(@PathVariable long id, HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int n = CommunityDao.adminSoftDeletePost(id);
        if (n == 0) return ResponseEntity.status(404).body(Map.of("error", "帖子不存在或已删除"));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 治理（M4） =================

    public static class HideReq {
        public Boolean hidden;
    }

    public static class HandleReportReq {
        public String status;
    }

    public static class MuteReq {
        /** 禁言天数；0 或 null = 解除禁言 */
        public Integer days;
    }

    /** 举报队列（默认 pending） */
    @GetMapping("/reports")
    public ResponseEntity<Map<String, Object>> reports(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "20") int limit,
                                                       @RequestParam(defaultValue = "0") long offset,
                                                       HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int size = Math.min(Math.max(limit, 1), 100);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", CommunityDao.listReports(status, size, Math.max(offset, 0)));
        body.put("total", CommunityDao.countReports(status));
        return ResponseEntity.ok(body);
    }

    /**
     * 处理举报：{@code accepted}（举报成立，通常会同时折叠内容）/ {@code rejected}。
     *
     * <p>⚠️ 只改举报单的状态，**不自动折叠内容** —— 处理动作要显式（`/posts/{id}/hidden`），
     * 否则「误点接受」会连带把正常内容藏起来，且没人知道是哪一步做的。
     */
    @PostMapping("/reports/{id}/handle")
    public ResponseEntity<Map<String, Object>> handleReport(@PathVariable long id,
                                                            @RequestBody(required = false) HandleReportReq req,
                                                            HttpServletRequest request) {
        Map<String, Object> admin = AuthUtil.user(request);
        if (admin == null) return AuthUtil.unauth();
        if (req == null || req.status == null) return bad("缺少 status");
        if (!"accepted".equals(req.status) && !"rejected".equals(req.status)) {
            return bad("status 只能是 accepted / rejected");
        }
        CommunityDao.handleReport(id, req.status, AuthUtil.userId(admin));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 折叠/恢复一条主题。折叠后 C 端 404（等同于不存在），但数据保留、可在后台恢复。 */
    @PostMapping("/posts/{id}/hidden")
    public ResponseEntity<Map<String, Object>> hidePost(@PathVariable long id,
                                                        @RequestBody(required = false) HideReq req,
                                                        HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null || req.hidden == null) return bad("缺少 hidden");
        CommunityDao.setPostHidden(id, req.hidden);
        return ResponseEntity.ok(Map.of("ok", true, "hidden", req.hidden));
    }

    /** 折叠/恢复一条回复。折叠后 C 端显示「该回复已被折叠」，楼层保留（不串楼）。 */
    @PostMapping("/comments/{id}/hidden")
    public ResponseEntity<Map<String, Object>> hideComment(@PathVariable long id,
                                                           @RequestBody(required = false) HideReq req,
                                                           HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        if (req == null || req.hidden == null) return bad("缺少 hidden");
        CommunityDao.setCommentHidden(id, req.hidden);
        return ResponseEntity.ok(Map.of("ok", true, "hidden", req.hidden));
    }

    /**
     * 禁言 C 端用户 {@code days} 天（0 = 解除）。
     *
     * <p>⚠️ 禁言写在 {@code cm_user_profile.muted_until}，判定用 {@code > NOW()} ——
     * 到期自动失效，**不需要定时任务解禁**（定时任务挂了就会永久禁言，这是最不该引入的故障面）。
     */
    @PostMapping("/users/{id}/mute")
    public ResponseEntity<Map<String, Object>> mute(@PathVariable long id,
                                                    @RequestBody(required = false) MuteReq req,
                                                    HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int days = req == null || req.days == null ? 0 : req.days;
        if (days < 0 || days > 3650) return bad("days 必须在 0-3650 之间");
        if (days == 0) {
            CommunityDao.muteUser(id, null);
            return ResponseEntity.ok(Map.of("ok", true, "muted_until", ""));
        }
        // 由数据库算时间，避免 JVM 时区与 MySQL 时区不一致
        String until = CommunityDao.muteUserForDays(id, days);
        return ResponseEntity.ok(Map.of("ok", true, "muted_until", until));
    }

    // ================= 内容导入 =================

    /**
     * 从博客 RSS 导入「文章讨论区」种子帖（幂等）。
     *
     * <p>为什么放在后台按钮而不是一次性脚本：文章会持续新增，每次都得有人重新扫一遍。
     * 做成按钮，加完文章点一下就行；幂等由 {@link CommunitySeedService} 保证
     * （有回复的种子帖不会被删）。
     */
    @PostMapping("/import-blog-seeds")
    public ResponseEntity<Map<String, Object>> importBlogSeeds(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        Map<String, Object> r = CommunitySeedService.importFromRss();
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        return ok ? ResponseEntity.ok(r) : ResponseEntity.status(500).body(r);
    }

    // ================= 内部工具 =================

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.status(400).body(Map.of("error", msg));
    }
}
