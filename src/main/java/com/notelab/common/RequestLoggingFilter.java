package com.notelab.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求日志与链路 ID（全仓唯一入口）。
 *
 * <p>解决三件事：
 * <ol>
 *   <li><b>链路 ID</b>：优先用 nginx 传来的 {@code X-Request-Id}（nginx 的 {@code $request_id}），
 *       没有就本地生成；写进 MDC 后，本次请求打的所有日志都带 {@code rid=...}，
 *       报错时用户报一个 rid 就能把「前端请求 → nginx → 应用日志」串起来。</li>
 *   <li><b>访问日志</b>：每条请求一行 {@code method path status ms ip}，固定 logger 名 {@code http.access}，
 *       便于用 {@code ops/logs.sh} 按状态码/耗时筛选。</li>
 *   <li><b>响应头回显</b>：把 rid 写回 {@code X-Request-Id}，前端报错弹窗可以直接展示它。</li>
 * </ol>
 *
 * <p>⚠️ 不要把 rid 的来源换成「客户端传什么就用什么」：这里做了字符集与长度校验（≤64，仅 {@code [A-Za-z0-9._-]}），
 * 防止有人往日志里塞换行或超长串。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String RID_HEADER = "X-Request-Id";
    public static final String RID_MDC_KEY = "reqId";

    private static final Logger ACCESS = LoggerFactory.getLogger("http.access");
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String rid = req.getHeader(RID_HEADER);
        if (rid == null || !rid.matches("[A-Za-z0-9._-]{1,64}")) {
            rid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(RID_MDC_KEY, rid);
        res.setHeader(RID_HEADER, rid);

        long started = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            // 这里只补一行「请求失败」的骨架，异常栈由 ApiExceptionHandler / 容器记录，避免重复打两遍
            log.warn("request failed: {} {} -> {}", req.getMethod(), req.getRequestURI(), e.toString());
            throw e;
        } finally {
            long ms = System.currentTimeMillis() - started;
            String path = req.getRequestURI();
            String query = req.getQueryString();
            if (query != null && !query.isEmpty()) {
                path = path + "?" + query;
            }
            ACCESS.info("method={} path={} status={} ms={} ip={} ua=\"{}\"",
                    req.getMethod(), path, res.getStatus(), ms, ClientIp.of(req), truncate(req.getHeader("User-Agent"), 160));
            MDC.remove(RID_MDC_KEY);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
