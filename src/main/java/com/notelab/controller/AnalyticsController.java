package com.notelab.controller;

import com.notelab.dao.AnalyticsDao;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据看板查询口（PRD-P0 §6.1）：{@code /api/analytics/**}，**仅超管**。
 *
 * <p>权限怎么生效：本前缀已加入 {@link com.notelab.common.PermGuard#RESTRICTED_PREFIXES}，
 * 已登录的非超管会被拦截器挡掉；未登录请求（拦截器刻意放行）由本类方法体的 401 兜住。
 * ⚠️ 新 Controller 必须**重启后端**路由才进权限表（`PermService.registerAllRoutes()`），
 * 否则路径匹配不上，会被默认拒绝策略判成未登记接口。
 *
 * <p>响应统一 {@code {ok:true, data:{...}}}（与现有 Controller 风格一致）。
 *
 * <p>日界口径：{@code day} 与每日翻译激活同源（{@code TranslateScheduler} 的 0 点 cron / {@code LocalDate.now()}）。
 *
 * <p>⚠️ LLM 依赖：无 —— key 全挂时看板照常出数（A7）。
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    /** 看板一次最多看 90 天（与清理保留期一致，超了也查不到明细） */
    private static final int MAX_DAYS = 90;
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_LIMIT = 100;

    /** C 端 app 码（DAU/留存只算 C 端；B 端行为另算，见 /top?app=b） */
    private static final String APP_C = "c";

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(required = false) String days,
                                                        HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int d = daysOf(days);
        String to = LocalDate.now().toString();
        String from = LocalDate.now().minusDays(d - 1L).toString();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", d);
        data.put("from", from);
        data.put("to", to);
        data.put("dau", AnalyticsDao.dau(APP_C, to));
        data.put("newUsers", AnalyticsDao.newUsers(APP_C, to));
        data.put("events", AnalyticsDao.eventTotal(APP_C, from, to));
        data.put("wau", AnalyticsDao.rangeActive(APP_C,
                LocalDate.now().minusDays(6).toString(), to));
        data.put("mau", AnalyticsDao.rangeActive(APP_C,
                LocalDate.now().minusDays(29).toString(), to));
        data.put("activeSeries", AnalyticsDao.dailyActive(APP_C, from, to));
        data.put("newSeries", AnalyticsDao.newUsersByDay(APP_C, from, to));

        List<Map<String, Object>> r1 = AnalyticsDao.retention(APP_C, from, to, 1);
        List<Map<String, Object>> r7 = AnalyticsDao.retention(APP_C, from, to, 7);
        data.put("retentionD1", retentionRate(r1, 1, to));
        data.put("retentionD7", retentionRate(r7, 7, to));
        data.put("retentionD1Matrix", r1);
        data.put("retentionD7Matrix", r7);
        return ok(data);
    }

    @GetMapping("/series")
    public ResponseEntity<Map<String, Object>> series(@RequestParam(required = false) String event,
                                                      @RequestParam(required = false) String app,
                                                      @RequestParam(required = false) String days,
                                                      HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int d = daysOf(days);
        String to = LocalDate.now().toString();
        String from = LocalDate.now().minusDays(d - 1L).toString();
        String ev = event == null || event.isBlank() ? null : event.trim();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", ev);
        data.put("days", d);
        data.put("points", AnalyticsDao.series(appOf(app), ev, from, to));
        return ok(data);
    }

    @GetMapping("/top")
    public ResponseEntity<Map<String, Object>> top(@RequestParam(required = false) String app,
                                                   @RequestParam(required = false) String limit,
                                                   @RequestParam(required = false) String days,
                                                   HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int d = daysOf(days);
        int lim = limitOf(limit);
        String to = LocalDate.now();
        String from = to.minusDays(d - 1L).toString();
        String toS = to.toString();
        String a = appOf(app);

        List<Map<String, Object>> cur = AnalyticsDao.top(a, from, toS, lim);
        // 环比：同长度的上一个窗口
        String prevTo = to.minusDays(d).toString();
        String prevFrom = to.minusDays(2L * d - 1).toString();
        Map<String, Long> prev = new LinkedHashMap<>();
        for (Map<String, Object> r : AnalyticsDao.top(a, prevFrom, prevTo, MAX_LIMIT)) {
            prev.put(String.valueOf(r.get("event")), lng(r.get("n")));
        }

        long total = 0;
        for (Map<String, Object> r : cur) total += lng(r.get("n"));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : cur) {
            String ev = String.valueOf(r.get("event"));
            long n = lng(r.get("n"));
            long p = prev.getOrDefault(ev, 0L);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("event", ev);
            out.put("count", n);
            out.put("users", lng(r.get("users")));
            out.put("pct", total == 0 ? 0d : Math.round(n * 10000d / total) / 100d);
            out.put("prev", p);
            out.put("delta", p == 0 ? null : Math.round((n - p) * 1000d / p) / 10d);
            items.add(out);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", a);
        data.put("days", d);
        data.put("total", total);
        data.put("items", items);
        return ok(data);
    }

    @GetMapping("/funnel")
    public ResponseEntity<Map<String, Object>> funnel(@RequestParam(required = false) String days,
                                                      HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int d = daysOf(days);
        String to = LocalDate.now().toString();
        String from = LocalDate.now().minusDays(d - 1L).toString();

        // 注册 → 登录 → 打开游戏 → 游戏内 3 分钟（PRD §5 漏斗口径）
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("register", "注册成功", AnalyticsDao.peopleWith(APP_C, "register_success", from, to)));
        steps.add(step("login", "登录成功", AnalyticsDao.peopleWith(APP_C, "login_success", from, to)));
        steps.add(step("game_start", "打开游戏", AnalyticsDao.peopleWith(APP_C, "game_start", from, to)));
        steps.add(step("game_3min", "玩到 3 分钟",
                AnalyticsDao.playedGameWithin(APP_C, from, to, 180)));
        // 逐级转化率（相对上一步）
        long prev = 0;
        for (int i = 0; i < steps.size(); i++) {
            long n = lng(steps.get(i).get("count"));
            steps.get(i).put("fromPrev", i == 0 || prev == 0 ? null : Math.round(n * 1000d / prev) / 10d);
            prev = n;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", d);
        data.put("steps", steps);
        return ok(data);
    }

    @GetMapping("/pages")
    public ResponseEntity<Map<String, Object>> pages(@RequestParam(required = false) String app,
                                                     @RequestParam(required = false) String days,
                                                     HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        int d = daysOf(days);
        String to = LocalDate.now().toString();
        String from = LocalDate.now().minusDays(d - 1L).toString();
        String a = appOf(app);

        Map<String, List<Long>> dwell = new LinkedHashMap<>();
        for (Map<String, Object> r : AnalyticsDao.pageDwell(a, from, to, 1800)) {
            String path = r.get("path") == null ? "(unknown)" : String.valueOf(r.get("path"));
            dwell.computeIfAbsent(path, k -> new ArrayList<>()).add(lng(r.get("dwell_ms")));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : AnalyticsDao.pagePv(a, from, to)) {
            String path = r.get("path") == null ? "(unknown)" : String.valueOf(r.get("path"));
            List<Long> ds = dwell.get(path);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("pv", lng(r.get("pv")));
            // p50/p95 而不是均值：停留时长有长尾，均值会被少数超长会话带偏（PRD §5）
            out.put("p50", percentile(ds, 50));
            out.put("p95", percentile(ds, 95));
            items.add(out);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", a);
        data.put("days", d);
        data.put("items", items);
        return ok(data);
    }

    // ==================== 工具 ====================

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("data", data);
        return m;
    }

    private static int daysOf(String days) {
        if (days == null || days.isBlank()) return DEFAULT_DAYS;
        try {
            return Math.max(1, Math.min(MAX_DAYS, Integer.parseInt(days.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_DAYS;
        }
    }

    private static int limitOf(String limit) {
        if (limit == null || limit.isBlank()) return 20;
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(limit.trim())));
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    private static String appOf(String app) {
        return "b".equals(app) ? "b" : APP_C;
    }

    /**
     * 留存率：窗口内各日 cohort 的加权（Σretained / Σcohort）。
     * ⚠️ 必须**剔除窗口最后 offset 天**：那些天还等不到 D+offset 的数据，留着会把留存率稀释成假的低值。
     */
    private static Map<String, Object> retentionRate(List<Map<String, Object>> rows, int offset, String to) {
        LocalDate cutoff = LocalDate.parse(to).minusDays(offset);
        long cohort = 0, retained = 0;
        for (Map<String, Object> r : rows) {
            Object dayObj = r.get("day");
            if (dayObj == null) continue;
            LocalDate day = LocalDate.parse(String.valueOf(dayObj).substring(0, 10));
            if (day.isAfter(cutoff)) continue;
            cohort += lng(r.get("cohort"));
            retained += lng(r.get("retained"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cohort", cohort);
        m.put("retained", retained);
        m.put("rate", cohort == 0 ? null : Math.round(retained * 1000d / cohort) / 10d);
        return m;
    }

    private static Map<String, Object> step(String key, String label, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("count", count);
        return m;
    }

    /** 线性插值分位（样本已按时间顺序，这里先排序再取位）。空样本返回 null。 */
    private static Long percentile(List<Long> xs, int p) {
        if (xs == null || xs.isEmpty()) return null;
        List<Long> s = new ArrayList<>(xs);
        s.sort(null);
        if (s.size() == 1) return s.get(0);
        double rank = (p / 100d) * (s.size() - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return s.get(lo);
        return Math.round(s.get(lo) + (s.get(hi) - s.get(lo)) * (rank - lo));
    }

    private static long lng(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
