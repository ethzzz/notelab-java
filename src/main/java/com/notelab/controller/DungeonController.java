package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notelab.common.JsonUtil;
import com.notelab.dao.GameSaveDao;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 《地牢领主》存档：走 C 端体系（{@code /api/c/} 豁免 B 端接口门禁，由 CAuthUtil 守卫）。
 *
 * <p>为什么单独开一个控制器而不是直接用 {@code /api/c/game/save}：
 * 本作是**长期养成**，读档必须拿到「服务端当前时间 + 上次离开时间」，
 * 客户端才能按**服务端时间差**补算离线收益 —— 时间源在服务端，改本机时间就无效了。
 *
 * <p>分工（重要，决定了作弊面）：
 * <ul>
 *   <li>服务端：提供可信时间基准、存档、并对**离线收益做上限校验**（不逐项模拟）。</li>
 *   <li>客户端：按 offlineSec 用同一套规则算出具体收益，再把结果存回。</li>
 * </ul>
 * 即"收益规则在客户端、收益上限在服务端"：单机养成游戏这样最划算 ——
 * 想彻底反作弊就得把整套模拟搬进服务端（留到 M4 视需要再补）。
 */
@RestController
@RequestMapping("/api/c/dungeon")
public class DungeonController {

    private static final String GAME = "dungeon";

    /** 单份存档上限（防把存档当网盘用） */
    private static final int MAX_JSON_BYTES = 256 * 1024;

    /** 离线收益上限：金币/秒。超出即拒绝存档（数值按平衡再调，目前给得很宽松） */
    private static final long MAX_GOLD_PER_SEC = 5;

    /** 手动游玩（收菜/卖东西）单次允许的金��增量容差，避免正常操作被上限误伤 */
    private static final long MANUAL_SLACK = 100_000;

    /** 可离线结算的最大时长：超过 12 小时的部分不产出（也是上限的一部分） */
    private static final long MAX_OFFLINE_SEC = 12 * 3600;

    /** 首版层数上限：与前端 FLOORS 保持一致，越界的派遣不予结算 */
    private static final int MAX_FLOOR = 10;

    /** 读档：返回存档 + 服务端时间 + 离线秒数 */
    @GetMapping("/save")
    public ResponseEntity<Map<String, Object>> load(HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);

        Map<String, Object> row = GameSaveDao.get(uid, GAME);
        long now = System.currentTimeMillis();
        Long lastSeen = row == null ? null : tsOf(row.get("updated_at"));
        long offlineSec = lastSeen == null ? 0 : Math.max(0, (now - lastSeen) / 1000);

        Object data = null;
        if (row != null && row.get("data_json") != null) {
            String raw = String.valueOf(row.get("data_json"));
            try {
                data = JsonUtil.MAPPER.readValue(raw, Object.class);
            } catch (Exception e) {
                data = raw; // 解析不了就原样回传，交给前端决定（新版本结构会走迁移）
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        body.put("serverNow", now);
        body.put("lastSeenAt", lastSeen);
        body.put("offlineSec", Math.min(offlineSec, MAX_OFFLINE_SEC));
        body.put("offlineCapped", offlineSec > MAX_OFFLINE_SEC);
        return ResponseEntity.ok(body);
    }

    /** 存档：校验体积 + 版本号 + 离线收益上限，然后落库（updated_at 即下次的 lastSeen） */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);
        if (raw == null || raw.isBlank()) return bad("空存档");
        if (raw.length() > MAX_JSON_BYTES) return bad("存档超过 " + (MAX_JSON_BYTES / 1024) + "KB");

        JsonNode n;
        try {
            n = JsonUtil.MAPPER.readTree(raw);
        } catch (Exception e) {
            return bad("存档不是合法 JSON");
        }
        JsonNode data = n.get("data");
        if (data == null || !data.isObject()) return bad("缺少 data 对象");
        int version = n.path("version").asInt(1);
        if (version < 1) return bad("version 非法");

        // 收益上限校验：拿旧档的金币与新档比对，涨幅不得超过「离线时长 × 每秒上限 + 手动容差」。
        // 无旧档（首次写入）同样受限 —— 否则新号可以直接 POST 一笔天文数字的"初始存档"。
        Map<String, Object> old = GameSaveDao.get(uid, GAME);
        long newGold = goldOf(data);
        if (old == null || old.get("data_json") == null) {
            if (newGold > MANUAL_SLACK) {
                return bad("初始存档金币 " + newGold + " 超出上限 " + MANUAL_SLACK);
            }
        } else {
            String oldRaw = String.valueOf(old.get("data_json"));
            long oldGold = goldOfJson(oldRaw);
            Long lastSeen = tsOf(old.get("updated_at"));
            long offlineSec = lastSeen == null ? 0 : Math.min(MAX_OFFLINE_SEC, Math.max(0, (System.currentTimeMillis() - lastSeen) / 1000));
            long cap = offlineSec * MAX_GOLD_PER_SEC + MANUAL_SLACK;
            long delta = newGold - oldGold;
            if (delta > cap) {
                return bad("本次金币增量 " + delta + " 超出上限 " + cap + "（离线 " + offlineSec + "s）");
            }
            // 版本回退防护：旧档版本比新档还新，多半是并发写坏了或拿旧档覆盖，拒绝
            int oldVersion = oldVersionOf(oldRaw);
            if (oldVersion > 0 && version < oldVersion) {
                return bad("存档版本 " + version + " 低于服务端已有的 " + oldVersion + "，疑似旧档覆盖，已拒绝");
            }
        }

