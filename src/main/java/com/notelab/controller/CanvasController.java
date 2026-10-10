package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.CanvasCollaboratorDao;
import com.notelab.dao.CanvasDocDao;
import com.notelab.dao.UserDao;
import com.notelab.service.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B 端协作画布：前缀 /api/canvas。
 *
 * <p><b>职责划分</b>：本控制器只管**元数据**（有哪些画布、叫什么名字、用哪套引擎、谁建的）；
 * 画布**内容**（tldraw 文档快照 / Excalidraw 场景）由协作服务（notelab-b/collab，本机 :3030）
 * 的 SQLite 持有，两边靠 {@code room_id} 关联。这里不读写内容，避免把大快照塞进业务库。
 *
 * <p><b>权限模型（画布级 ACL，2026-10-10 方案 C）</b>：画布**列表对全部 B 端账户可见**
 * （发现与授权分开），但「能打开 / 能改 / 能删」按画布逐块判定，见 {@link #permOf}：
 * <ul>
 *   <li>{@code owner} —— 创建者（{@code created_by} 隐含，不落授权表）与超管：全权，含删除</li>
 *   <li>{@code edit} —— 被邀请且给了编辑权：可打开、可改内容、可改名，**不可删**</li>
 *   <li>{@code view} —— 只读：可打开，服务端强制只读（WS 层拦写，见 collab）</li>
 *   <li>{@code none} —— 列表里看得见，但打不开、操作栏全灰</li>
 * </ul>
 *
 * <p>授权行存在 MySQL {@code canvas_collaborator}；创建者与超管**刻意不落表**，
 * 这样「创建者一定是 owner」不需要任何同步逻辑，改不掉也删不掉。
 *
 * <p><b>两套引擎</b>：{@code engine} 是画布级属性，建好即固定（两套的文档格式不通用，要换就新建）。
 * 新增引擎时需三处同步登记：本文件的 {@link #ENGINES}、notelab-b 前端 engines 注册表、
 * collab 服务的 ENGINES —— 三处都是显式白名单，不做字符串猜引擎。
 *
 * <p>⚠️ {@link #get} 同时是**协作服务 WS 握手的鉴权入口**：它响应里的 {@code my_permission}
 * 被 collab 用来决定这条连接是否只读。改权限语义必须同步改 collab 侧。
 *
 * <p>全部要求 B 端登录（AuthUtil）；路由由 PermService 启动时自动登记进 perm_routes
 * （⚠️ 新 Controller 必须重启后端才进权限表）。
 */
@RestController
@RequestMapping("/api/canvas")
public class CanvasController {

    /** 标题上限 */
    private static final int MAX_TITLE_LEN = 200;
    /** 房间号：8 字节随机 → 16 位 hex。随机不可枚举，且删除后不复用 */
    private static final Pattern ROOM_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final SecureRandom RND = new SecureRandom();

    /** 支持的画布引擎白名单（建库默认值也是它） */
    private static final Set<String> ENGINES = Set.of("tldraw", "excalidraw");
    private static final String DEFAULT_ENGINE = "tldraw";

    /** 协作服务本机地址：仅用于「删画布时顺带清房间」，公网不暴露 */
    private static final String COLLAB_BASE = "http://127.0.0.1:3030";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public static class CanvasReq {
        public String title;
        /** 可选：tldraw | excalidraw，缺省 tldraw */
        public String engine;
    }

    // ================= CRUD =================

    /** 列表：q 模糊匹配标题；engine 筛选；按最近编辑倒序 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String engine,
                                                    @RequestParam(defaultValue = "100") int limit,
                                                    HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        // 引擎筛选走白名单校验：不认识的引擎**报 400 而不是静默忽略** ——
        // 静默忽略会让「筛了但没生效」看起来像「确实没有这种画布」。
        if (engine != null && !engine.isBlank() && !ENGINES.contains(engine)) {
            return bad("不支持的画布引擎：" + engine);
        }
        // 方案 C：列表对**全部 B 端登录账户**可见（画布不再是个人私有列表）。
        // 能不能打开 / 改 / 删由每行的 my_permission 决定，前端据此控制操作栏 ——
        // 列表可见性是「发现」，不是「授权」，两者刻意分开。
        List<Map<String, Object>> rows = CanvasDocDao.list(q, engine, limit);
        List<String> roomIds = new ArrayList<>();
        for (Map<String, Object> r : rows) roomIds.add(String.valueOf(r.get("room_id")));
        // 一次取回所有房间的协作者（逐房间查就是 N+1，画布一多直接拖垮列表）
        Map<String, List<Map<String, Object>>> collabOf = CanvasCollaboratorDao.groupByRooms(roomIds);
        Map<Long, String> names = userNames();

        Long uid = meId(me);
        boolean superAdmin = PermService.isSuperAdmin(me);
        for (Map<String, Object> r : rows) {
            String roomId = String.valueOf(r.get("room_id"));
            List<Map<String, Object>> collabs = collabOf.getOrDefault(roomId, List.of());
            long ownerId = asLong(r.get("created_by"), -1L);
            boolean mine = uid != null && uid.longValue() == ownerId;
            r.put("collaborators", decorate(collabs, names, ownerId));
            r.put("collaborator_count", collabs.size());
            // 我的权限：超管/创建者 = owner；否则看协作者表；都不是 = none
            String perm = superAdmin || mine ? PERM_OWNER : permissionIn(collabs, uid);
            r.put("my_permission", perm);
            r.put("is_mine", mine);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        // 选人下拉用的 B 端账户简表（不含 email）—— 创建者未必是超管，用不了超管专属的 /api/perm/users
        body.put("users", UserDao.listUserBriefs());
        body.put("me", Map.of("id", uid == null ? 0L : uid, "role", String.valueOf(me.get("role"))));
        return ResponseEntity.ok(body);
    }

    /** 单个画布元数据（编辑页取标题用） */
    @GetMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String roomId,
                                                   HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        // ⚠️ 本接口同时是**协作服务握手的鉴权入口** —— collab/lib/auth.mjs 转发 cookie 调它，
        //    **能读到元数据（200）才允许建立 WebSocket**，且响应里的 my_permission 会被 collab
        //    用来决定这条连接是否只读。改这里的语义 = 改 WS 的读写权限，务必同步改 collab 侧。
        String perm = permOf(me, roomId);
        if (!canRead(perm)) return noAccess();
        Map<String, Object> row = CanvasDocDao.getByRoom(roomId);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "画布不存在"));
        row.put("my_permission", perm);
        return ResponseEntity.ok(row);
    }

    /** 新建画布（room_id 由服务端生成，客户端拿回后跳转编辑） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CanvasReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        String title = normalizeTitle(req == null ? null : req.title, "未命名画布");
        if (title == null) return bad("标题过长（上限 " + MAX_TITLE_LEN + " 字）");
        String engine = normalizeEngine(req == null ? null : req.engine);
        if (engine == null) return bad("不支持的画布引擎（可选：" + String.join(" / ", ENGINES) + "）");
        Object idObj = me.get("id");
        Long createdBy = idObj instanceof Number n ? n.longValue() : null;
        String roomId = newRoomId();
        long id = CanvasDocDao.create(roomId, title, engine, createdBy);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        body.put("roomId", roomId);
        body.put("title", title);
        body.put("engine", engine);
        return ResponseEntity.ok(body);
    }

    /**
     * 重命名：**创建者 / 超管 / 有编辑权的协作者**都能改名。
     *
     * <p>语义取舍：改名是元数据动作，但它属于「编辑」而不是「拥有」—— 有编辑权的人本来就能改
     * 画布内容，再卡住名字没有意义。查看权限（view）改不动。
     */
    @PutMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable String roomId,
                                                      @RequestBody(required = false) CanvasReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!canEdit(permOf(me, roomId))) return noAccess();
        String title = normalizeTitle(req == null ? null : req.title, null);
        if (title == null) return bad("标题为空或过长（上限 " + MAX_TITLE_LEN + " 字）");
        CanvasDocDao.rename(roomId, title);
        return ResponseEntity.ok(Map.of("ok", true, "title", title));
    }

    /**
     * 删除画布：**只有创建者（与超管）**。协作者一律不能删 —— 哪怕他有编辑权。
     *
     * <p>先删元数据与授权行，再 best-effort 通知协作服务清房间内容。
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String roomId,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!isOwner(permOf(me, roomId))) {
            return ResponseEntity.status(403).body(Map.of("error", "只有画布创建者可以删除该画布"));
        }
        CanvasDocDao.delete(roomId);
        // 授权行随画布一起清（否则会留下「没有画布的授权行」，只能靠对账收拾）
        CanvasCollaboratorDao.deleteByRoom(roomId);
        // 元数据删除不可回滚，所以内容清不掉也不能报错回滚；但要**如实告知**，
        // 否则用户以为删干净了，实际在协作服务里留了一张孤儿表（「对账」能查出来并清理）。
        if (!purgeRoom(roomId)) {
            return ResponseEntity.ok(Map.of("ok", true, "purged", false,
                    "warning", "元数据已删除，但协作服务未能清理房间内容（可能它没在运行）。"
                            + "已留下孤儿表，可在「对账」里查看并清理。"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "purged", true));
    }

    // ================= 协作者（画布级 ACL，仅创建者/超管可改） =================

    public static class CollabReq {
        /** 被邀请的 B 端账户 id */
        public Long user_id;
        /** view | edit，缺省 view */
        public String permission;
    }

    /**
     * 协作者接口的**统一响应体**（GET / POST / DELETE 三者同构）。
     *
     * <p>⚠️ 三个接口必须给同一组字段：前端拿到响应后是**直接覆盖**本地状态的
     * （`setCollabList(await ...)`）。少了 {@code my_permission}，
     * {@code ownerCanManage} 就会凭 undefined 判成 false —— 邀请表单与
     * 「改权限 / 移除」按钮**当场整块消失**，表现为「邀请一次之后就再也点不了第二次」。
     * 这类塌陷只在交互过程中出现，静态读代码看不出来，只有真点一遍才发现。
     */
    private Map<String, Object> collabBody(String roomId, Map<String, Object> me, long ownerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("items", decorate(CanvasCollaboratorDao.listByRoom(roomId), userNames(), ownerId));
        body.put("owner", ownerId);
        body.put("my_permission", permOf(me, roomId));
        return body;
    }

    /** 某画布的协作者列表（任何能打开该画布的人都能看：知道自己和谁一起协作） */
    @GetMapping("/{roomId}/collaborators")
    public ResponseEntity<Map<String, Object>> listCollaborators(@PathVariable String roomId,
                                                                 HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!canRead(permOf(me, roomId))) return noAccess();
        Map<String, Object> row = CanvasDocDao.getByRoom(roomId);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "画布不存在"));
        return ResponseEntity.ok(collabBody(roomId, me, asLong(row.get("created_by"), -1L)));
    }

    /**
     * 邀请协作者 / 改其权限（upsert，仅创建者与超管）。
     *
     * <p>⚠️ 两道自洽性约束：① 不能把创建者加成协作者（他已经是 owner，加了反而可能被降级成
     * view 而自锁）② 超管也不落表（同理，见 {@code canvas_collaborator} 注释）。
     */
    @PostMapping("/{roomId}/collaborators")
    public ResponseEntity<Map<String, Object>> addCollaborator(@PathVariable String roomId,
                                                               @RequestBody(required = false) CollabReq req,
                                                               HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!isOwner(permOf(me, roomId))) {
            return ResponseEntity.status(403).body(Map.of("error", "只有画布创建者可以邀请协作者"));
        }
        if (req == null || req.user_id == null) return bad("缺少 user_id");
        String perm = "edit".equals(req.permission) ? "edit" : "view";
        Map<String, Object> row = CanvasDocDao.getByRoom(roomId);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "画布不存在"));
        long userId = req.user_id;
        if (userId == asLong(row.get("created_by"), -1L)) {
            return bad("创建者本身就是画布所有者，无需再邀请");
        }
        Map<String, Object> target = UserDao.getUserById(userId);
        if (target == null) return ResponseEntity.status(404).body(Map.of("error", "账户不存在"));
        if (PermService.ROLE_ADMIN.equals(String.valueOf(target.get("role")))) {
            return bad("超级管理员对所有画布已有全部权限，无需邀请");
        }
        CanvasCollaboratorDao.upsert(roomId, userId, perm, meId(me));
        return ResponseEntity.ok(collabBody(roomId, me, asLong(row.get("created_by"), -1L)));
    }

    /** 移除协作者（仅创建者与超管） */
    @DeleteMapping("/{roomId}/collaborators/{userId}")
    public ResponseEntity<Map<String, Object>> removeCollaborator(@PathVariable String roomId,
                                                                  @PathVariable long userId,
                                                                  HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!isOwner(permOf(me, roomId))) {
            return ResponseEntity.status(403).body(Map.of("error", "只有画布创建者可以移除协作者"));
        }
        int n = CanvasCollaboratorDao.remove(roomId, userId);
        if (n == 0) return ResponseEntity.status(404).body(Map.of("error", "该账户不是本画布的协作者"));
        Map<String, Object> row = CanvasDocDao.getByRoom(roomId);
        return ResponseEntity.ok(collabBody(roomId, me, row == null ? -1L : asLong(row.get("created_by"), -1L)));
    }

    /**
     * B 端账户简表（id / username / role），供「邀请协作者」的选人下拉。
     *
     * <p>刻意不复用 {@code /api/perm/users}：那个在受限前缀 {@code /api/perm} 下、仅超管可调，
     * 而画布创建者未必是超管。本接口只要求 B 端登录，且**不含 email**。
     */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> pickableUsers(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        return ResponseEntity.ok(Map.of("items", UserDao.listUserBriefs()));
    }

    // ================= 对账（仅超管） =================

    /**
     * 元数据 ↔ 内容 对账。
     *
     * <p>为什么需要：画布元数据在 MySQL {@code canvas_doc}、内容在协作服务的 SQLite，
     * 两边只靠 {@code room_id} 关联，删除又是**跨进程两步且失败不回滚**（见 {@link #delete}），
     * 所以会攒下两类不一致：
     * <ul>
     *   <li>{@code orphanContent} —— SQLite 有房间表、MySQL 没有记录。**删元数据成功但清房间失败**
     *       留下的垃圾，是真正要清理的那种。</li>
     *   <li>{@code orphanMeta} —— MySQL 有记录、SQLite 没表。⚠️ **绝大多数是正常的**：
     *       房间表懒建，只建了元数据、从没打开过编辑器就没有表。**不要按它删元数据**。</li>
     * </ul>
     *
     * <p>⚠️ 协作服务不可达时返回 503 而不是「两边不一致」—— 拿不到内容侧清单就无从对账，
     * 谎报成「有孤儿」会诱导误删。
     */
    @GetMapping("/reconcile")
    public ResponseEntity<Map<String, Object>> reconcile(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅超级管理员可做画布对账"));
        }
        JsonNode inv = collabInventory();
        if (inv == null) return collabUnreachable();

        Map<String, Map<String, Object>> content = new LinkedHashMap<>();
        JsonNode engines = inv.get("engines");
        if (engines != null && engines.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = engines.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode list = e.getValue();
                if (list == null || !list.isArray()) continue;   // 该引擎返回了 {error:...}
                for (JsonNode r : list) {
                    String id = str(r, "roomId");
                    if (id == null) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("roomId", id);
                    row.put("engine", e.getKey());
                    row.put("tables", intOr(r, "tables", 0));
                    row.put("rows", intOr(r, "rows", 0));
                    content.put(id, row);
                }
            }
        }

        Map<String, Map<String, Object>> meta = CanvasDocDao.metaByRoom();

        List<Map<String, Object>> orphanContent = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : content.entrySet()) {
            if (!meta.containsKey(e.getKey())) orphanContent.add(e.getValue());
        }
        List<Map<String, Object>> orphanMeta = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : meta.entrySet()) {
            if (content.containsKey(e.getKey())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roomId", e.getKey());
            row.put("title", e.getValue().get("title"));
            row.put("engine", e.getValue().get("engine"));
            row.put("updated_at", e.getValue().get("updated_at"));
            orphanMeta.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("metaCount", meta.size());
        body.put("contentCount", content.size());
        body.put("orphanContent", orphanContent);   // 可清理
        body.put("orphanMeta", orphanMeta);         // 仅提示，多属正常
        // 授权行孤儿：画布已删但 canvas_collaborator 还留着（删除是跨表两步，中途失败会剩）
        List<String> orphanCollab = CanvasCollaboratorDao.orphanRoomIds();
        body.put("orphanCollab", orphanCollab);
        return ResponseEntity.ok(body);
    }

    /**
     * 清理孤儿内容：把 SQLite 里**元数据已不存在**的房间表删掉（仅超管）。
     *
     * <p>⚠️ 只处理 {@code orphanContent} 这一个方向。反向（有元数据没表）**一律不动** ——
     * 那是房间表懒建的正常形态，而删元数据是不可逆的，必须由人在界面上逐条确认。
     *
     * <p>清理完返回「删了哪些」，而不是一个笼统的 ok —— 这是破坏性操作，要留下可读的凭据。
     */
    @DeleteMapping("/orphans")
    public ResponseEntity<Map<String, Object>> purgeOrphans(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅超级管理员可清理画布孤儿"));
        }
        // ① 授权行孤儿（画布已删、canvas_collaborator 还留着）：纯 MySQL 内的事，
        //    **不依赖协作服务** —— 所以放在它不可达的早退之前，否则协作服务一挂就连这个都清不了。
        List<String> collabRemoved = new ArrayList<>();
        for (String id : CanvasCollaboratorDao.orphanRoomIds()) {
            if (CanvasCollaboratorDao.deleteByRoom(id) > 0) collabRemoved.add(id);
        }

        // ② 内容孤儿（SQLite 有房间表、MySQL 没元数据）：必须拿到协作服务的清单
        JsonNode inv = collabInventory();
        if (inv == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("removed", List.of());
            body.put("removedCount", 0);
            body.put("collabRemoved", collabRemoved);
            body.put("collabRemovedCount", collabRemoved.size());
            body.put("warning", "授权行孤儿已清理，但协作服务不可达，内容孤儿这次跳过");
            return ResponseEntity.ok(body);
        }

        Map<String, Map<String, Object>> meta = CanvasDocDao.metaByRoom();
        List<Map<String, Object>> removed = new ArrayList<>();
        JsonNode engines = inv.get("engines");
        if (engines != null && engines.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = engines.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode list = e.getValue();
                if (list == null || !list.isArray()) continue;
                for (JsonNode r : list) {
                    String id = str(r, "roomId");
                    if (id == null || meta.containsKey(id)) continue;
                    purgeRoom(id);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("roomId", id);
                    row.put("engine", e.getKey());
                    row.put("tables", intOr(r, "tables", 0));
                    removed.add(row);
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("removed", removed);
        body.put("removedCount", removed.size());
        body.put("collabRemoved", collabRemoved);
        body.put("collabRemovedCount", collabRemoved.size());
        return ResponseEntity.ok(body);
    }

    /** 拉协作服务的磁盘房间清单（内部端点，仅本机）。不可达返回 null。 */
    private static JsonNode collabInventory() {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(COLLAB_BASE + "/rooms"))
                    // ⚠️ 与 purgeRoom 同一个坑：默认 HTTP_2 会先发 h2c 升级探测，
                    //    Node 把带 Upgrade 头的请求路由到 'upgrade' 事件 → 永远收不到响应
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(rq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("[canvas] 取房间清单返回 " + resp.statusCode() + "：" + resp.body());
                return null;
            }
            return JsonUtil.MAPPER.readTree(resp.body());
        } catch (Exception e) {
            System.err.println("[canvas] 取房间清单失败：" + e);
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> collabUnreachable() {
        return ResponseEntity.status(503).body(Map.of("error",
                "协作服务不可达（" + COLLAB_BASE + "）。对账需要它的房间清单，请确认 notelab-collab 在运行。"));
    }

    /** 取字符串字段；缺失/非文本返回 null（协同 JsonSanitizer 的判据：isTextual，不是 !isNull） */
    private static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static int intOr(JsonNode n, String field, int fallback) {
        JsonNode v = n == null ? null : n.get(field);
        return v != null && v.canConvertToInt() ? v.asInt() : fallback;
    }

    // ================= 工具 =================

    /** 当前会话的用户 id；拿不到返回 null */
    private static Long meId(Map<String, Object> me) {
        Object id = me.get("id");
        return id instanceof Number n ? n.longValue() : null;
    }

    // ================= 权限（画布级 ACL，方案 C） =================

    private static final String PERM_NONE = "none";
    private static final String PERM_VIEW = "view";
    private static final String PERM_EDIT = "edit";
    private static final String PERM_OWNER = "owner";

    /**
     * 当前用户对某画布的**有效权限**：{@code owner > edit > view > none}。
     *
     * <p>语义（2026-10-10 从方案 B「个人私有 + 超管可见」演进为方案 C「列表公开 + 逐块授权」）：
     * <ul>
     *   <li>画布**列表**对全部 B 端账户可见（见 {@link #list}）—— 发现与授权分开；</li>
     *   <li>超管 → {@code owner}（系统惯例：超管处处短路；也保证总有人能收拾脏数据）；</li>
     *   <li>创建者 → {@code owner}，由 {@code canvas_doc.created_by} 隐含，不落授权表；</li>
     *   <li>被邀请的协作者 → 表里的 {@code view} / {@code edit}；</li>
     *   <li>其他 → {@code none}：**列表里看得见，但打不开、操作栏全灰**。</li>
     * </ul>
     *
     * <p>⚠️ fail-closed：画布不存在 / 拿不到当前用户 id 都返回 {@code none}。
     * 绝不能让「身份解析失败」退化成放行 —— 那是这类改动最容易埋的反向漏洞。
     */
    private static String permOf(Map<String, Object> me, String roomId) {
        if (PermService.isSuperAdmin(me)) return PERM_OWNER;
        Long uid = meId(me);
        if (uid == null) return PERM_NONE;
        Long owner = CanvasDocDao.ownerId(roomId);
        if (owner != null && owner.longValue() == uid) return PERM_OWNER;
        return permissionOf(roomId, uid);
    }

    /** 能否打开（owner / edit / view） */
    private static boolean canRead(String perm) {
        return PERM_OWNER.equals(perm) || PERM_EDIT.equals(perm) || PERM_VIEW.equals(perm);
    }

    /** 能否改（改名 / 写内容）：owner / edit */
    private static boolean canEdit(String perm) {
        return PERM_OWNER.equals(perm) || PERM_EDIT.equals(perm);
    }

    /** 能否删 / 改授权：只有 owner（创建者或超管） */
    private static boolean isOwner(String perm) {
        return PERM_OWNER.equals(perm);
    }

    /** 从「某房间的协作者行」里取某人的权限（不是协作者 → none） */
    private static String permissionIn(List<Map<String, Object>> collabs, Long uid) {
        if (uid == null || collabs == null) return PERM_NONE;
        for (Map<String, Object> c : collabs) {
            if (uid.longValue() == asLong(c.get("user_id"), -2L)) {
                String p = String.valueOf(c.get("permission"));
                return PERM_EDIT.equals(p) ? PERM_EDIT : PERM_VIEW;
            }
        }
        return PERM_NONE;
    }

    /** 直接查授权表取权限（{@link #permOf} 用；手头没有现成的协作者列表可复用） */
    private static String permissionOf(String roomId, long uid) {
        String p = CanvasCollaboratorDao.permissionOf(roomId, uid);
        if (PERM_EDIT.equals(p)) return PERM_EDIT;
        return PERM_VIEW.equals(p) ? PERM_VIEW : PERM_NONE;
    }

    /** id → username（协作者要显示成人名，不是一串 id）。表很小，一次全取比逐条查省事 */
    private static Map<Long, String> userNames() {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> u : UserDao.listUserBriefs()) {
            out.put(asLong(u.get("id"), -1L), String.valueOf(u.get("username")));
        }
        return out;
    }

    /**
     * 给协作者行补上 username（前端直接渲染，不用再自己 join）。
     *
     * <p>返回的每行是**新建**的 LinkedHashMap，不原地改 DAO 的返回值 ——
     * 同一批行可能被别处复用，原地加键会污染。
     */
    private static List<Map<String, Object>> decorate(List<Map<String, Object>> collabs,
                                                      Map<Long, String> names, long ownerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : collabs) {
            Map<String, Object> row = new LinkedHashMap<>(c);
            long uid = asLong(c.get("user_id"), -1L);
            row.put("username", names.getOrDefault(uid, "账户#" + uid));
            row.put("is_owner", uid == ownerId);
            out.add(row);
        }
        return out;
    }

    /** 宽松取 long（MySQL BIGINT 经 Jackson / JDBC 可能是 Integer 或 Long） */
    private static long asLong(Object v, long fallback) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 无权限的统一响应：**刻意不区分**「画布不存在」与「你不是创建者也不是协作者」 */
    private static ResponseEntity<Map<String, Object>> noAccess() {
        return ResponseEntity.status(403).body(Map.of("error", "画布不存在，或你不是它的创建者 / 协作者"));
    }

    /** 生成新房间号（8 字节随机 → 16 位 hex） */
    private static String newRoomId() {
        byte[] b = new byte[8];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 标题规整：去空白；空则用 fallback（fallback 为 null 时返回 null 表示非法） */
    private static String normalizeTitle(String raw, String fallback) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return fallback;
        return t.length() > MAX_TITLE_LEN ? null : t;
    }

    /**
     * 引擎归一化：缺省 → {@link #DEFAULT_ENGINE}；不在白名单 → null（调用方转 400）。
     *
     * <p>刻意**不做**「不认识就落回默认」的宽容处理：引擎写错会导致前端拿一个没有实现的
     * 名字去拼 WS 地址，表现为画布打开即白屏且没有任何报错——宁可建画布时就 400 掉。
     */
    private static String normalizeEngine(String raw) {
        String e = raw == null ? "" : raw.trim().toLowerCase();
        if (e.isEmpty()) return DEFAULT_ENGINE;
        return ENGINES.contains(e) ? e : null;
    }

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.status(400).body(Map.of("error", msg));
    }

    /**
     * 通知协作服务删除房间内容（本机调用）。
     *
     * <p>协作服务会**遍历所有引擎**逐个清（tldraw 的 room_&lt;id&gt;_* 与 excalidraw 的
     * exc_&lt;id&gt;_*），所以这里不需要知道这个画布用的是哪套引擎。
     *
     * <p>best-effort：协作服务不可达**不影响**删除元数据（room_id 随机不复用，残留只占磁盘）；
     * 但失败必须**留痕**——静默吞掉的话，删画布"看起来成功"，实际垃圾在 SQLite 里越堆越多，
     * 只能靠人肉查表发现。
     */
    /**
     * 让协作服务清掉该房间的内容。
     *
     * @return true = 内容已清干净；false = 没清掉（协作服务不可达或返回错误）。
     *         失败**只告警不回滚** —— 调用方已先删了元数据，回滚无从谈起；
     *         这种情况留下的孤儿表由 {@code /api/canvas/reconcile} 负责查出来。
     */
    private static boolean purgeRoom(String roomId) {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(COLLAB_BASE + "/rooms/" + roomId))
                    // ⚠️ 必须显式 HTTP/1.1：HttpClient 默认 HTTP_2，对明文 http:// 会先发
                    //    h2c 升级探测（带 Connection: Upgrade 头）——Node 的 http server 见到
                    //    Upgrade 头会走 'upgrade' 事件而不是 'request'，于是被协作服务的
                    //    「非 WS 路径一律 404」逻辑拒掉，表现为清理永远 404（元数据删了、房间内容留着）
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(3))
                    .DELETE()
                    .build();
            HttpResponse<String> resp = HTTP.send(rq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                System.err.println("[canvas] 清理房间 " + roomId + " 返回 " + resp.statusCode() + "：" + resp.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[canvas] 清理房间 " + roomId + " 失败：" + e);
            return false;
        }
    }
}
