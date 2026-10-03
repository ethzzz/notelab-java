package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.AnalyticsDao;
import com.notelab.service.EventRecorder;
import com.notelab.service.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C 端埋点上报口：{@code POST /api/c/track}（批量）与 {@code POST /api/c/track/identify}（身份回填）。
 *
 * <p><b>为什么是 {@code /api/c/track} 而不是 {@code /api/analytics/events}</b>（PRD §6.3 / §12.5）：
 * 查询端点走 {@code /api/analytics/**}（进 {@link com.notelab.common.PermGuard} 受限前缀，仅超管），
 * 但上报必须**匿名可写**。{@code PermGuard} 是默认拒绝，把它挂到 {@code /api/c/**} 下就直接命中
 * 「C 端体系不参与 B 端隔离」的豁免，不用去动 {@code SESSION_ENDPOINTS} 白名单 —— 最省事且零风险。
 *
 * <p><b>为什么用 {@code @RequestBody String} 而不是 DTO</b>（PRD §4.4 坑 1）：
 * 客户端走 {@code navigator.sendBeacon}，而 sendBeacon **不能带自定义 header**，
 * 于是 Content-Type 只能是 {@code text/plain}，Spring 用 DTO 绑定会直接解析失败。
 * 这里读原始串再自己 {@code JSON.parse}（与 {@link GameSaveController#save} 同一套路，已验证可用）。
 *
 * <p>校验与防线（PRD §7）：单批 ≤ 20 条 / props ≤ 2000 字符 / 事件与 props 键走**白名单**，
 * 任一不合法 → 400 且**整批不落库**；限流 60 次/分钟/IP（Redis，挂则回内存）。
 *
 * <p>⚠️ LLM 依赖：无。
 */
@RestController
@RequestMapping("/api/c/track")
public class CTrackController {

    /** 单批上限（PRD §7.2） —— 与 C 端 lib/track.ts 的 MAX_BATCH 必须一致 */
    private static final int MAX_BATCH = 20;
    private static final int MAX_PROPS_CHARS = 2000;
    private static final int MAX_EVENT_LEN = 64;
    private static final int MAX_PATH_LEN = 255;
    private static final int MAX_STR = 64;

    /** 防前端重复触发的时间窗（PRD §7.3） */
    private static final int DEDUP_SEC = 5;

    private static final Pattern ID16 = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern EVENT_RE = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern KEY_RE = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /**
     * 事件 → 允许的 props 键。**与 {@code notelab-c/lib/track.ts} 的 EVENT_PROPS 同源**
     * （客户端做第一道闸、这里做第二道，A3 验收要求「字段不在白名单」能拒）。
     */
    private static final Map<String, List<String>> EVENT_PROPS = Map.ofEntries(
            Map.entry("page_view", List.of("referrer")),
            Map.entry("game_start", List.of("game_code")),
            Map.entry("spire_node_reached", List.of("act", "depth")),
            Map.entry("translate_day_open", List.of("has_today")),
            Map.entry("translate_submit", List.of("tier", "score_bucket", "grade_mode")),
            Map.entry("register_success", List.of("via")),
            Map.entry("login_success", List.of()),
            Map.entry("tool_open", List.of("tool_id")),
            Map.entry("loot_raid_start", List.of("map_id", "entry_coins")),
            Map.entry("loot_raid_settle", List.of("map_id", "success", "reason_code", "haul", "items", "risk", "containers", "duration_ms")),
            Map.entry("loot_stash_recycle", List.of("items", "gained")),
            Map.entry("loot_rescue_claim", List.of("amount"))
    );

    /** 批量上报。返回 {ok:true, accepted:n, skipped:n}；非法 payload → 400 且不落库。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody(required = false) String raw,
                                                      HttpServletRequest request) {
        String ip = AuthUtil.clientIp(request);
        if (!RateLimit.rateOk("c-track:" + ip, 60, 60)) {
            return ResponseEntity.status(429).body(Map.of("error", "上报过于频繁"));
        }
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "空请求体"));
        }
        JsonNode root;
        try {
            root = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "不是合法 JSON"));
        }
        if (root == null || !root.isObject() || !root.has("events") || !root.get("events").isArray()) {
            return ResponseEntity.status(400).body(Map.of("error", "events 必须是数组"));
        }
        JsonNode arr = root.get("events");
        if (arr.size() > MAX_BATCH) {
            return ResponseEntity.status(400).body(Map.of("error", "单批最多 " + MAX_BATCH + " 条"));
        }

        // 先全量校验再落库：任一条不合法 → 整批 400，不产生半截数据（A3）
        String ua = request.getHeader("User-Agent");
        String ipHash = null;   // 懒算：有可落库的条时才 hash
        Map<String, Object> user = CAuthUtil.user(request);
        Long sessionUid = user == null ? null : CAuthUtil.userId(user);

        List<JsonNode> pending = new java.util.ArrayList<>();
        for (JsonNode ev : arr) {
            try {
                validate(ev);
            } catch (IllegalArgumentException bad) {
                return ResponseEntity.status(400).body(Map.of("error", bad.getMessage()));
            }
            pending.add(ev);
        }

        int accepted = 0, skipped = 0;
        for (JsonNode ev : pending) {
            String event = ev.get("event").asText();
            String sessionId = str(ev, "session_id");
            String anonId = str(ev, "anon_id");
            if (AnalyticsDao.existsRecent(sessionId, event, DEDUP_SEC)) { skipped++; continue; }
            if (ipHash == null) ipHash = EventRecorder.ipHash(request);
            Long evUid = ev.hasNonNull("user_id") ? ev.get("user_id").asLong() : null;
            if (sessionUid != null) evUid = sessionUid;   // 有会话就以服务端为准，不信客户端自报
            boolean stored = EventRecorder.recordClient(event,
                    ev.hasNonNull("ts") ? ev.get("ts").asLong() : 0L,
                    ID16.matcher(sessionId).matches() ? sessionId : null,
                    ID16.matcher(anonId).matches() ? anonId : null,
                    evUid,
                    clip(str(ev, "path"), MAX_PATH_LEN),
                    ev.has("props") && ev.get("props").isObject() ? ev.get("props").toString() : null,
                    ipHash, ua);
            if (stored) accepted++;
            else skipped++;   // bot 过滤 / 落库异常都记 skipped（A5：curl UA 落库 0 条）
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("accepted", accepted);
        out.put("skipped", skipped);
        return ResponseEntity.ok(out);
    }

    /**
     * 身份回填：登录后调用一次，把该 anon_id 最近 30 分钟的游客事件补上 user_id。
     * user_id **取自 C 端会话**（不信客户端自报），所以必须先登录。
     */
    @PostMapping("/identify")
    public ResponseEntity<Map<String, Object>> identify(@RequestBody(required = false) String raw,
                                                        HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        JsonNode root;
        try {
            root = raw == null ? null : JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "不是合法 JSON"));
        }
        String anonId = root == null ? "" : str(root, "anon_id").toLowerCase();
        if (!ID16.matcher(anonId).matches()) {
            return ResponseEntity.status(400).body(Map.of("error", "anon_id 非法"));
        }
        int merged = AnalyticsDao.backfillUser(anonId, CAuthUtil.userId(user), EventRecorder.BACKFILL_WINDOW_MIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("merged", merged);
        return ResponseEntity.ok(out);
    }

    /** 单条事件校验（不合法抛 IllegalArgumentException → 上层 400）。 */
    private static void validate(JsonNode ev) {
        if (ev == null || !ev.isObject()) throw new IllegalArgumentException("事件必须是对象");
        String event = str(ev, "event");
        if (!EVENT_RE.matcher(event).matches()) throw new IllegalArgumentException("event 非法: " + event);
        String path = str(ev, "path");
        if (path.length() > MAX_PATH_LEN) throw new IllegalArgumentException("path 过长");
        JsonNode props = ev.get("props");
        if (props == null || props.isNull()) return;
        if (!props.isObject()) throw new IllegalArgumentException("props 必须是对象");
        if (props.toString().length() > MAX_PROPS_CHARS) throw new IllegalArgumentException("props 过长");
        List<String> allow = EVENT_PROPS.get(event);
        var it = props.fields();
        while (it.hasNext()) {
            var f = it.next();
            String k = f.getKey();
            if (!KEY_RE.matcher(k).matches()) throw new IllegalArgumentException("props 键非法: " + k);
            // 已登记事件：严格白名单（A3 要能拒"字段不在白名单"）；
            // 未登记事件：只查键形状 —— 防两侧清单不同步时把整批打成 400 丢数据
            if (allow != null && !allow.contains(k)) throw new IllegalArgumentException("props 字段不在白名单: " + k);
            JsonNode v = f.getValue();
            if (v.isNumber() || v.isBoolean()) continue;
            if (v.isTextual() && v.asText().length() <= MAX_STR) continue;
            throw new IllegalArgumentException("props 值非法: " + k);
        }
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
