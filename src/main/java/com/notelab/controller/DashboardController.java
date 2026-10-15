package com.notelab.controller;

import com.notelab.dao.AnalyticsDao;
import com.notelab.dao.DashboardDao;
import com.notelab.dao.PracticeImageDao;
import com.notelab.service.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 仪表盘聚合口：{@code GET /api/dashboard/stats}。
 *
 * <h3>为什么这个前缀进的是「会话端点」而不是权限码</h3>
 * 仪表盘是每个登录用户的**落地页**（{@code /api/menu} 里 path 为 {@code /} 的那项），
 * 和 {@code /api/me}、{@code /api/menu} 同类 —— 若按权限码发放，新角色默认没勾就会
 * 打开一片空白，而这种「基础壳数据拿不到」的故障极难自查。
 * 所以 {@code /api/dashboard} 已加入 {@link com.notelab.common.PermGuard#SESSION_ENDPOINTS}：
 * **任何已登录用户都能读**。
 *
 * <h3>那么经营数据怎么不越权</h3>
 * 靠**响应裁剪**而不是靠门禁：全站活跃趋势、登录审计属「经营 / 安全数据」
 * （与 {@code /api/analytics} 只给超管同一条纪律），非超管请求时这两个区块直接返回 {@code null}，
 * 前端据此不渲染。{@code scope} 字段把裁剪结果明说，避免前端靠「字段为 null」猜。
 *
 * <p>⚠️ 新 Controller 必须**重启后端**才会进 {@code perm_routes}（{@code PermService.registerAllRoutes()}）。
 *
 * <p>LLM 依赖：无 —— 纯 SQL 聚合。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    /** 内容产出趋势窗口（天） */
    private static final int TREND_DAYS = 14;
    /** 登录审计窗口（天） */
    private static final int LOGIN_TREND_DAYS = 7;
    /** C 端埋点 app 码（活跃趋势只看 C 端 —— B 端后台自用的埋点混进来会把曲线拖平） */
    private static final String APP_C = "c";
    /** B 端登录审计 app 码 */
    private static final String APP_B = "b";

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        boolean superAdmin = PermService.isSuperAdmin(me);

        LocalDate today = LocalDate.now();
        String to = today.toString();
        String from = today.minusDays(TREND_DAYS - 1L).toString();
        String loginFrom = today.minusDays(LOGIN_TREND_DAYS - 1L).toString();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", superAdmin ? "full" : "basic");
        data.put("days", TREND_DAYS);
        data.put("from", from);
        data.put("to", to);
        data.put("assets", assets());
        data.put("contentTrend", contentTrend(from, to));
        data.put("practice", practice());
        // 下面两块是经营 / 安全数据：非超管拿到 null（前端不渲染），而不是拿到一份假的空数据
        data.put("activeTrend", superAdmin ? activeTrend(from, to) : null);
        data.put("loginTrend", superAdmin ? loginTrend(loginFrom, to) : null);
        return ok(data);
    }

    // ==================== 区块：资产指标卡 ====================

    /**
     * 资产规模指标卡。每张卡带 {@code path}，前端点卡片直接跳对应模块
     * —— 这是原「功能入口网格」的信息量来源，但比罗列所有菜单更省地方。
     */
    private static List<Map<String, Object>> assets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(card("bUsers", "B 端账号", DashboardDao.count("users"), "个", "/user/accounts"));
        out.add(card("cUsers", "C 端用户", DashboardDao.count("c_users"), "个", "/c-users"));
        out.add(card("notes", "笔记", DashboardDao.count("notes"), "篇", "/notes"));
        out.add(card("documents", "文档", DashboardDao.count("documents"), "篇", "/docs"));
        out.add(card("canvas", "协作画布", DashboardDao.count("canvas_doc"), "个", "/canvas"));
        out.add(card("practice", "练习记录", DashboardDao.count("practice_record"), "条", "/practice/dev-summary"));
        out.add(card("lowcode", "低代码提交", DashboardDao.count("lowcode_records"), "条", "/lowcode"));

        // 配图单独一张：张数之外还要看**占用**（图片存库，体积涨了在列表上完全看不出来）
        Map<String, Object> imgs = PracticeImageDao.summary();
        Map<String, Object> imgCard = card("practiceImages", "练习配图",
                lng(imgs.get("count")), "张", "/practice/dev-summary");
        imgCard.put("hint", humanBytes(lng(imgs.get("bytes"))) + " 已占用");
        out.add(imgCard);
        return out;
    }

    // ==================== 区块：内容产出趋势 ====================

    /** 近 N 天每日新增的笔记 / 文档 / 练习记录（三条曲线共用一张图）。 */
    private static List<Map<String, Object>> contentTrend(String from, String to) {
        Map<String, Map<String, Object>> byDay = new TreeMap<>();
        List<String> keys = List.of("notes", "documents", "practice");
        fill(byDay, DashboardDao.dailyNew("notes", from, to), "notes", keys);
        fill(byDay, DashboardDao.dailyNew("documents", from, to), "documents", keys);
        fill(byDay, DashboardDao.dailyNew("practice_record", from, to), "practice", keys);
        return padDays(byDay, from, to, keys);
    }

    // ==================== 区块：练习实践构成 ====================

    private static Map<String, Object> practice() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> r : DashboardDao.practiceByType()) {
            Map<String, Object> one = new LinkedHashMap<>();
            String t = r.get("type") == null ? "" : String.valueOf(r.get("type"));
            one.put("type", t.isEmpty() ? "未分类" : t);
            one.put("n", lng(r.get("n")));
            byType.add(one);
        }
        m.put("byType", byType);
        m.put("published", DashboardDao.practicePublished());
        m.put("total", DashboardDao.count("practice_record"));
        return m;
    }

    // ==================== 区块：C 端活跃（仅超管） ====================

    private static List<Map<String, Object>> activeTrend(String from, String to) {
        Map<String, Map<String, Object>> byDay = new TreeMap<>();
        List<String> keys = List.of("dau", "events");
        for (Map<String, Object> r : AnalyticsDao.dailyActive(APP_C, from, to)) {
            String day = normDay(r.get("day"));
            if (day.isEmpty()) continue;
            Map<String, Object> m = byDay.computeIfAbsent(day, d -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("day", d);
                x.put("dau", 0L);
                x.put("events", 0L);
                return x;
            });
            m.put("dau", lng(r.get("dau")));
            m.put("events", lng(r.get("events")));
        }
        return padDays(byDay, from, to, keys);
    }

    // ==================== 区块：登录审计（仅超管） ====================

    /**
     * 近 N 天 B 端登录成功 / 失败。
     *
     * <p>结果归类：{@code login_audit.result} 有 success / bad_password / no_such_user /
     * disabled / rate_limited / bad_request 多种取值（见 {@code service/LoginAudit}），
     * 图上只分「成功」与「失败」两色 —— 因此这里显式折叠，
     * 而不是把 service 的枚举漏到前端（那样以后加一种失败原因，图例就会多出一条）。
     */
    private static List<Map<String, Object>> loginTrend(String from, String to) {
        Map<String, Map<String, Object>> byDay = new TreeMap<>();
        List<String> keys = List.of("ok", "fail");
        for (Map<String, Object> r : DashboardDao.loginDaily(APP_B, from, to)) {
            String day = normDay(r.get("day"));
            if (day.isEmpty()) continue;
            boolean success = "success".equals(String.valueOf(r.get("result")));
            Map<String, Object> m = byDay.computeIfAbsent(day, d -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("day", d);
                x.put("ok", 0L);
                x.put("fail", 0L);
                return x;
            });
            m.put(success ? "ok" : "fail", lng(r.get("n")));
        }
        return padDays(byDay, from, to, keys);
    }

    // ==================== 工具 ====================

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("data", data);
        return ResponseEntity.ok(m);
    }

    private static Map<String, Object> card(String key, String label, long value, String unit, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("unit", unit);
        m.put("path", path);
        return m;
    }

    /** 把某张表的按天新增合并进 day → 行 的索引 */
    private static void fill(Map<String, Map<String, Object>> byDay, List<Map<String, Object>> rows,
                             String key, List<String> keys) {
        for (Map<String, Object> r : rows) {
            String day = normDay(r.get("day"));
            if (day.isEmpty()) continue;
            Map<String, Object> m = byDay.computeIfAbsent(day, d -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("day", d);
                for (String k : keys) x.put(k, 0L);
                return x;
            });
            m.put(key, lng(r.get("n")));
        }
    }

    /**
     * 补齐成 from→to 的**连续**日期，缺的天填 0。
     *
     * <p>⚠️ 不补的话，某天没有新增就直接没有那一行 —— 折线图会把 10-01 与 10-05 直接连起来，
     * 看起来像「这几天一直有产出」，而那几天其实是 0。这是趋势图最容易骗人的一处。
     */
    private static List<Map<String, Object>> padDays(Map<String, Map<String, Object>> byDay,
                                                     String from, String to, List<String> keys) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate d = LocalDate.parse(from);
        LocalDate end = LocalDate.parse(to);
        while (!d.isAfter(end)) {
            String ds = d.toString();
            Map<String, Object> row = byDay.get(ds);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("day", ds);
                for (String k : keys) row.put(k, 0L);
            }
            out.add(row);
            d = d.plusDays(1);
        }
        return out;
    }

    /** JDBC 的 DATE 列可能是 java.sql.Date / Timestamp / String —— 一律取前 10 位成 yyyy-MM-dd */
    private static String normDay(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024d;
        if (kb < 1024) return round1(kb) + " KB";
        double mb = kb / 1024d;
        if (mb < 1024) return round1(mb) + " MB";
        return round1(mb / 1024d) + " GB";
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
