package com.notelab.controller;

import com.notelab.dao.CanvasDocDao;
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
import java.util.LinkedHashMap;
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
 * <p><b>两套引擎</b>：{@code engine} 是画布级属性，建好即固定（两套的文档格式不通用，要换就新建）。
 * 新增引擎时需三处同步登记：本文件的 {@link #ENGINES}、notelab-b 前端 engines 注册表、
 * collab 服务的 ENGINES —— 三处都是显式白名单，不做字符串猜引擎。
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

    /** 列表：q 模糊匹配标题；按最近编辑倒序。**超管看全部，其他人只看自己建的** */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "100") int limit,
                                                    HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        // ownerFilter = null 表示不过滤（只有超管能这样）；其他人强制按 created_by 收窄
        Long ownerFilter = null;
        if (!PermService.isSuperAdmin(me)) {
            ownerFilter = meId(me);
            // ⚠️ fail-closed：拿不到自己的 id 时**不放行**。宁可给空列表，
            //    也绝不能让 ownerFilter 停在 null 退化成「看全部」。
            if (ownerFilter == null) {
                return ResponseEntity.status(403).body(Map.of("error", "无法识别当前账户"));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", CanvasDocDao.list(q, ownerFilter, limit));
        return ResponseEntity.ok(body);
    }

    /** 单个画布元数据（编辑页取标题用） */
    @GetMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String roomId,
                                                   HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        // ⚠️ 本接口同时是**协作服务握手的鉴权入口** —— collab/lib/auth.mjs 会转发用户 cookie
        //    调它，能读到元数据（200）才允许建立 WebSocket。改权限语义时务必兼顾这一处。
        if (!canAccess(me, roomId)) return noAccess();
        // 超管会走到这一行（canAccess 里超管短路放行），所以仍要处理「画布不存在」——
        // 否则会回 200 + null。普通用户到不了这里：不存在的画布在 canAccess 里就是 false
        // → 403，因此这个 404 只可能被超管看到，不会被用来枚举 roomId 是否存在。
        Map<String, Object> row = CanvasDocDao.getByRoom(roomId);
        if (row == null) return ResponseEntity.status(404).body(Map.of("error", "画布不存在"));
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

    /** 重命名 */
    @PutMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable String roomId,
                                                      @RequestBody(required = false) CanvasReq req,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!canAccess(me, roomId)) return noAccess();
        String title = normalizeTitle(req == null ? null : req.title, null);
        if (title == null) return bad("标题为空或过长（上限 " + MAX_TITLE_LEN + " 字）");
        CanvasDocDao.rename(roomId, title);
        return ResponseEntity.ok(Map.of("ok", true, "title", title));
    }

    /**
     * 删除画布：先删元数据，再 best-effort 通知协作服务清房间内容。
     * 清房间失败**不回滚**（room_id 随机不复用，残留数据不影响功能，只占磁盘）。
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String roomId,
                                                      HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!ROOM_ID.matcher(roomId).matches()) return bad("房间号格式非法");
        if (!canAccess(me, roomId)) return noAccess();
        CanvasDocDao.delete(roomId);
        purgeRoom(roomId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ================= 工具 =================

    /** 当前会话的用户 id；拿不到返回 null */
    private static Long meId(Map<String, Object> me) {
        Object id = me.get("id");
        return id instanceof Number n ? n.longValue() : null;
    }

    /**
     * 画布可见性判定：**超管看全部；其他人只能碰自己建的**。
     *
     * <p>语义取舍（2026-10-09 定，方案 B「个人私有 + 超管可见」）：
     * 画布是**个人创作物**，不是公共列表 —— 别人既看不到、也改不了、更删不掉。
     * 将来要演进到方案 C（画布级 ACL / 单独授权、只读分享）时，把本方法换成查共享表即可，
     * 三个调用点（get / rename / delete）与 collab 的握手入口都不用动。
     *
     * <p>⚠️ 「画布不存在」与「不是你的」**都返回 false**，调用方统一回 403 ——
     * 这样响应差异不会泄漏「某个 roomId 是否存在」。
     *
     * <p>⚠️ fail-closed：拿不到当前用户 id 也返回 false。绝不能让「身份解析失败」
     * 退化成「放行」—— 那正是这类改动最容易埋下的反向漏洞。
     */
    private static boolean canAccess(Map<String, Object> me, String roomId) {
        if (PermService.isSuperAdmin(me)) return true;
        Long uid = meId(me);
        if (uid == null) return false;
        Long owner = CanvasDocDao.ownerId(roomId);
        return owner != null && owner.longValue() == uid;
    }

    /** 无权限的统一响应：**刻意不区分**「画布不存在」与「不是你建的」 */
    private static ResponseEntity<Map<String, Object>> noAccess() {
        return ResponseEntity.status(403).body(Map.of("error", "画布不存在或无权访问"));
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
    private static void purgeRoom(String roomId) {
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
            }
        } catch (Exception e) {
            System.err.println("[canvas] 清理房间 " + roomId + " 失败：" + e);
        }
    }
}