        try {
            GameSaveDao.upsert(uid, GAME, JsonUtil.MAPPER.writeValueAsString(data));
        } catch (Exception e) {
            return bad("序列化失败：" + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("savedAt", System.currentTimeMillis());
        return ResponseEntity.ok(body);
    }

    /**
     * 结算闸门：派遣归来的战利品由客户端按规则算出再存回（规则只写一份，避免前后端数值漂移），
     * 但**"能不能结算"由服务端说了算** —— 服务端只在 endsAt（服务端时间）真的到了才盖章。
     * 这样改本机时间、或直接伪造一份"已完成"的存档，都过不了这道闸门。
     */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collect(@RequestBody(required = false) String raw,
                                                       HttpServletRequest request) {
        Map<String, Object> user = CAuthUtil.user(request);
        if (user == null) return CAuthUtil.unauth();
        long uid = CAuthUtil.userId(user);

        JsonNode n;
        try {
            n = raw == null ? null : JsonUtil.MAPPER.readTree(raw);
        } catch (Exception e) {
            return bad("请求不是合法 JSON");
        }
        if (n == null) return bad("缺少参数");
        String id = n.path("id").asText("");
        if (id.isEmpty()) return bad("缺少队伍 id");

        Map<String, Object> row = GameSaveDao.get(uid, GAME);
        if (row == null || row.get("data_json") == null) return bad("还没有存档");
        String saveRaw = String.valueOf(row.get("data_json"));

        JsonNode root;
        try {
            root = JsonUtil.MAPPER.readTree(saveRaw);
        } catch (Exception e) {
            return bad("存档无法解析");
        }
        if (!root.isObject()) return bad("存档格式不对");

        JsonNode parties = root.path("parties");
        if (!parties.isArray()) return bad("存档里没有出征记录");

        long now = System.currentTimeMillis();
        ObjectNode target = null;
        for (JsonNode p : parties) {
            if (p.isObject() && id.equals(p.path("id").asText())) { target = (ObjectNode) p; break; }
        }
        if (target == null) return bad("找不到这支出征队伍");
        if (target.path("collected").asBoolean(false)) return bad("这队已经结算过了");

        int floor = target.path("floor").asInt(0);
        if (floor < 1 || floor > MAX_FLOOR) return bad("层数 " + floor + " 不合法");

        long endsAt = target.path("endsAt").asLong(0);
        if (endsAt <= 0) return bad("这队没有归来时间");
        if (now < endsAt) {
            return bad("队伍还在路上，还需 " + Math.max(1, (endsAt - now) / 1000) + " 秒");
        }

        // 盖章：标记已结算（真正的战利品由客户端算完再走 /save 存回，受金币上限约束）
        target.put("collected", true);
        try {
            GameSaveDao.upsert(uid, GAME, JsonUtil.MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            return bad("写档失败：" + e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("id", id);
        body.put("floor", floor);
        body.put("serverNow", now);
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ 工具

    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static long goldOf(JsonNode data) {
        JsonNode g = data.path("res").path("gold");
        return g.isNumber() ? g.asLong() : 0;
    }

    private static long goldOfJson(String raw) {
        try {
            return goldOf(JsonUtil.MAPPER.readTree(raw));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 旧档自身的版本号（data.v）；解析不了返回 0（视为未知，不做回退比较） */
    private static int oldVersionOf(String raw) {
        try {
            JsonNode n = JsonUtil.MAPPER.readTree(raw);
            JsonNode v = n.path("v");
            return v.isNumber() ? v.asInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** updated_at 可能是 Date / LocalDateTime / 时间戳 / 字符串，逐个试 */
    private static Long tsOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number num) return num.longValue();
        if (v instanceof Date d) return d.getTime();
        if (v instanceof LocalDateTime t) return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignore) { /* 不是 ISO-Z 格式 */ }
        try {
            return LocalDateTime.parse(s.replace(" ", "T")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignore) { /* 也不是本地时间格式 */ }
        try {
            return Long.parseLong(s);
        } catch (Exception ignore) { /* 放弃 */ }
        return null;
    }
}
