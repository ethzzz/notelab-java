package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
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
        body.put("items", CanvasDocDao.list(q, engine, ownerFilter, limit));
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
        // 元数据删除不可回滚，所以内容清不掉也不能报错回滚；但要**如实告知**，
        // 否则用户以为删干净了，实际在协作服务里留了一张孤儿表（「对账」能查出来并清理）。
        if (!purgeRoom(roomId)) {
            return ResponseEntity.ok(Map.of("ok", true, "purged", false,
                    "warning", "元数据已删除，但协作服务未能清理房间内容（可能它没在运行）。"
                            + "已留下孤儿表，可在「对账」里查看并清理。"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "purged", true));
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
        JsonNode inv = collabInventory();
        if (inv == null) return collabUnreachable();

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
        return ResponseEntity.ok(Map.of("ok", true, "removed", removed, "removedCount", removed.size()));
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
