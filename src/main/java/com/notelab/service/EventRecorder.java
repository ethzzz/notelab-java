package com.notelab.service;

import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import com.notelab.dao.AnalyticsDao;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 埋点记录器（PRD-P0 §4.3 的**服务端旁路**）。
 *
 * <p>为什么要有服务端这一路：登录、注册、判分提交、发布这些动作**客户端也可能漏报**
 * （关页面/被墙/脚本调用），而它们恰恰是权威指标。所以这一路在 Controller 里**同步调用**，
 * 指标一律以服务端记的为准。
 *
 * <p>三条铁律（PRD §4.5 / §12.7）：
 * <ol>
 *   <li>**绝不影响主业务** —— 所有实现体包在 try-catch 里，异常只落 debug 日志并吞掉。
 *       埋点挂了不能连累登录/判分。</li>
 *   <li>**IP 不落明文** —— 入库前 {@code HmacSHA256(ip + 盐)} 取前 16 位。盐走
 *       {@code AppConfig}（服务器 .env 设 {@code ANALYTICS_IP_SALT}），不写死在代码里。</li>
 *   <li>**用户输入不进库** —— props 由调用方只传 id/枚举/数字；这里再兜一层长度校验，
 *       超长直接丢 props 保事件。</li>
 * </ol>
 *
 * <p>⚠️ LLM 依赖：无。
 */
public final class EventRecorder {

    private static final Logger log = LoggerFactory.getLogger(EventRecorder.class);

    /** PRD §4.5.2：已知爬虫/命令行 UA 一律不进库（在入口拦，别等落库再删）。 */
    private static final Pattern BOT = Pattern.compile(
            "bot|crawler|spider|slurp|curl|wget|python-requests|okhttp|httpclient|headless",
            Pattern.CASE_INSENSITIVE);

    /** 客户端 SDK 的 id 形状（16 位小写 hex），非此形状视作没带 */
    private static final Pattern ID16 = Pattern.compile("^[0-9a-f]{16}$");

    private static final String H_ANON = "X-Notelab-Anon";
    private static final String H_SESSION = "X-Notelab-Session";

    private static final int MAX_PROPS_CHARS = 2000;
    private static final int MAX_PATH = 255;
    private static final int MAX_UA = 255;

    /** 身份回填窗口（PRD §4.1：最近 30 分钟内的同 anon_id 事件连成一个人） */
    public static final int BACKFILL_WINDOW_MIN = 30;

    /** 客户端时钟与服务器的最大可容忍偏差（超过就用服务器时间，防止乱造时间戳） */
    private static final long SKEW_MS = 7L * 24 * 3600 * 1000;

    private EventRecorder() {}

    /** 便捷重载：path 取请求实际路径。 */
    public static void record(String app, String event, Long userId, HttpServletRequest request,
                              Map<String, Object> props) {
        record(app, event, userId, request, request == null ? null : request.getRequestURI(), props);
    }

    /**
     * 记一条事件。**永不抛异常**。
     *
     * @param app     'c' = C 端 / 'b' = B 端
     * @param event   事件名（PRD §4.2 清单内的名字）
     * @param userId  已登录用户 id；游客传 null
     * @param request 当前请求（取 IP / UA / 客户端透传的 anon·session）；可为 null
     * @param path    应用内路径（服务端事件填 API 路径）
     * @param props   只放 id/枚举/数字，绝不放用户输入的文本
     */
    public static void record(String app, String event, Long userId, HttpServletRequest request,
                              String path, Map<String, Object> props) {
        try {
            if (event == null || event.isEmpty()) return;

            String ua = request == null ? null : request.getHeader("User-Agent");
            if (ua != null && BOT.matcher(ua).find()) return;   // bot 不落库

            String sessionId = headerId(request, H_SESSION);
            String anonId = headerId(request, H_ANON);

            String propsJson = propsJson(props);
            String ipHash = ipHash(request);

            AnalyticsDao.insert(System.currentTimeMillis(), today(), app, event,
                    sessionId, anonId, userId,
                    clip(path, MAX_PATH), propsJson, ipHash, clip(ua, MAX_UA));
        } catch (Exception e) {
            // 埋点坏了不能连累主业务：静默（只留一行 debug）
            log.debug("埋点失败（已忽略）：event={} err={}", event, e.toString());
        }
    }

    /** 记录 + 身份回填：登录成功后调用，把该 anon_id 最近 30 分钟的游客事件补上 user_id。 */
    public static void recordLoginAndBackfill(String app, HttpServletRequest request, long userId,
                                              String event, Map<String, Object> props) {
        try {
            String anonId = headerId(request, H_ANON);
            if (anonId != null) {
                int n = AnalyticsDao.backfillUser(anonId, userId, BACKFILL_WINDOW_MIN);
                if (n > 0) log.info("埋点身份回填：anon={} → user={}，{} 条", anonId, userId, n);
            }
        } catch (Exception e) {
            log.debug("埋点身份回填失败（已忽略）：{}", e.toString());
        }
        record(app, event, userId, request, props);
    }

    /** 便捷重载：无 props */
    public static void recordLoginAndBackfill(String app, HttpServletRequest request, long userId, String event) {
        recordLoginAndBackfill(app, request, userId, event, null);
    }

    /**
     * 由上报端点调用：把客户端攒好的一条写成行（客户端已做白名单过滤，这里只兜长度）。
     *
     * <p>⚠️ {@code tsMillis} **必须用客户端的事件时间**，不能用服务器收到的时间：一格里攒的
     * 多条事件会在同一毫秒到达，全用服务器时间的话「页面停留」= 全部 0 毫秒，指标直接废掉。
     * 代价是不可信的时钟 —— 所以做一次合法性钳制（偏离服务器时间超过 {@code SKEW_MS} 就用服务器时间），
     * 并按**钳制后的时间**重算 {@code day}（PRD §5 坑 4：日界必须用服务器时区算，不能信客户端给的 day）。
     *
     * @param app 'c' = C 端 / 'b' = B 端（B 端也要 page_view，走同一个上报口）
     * @return 真的落库了返回 true；被 bot 过滤或异常吞掉返回 false（供上报端点回 accepted 计数）
     */
    public static boolean recordClient(String app, String event, long tsMillis, String sessionId, String anonId,
                                       Long userId, String path, String propsJson, String ipHash, String ua) {
        try {
            if (event == null || event.isEmpty()) return false;
            if (ua != null && BOT.matcher(ua).find()) return false;
            long now = System.currentTimeMillis();
            long ts = Math.abs(tsMillis - now) > SKEW_MS ? now : tsMillis;
            AnalyticsDao.insert(ts, dayOf(ts), "b".equals(app) ? "b" : "c", event,
                    sessionId, anonId, userId,
                    clip(path, MAX_PATH),
                    propsJson == null || propsJson.length() > MAX_PROPS_CHARS ? null : propsJson,
                    ipHash, clip(ua, MAX_UA));
            return true;
        } catch (Exception e) {
            log.debug("埋点失败（已忽略）：event={} err={}", event, e.toString());
            return false;
        }
    }

    /**
     * 日界口径 —— ⚠️ **与每日翻译激活（TranslateScheduler 的 {@code LocalDate.now()} / 0 点 cron）
     * 共用同一口径**：服务器本地时区的自然日。改这里等于改「每日英语」的日界，会出现对不上账，
     * 必须两处一起改。
     */
    public static String today() {
        return LocalDate.now().toString();
    }

    /** 与 {@link #today()} 同口径（服务器本地时区），只是从毫秒时间戳换算。 */
    public static String dayOf(long tsMillis) {
        return java.time.Instant.ofEpochMilli(tsMillis)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
    }

    /** 客户端透传的 16 位 hex id（合法才认，否则 null）。 */
    private static String headerId(HttpServletRequest request, String name) {
        if (request == null) return null;
        String v = request.getHeader(name);
        if (v == null) return null;
        v = v.trim().toLowerCase();
        return ID16.matcher(v).matches() ? v : null;
    }

    /** props → JSON 字符串；空则 null；超长丢 props 保事件（服务端兜底长度校验）。 */
    private static String propsJson(Map<String, Object> props) {
        if (props == null || props.isEmpty()) return null;
        try {
            String s = JsonUtil.write(props);
            return s.length() > MAX_PROPS_CHARS ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** IP 只存哈希：HmacSHA256(ip + 盐) 前 16 位。盐从 AppConfig 读（服务器 .env 设 ANALYTICS_IP_SALT）。 */
    public static String ipHash(HttpServletRequest request) {
        if (request == null) return null;
        String ip = clientIp(request);
        if (ip == null || ip.isEmpty()) return null;
        try {
            String salt = AppConfig.analyticsIpSalt();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;   // 哈希失败就不落 IP（宁缺勿明文）
        }
    }

    /** 取客户端 IP（与 AuthUtil.clientIp 同口径：优先 X-Forwarded-For 首个）。不动 controller 包。 */
    private static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("x-forwarded-for");
        if (fwd != null && !fwd.isEmpty()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
