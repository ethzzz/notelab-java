package com.notelab.controller;

import com.notelab.service.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接口压测（服务端代理转发）：前缀 {@code /api/admin}（受限前缀，仅 super_admin）。
 *
 * <p>由服务端真正并发发请求，绕过浏览器 CORS、能产生持续并发负载。
 * 经 {@link SseEmitter} 实时回传进度，结束回传汇总（QPS / 延迟分位 / 状态码分布 / 错误样本）。
 *
 * <p>⚠️ 仅 super_admin：本接口可让服务器向任意 URL 发起大量并发请求（SSRF / DoS 风险），
 * 因此归入受限前缀、普通角色默认既无页面入口也无接口权限码。
 */
@RestController
@RequestMapping("/api/admin/stress-test")
public class StressTestController {

    /** 单次压测的内存与负载上限（防滥用 / OOM） */
    private static final int MAX_CONCURRENCY = 200;
    private static final int MAX_TOTAL = 100_000;
    private static final int MAX_DURATION_SEC = 600;
    private static final int MAX_RAMP_SEC = 600;
    private static final int MAX_LATENCY_SAMPLES = 500_000;
    private static final int MAX_ERROR_SAMPLES = 50;
    private static final int READ_BODY_CAP = 1 << 20; // 单响应体最多统计 1MB

    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");

    private static final ConcurrentHashMap<String, RunHandle> RUNS = new ConcurrentHashMap<>();

    private static final class RunHandle {
        final SseEmitter emitter;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        RunHandle(SseEmitter e) { this.emitter = e; }
    }

    /** 请求体 */
    public static class StressTestReq {
        public String url;
        public String method = "GET";
        public Map<String, String> headers;
        public Map<String, String> query;
        public String body;
        public Integer concurrency = 10;
        public Integer totalRequests = 100;
        public Integer durationSec = 0;   // 0 = 按 totalRequests 跑完即止
        public Integer rampUpSec = 0;     // 前 rampUpSec 秒内线性放开并发数
    }

    @PostMapping
    public SseEmitter run(@RequestBody(required = false) StressTestReq req, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return errEmitter("未登录");
        if (!PermService.isSuperAdmin(me)) return errEmitter("需要超级管理员权限");

        String bad = validate(req);
        if (bad != null) return errEmitter(bad);

        final String method = normalizeMethod(req.method);
        final String fullUrl = buildUrl(req.url, req.query);
        final Map<String, String> headers = req.headers == null ? Map.of() : req.headers;
        final String body = req.body == null ? "" : req.body;
        final int concurrency = clamp(req.concurrency, 1, MAX_CONCURRENCY, 10);
        final int total = clamp(req.totalRequests, 1, MAX_TOTAL, 100);
        final int durationSec = clamp(req.durationSec, 0, MAX_DURATION_SEC, 0);
        final int rampUpSec = clamp(req.rampUpSec, 0, MAX_RAMP_SEC, 0);
        final boolean byDuration = durationSec > 0;
        final long hardCap = byDuration ? ((long) durationSec * 100_000L) : total;
        final int sampleCap = (int) Math.min(hardCap, MAX_LATENCY_SAMPLES);

        String runId = UUID.randomUUID().toString();
        long timeout = (byDuration ? (durationSec + 30L) : 600L) * 1000L;
        SseEmitter emitter = new SseEmitter(timeout);
        RunHandle handle = new RunHandle(emitter);
        RUNS.put(runId, handle);
        // 超时 / 完成都置取消标记，避免客户端断开后服务端还在空跑
        emitter.onTimeout(() -> { handle.cancel.set(true); RUNS.remove(runId); });
        emitter.onCompletion(() -> RUNS.remove(runId));

        // 异步执行，HTTP 立即返回 SseEmitter，由客户端持续收 SSE
        CompletableFuture.runAsync(() -> execute(handle, runId, method, fullUrl, headers, body,
                concurrency, total, durationSec, rampUpSec, byDuration, hardCap, sampleCap));
        return emitter;
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String runId, HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));
        RunHandle h = RUNS.get(runId);
        if (h == null) return ResponseEntity.ok(Map.of("ok", true, "stopped", false, "message", "任务不存在或已结束"));
        h.cancel.set(true);
        return ResponseEntity.ok(Map.of("ok", true, "stopped", true));
    }

    // ---------------- 执行 ----------------

    private void execute(RunHandle handle, String runId, String method, String fullUrl,
                         Map<String, String> headers, String body, int concurrency, int total,
                         int durationSec, int rampUpSec, boolean byDuration, long hardCap, int sampleCap) {
        SseEmitter emitter = handle.emitter;
        try {
            LinkedHashMap<String, Object> start = new LinkedHashMap<>();
            start.put("type", "start");
            start.put("runId", runId);
            start.put("byDuration", byDuration);
            start.put("target", fullUrl);
            start.put("method", method);
            emitter.send(SseEmitter.event().name("start").data(start));

            AtomicInteger completed = new AtomicInteger(0);
            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            AtomicLong totalBytes = new AtomicLong(0);
            long[] latencies = new long[sampleCap];
            AtomicInteger latIdx = new AtomicInteger(0);
            Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
            List<Map<String, Object>> errors = Collections.synchronizedList(new ArrayList<>());
            long startWall = System.currentTimeMillis();

            AtomicBoolean running = new AtomicBoolean(true);
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            List<Future<?>> workers = new ArrayList<>();

            // 进度 ticker：每 250ms 推一次
            ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
            ticker.scheduleAtFixedRate(() -> {
                try {
                    long elapsed = System.currentTimeMillis() - startWall;
                    LinkedHashMap<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "progress");
                    p.put("completed", completed.get());
                    p.put("success", success.get());
                    p.put("failed", failed.get());
                    p.put("qps", elapsed > 0 ? round1(completed.get() * 1000.0 / elapsed) : 0);
                    p.put("elapsedMs", elapsed);
                    emitter.send(SseEmitter.event().name("progress").data(p));
                } catch (IOException e) { running.set(false); }
            }, 250, 250, TimeUnit.MILLISECONDS);

            // 工作线程：原子预约 slot，保证「总请求数」模式下精确不超发
            int cap = byDuration ? Integer.MAX_VALUE : total;
            for (int i = 0; i < concurrency; i++) {
                final int workerIndex = i;
                workers.add(pool.submit(() -> {
                    while (running.get() && !handle.cancel.get()) {
                        if (byDuration && System.currentTimeMillis() - startWall >= durationSec * 1000L) break;
                        int slot = completed.getAndIncrement();
                        if (slot >= cap) { completed.decrementAndGet(); break; }
                        // ramp-up：前 rampUpSec 秒内逐步放开并发数（按 worker 序号门控），未放开则回退预约并重试
                        if (rampUpSec > 0) {
                            long elapsed = System.currentTimeMillis() - startWall;
                            int allowed = (int) Math.ceil(concurrency
                                    * Math.min(elapsed, (long) rampUpSec * 1000) / ((double) rampUpSec * 1000));
                            if (workerIndex >= allowed) {
                                completed.decrementAndGet();
                                try { Thread.sleep(50); } catch (InterruptedException ie) {
                                    completed.decrementAndGet();
                                    Thread.currentThread().interrupt(); break;
                                }
                                continue;
                            }
                        }
                        fireOne(method, fullUrl, headers, body, success, failed, totalBytes,
                                latencies, latIdx, statusCounts, errors, startWall);
                    }
                }));
            }

            for (Future<?> f : workers) { try { f.get(); } catch (Exception ignored) {} }
            pool.shutdownNow();
            ticker.shutdownNow();

            long elapsed = System.currentTimeMillis() - startWall;
            int n = Math.min(latIdx.get(), sampleCap);
            LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", "done");
            summary.put("total", completed.get());
            summary.put("success", success.get());
            summary.put("failed", failed.get());
            summary.put("durationMs", elapsed);
            summary.put("qps", elapsed > 0 ? round1(completed.get() * 1000.0 / elapsed) : 0);
            summary.put("bytesReceived", totalBytes.get());
            summary.put("latency", summarize(latencies, n));
            summary.put("statusCodes", statusMap(statusCounts));
            summary.put("errors", errors);
            emitter.send(SseEmitter.event().name("done").data(summary));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("type", "error", "message", "压测执行异常：" + e.getMessage())));
            } catch (IOException ignored) {}
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        } finally {
            RUNS.remove(runId);
        }
    }

    private void fireOne(String method, String fullUrl, Map<String, String> headers, String body,
                        AtomicInteger success, AtomicInteger failed,
                        AtomicLong totalBytes, long[] latencies, AtomicInteger latIdx,
                        Map<Integer, AtomicInteger> statusCounts, List<Map<String, Object>> errors, long startWall) {
        long t0 = System.currentTimeMillis();
        int status = -1;
        String errMsg = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    String k = h.getKey(), v = h.getValue();
                    if (k == null || v == null) continue;
                    // Host / Content-Length 由 JDK 管理，跳过避免协议异常
                    if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Content-Length")) continue;
                    conn.setRequestProperty(k, v);
                }
            }
            boolean hasBody = body != null && !body.isEmpty()
                    && !"GET".equals(method) && !"HEAD".equals(method);
            if (hasBody) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            long bytes = drain(in);
            if (bytes >= 0) totalBytes.addAndGet(bytes);
            conn.disconnect();
            statusCounts.computeIfAbsent(status, k -> new AtomicInteger(0)).incrementAndGet();
        } catch (Exception e) {
            errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            failed.incrementAndGet();
            if (errors.size() < MAX_ERROR_SAMPLES) {
                errors.add(Map.of("ts", System.currentTimeMillis() - startWall, "message", String.valueOf(errMsg)));
            }
        }
        if (errMsg == null) {
            if (status >= 200 && status < 400) success.incrementAndGet();
            else failed.incrementAndGet();
        }
        long lat = System.currentTimeMillis() - t0;
        int idx = latIdx.getAndIncrement();
        if (idx < latencies.length) latencies[idx] = lat;
    }

    /** 读完响应体以释放连接；最多精确统计 READ_BODY_CAP 字节 */
    private long drain(InputStream in) {
        if (in == null) return 0;
        long total = 0;
        try {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (total < READ_BODY_CAP) total += r;
            }
            in.close();
        } catch (IOException e) { /* 已尽量读取 */ }
        return total;
    }

    // ---------------- 工具 ----------------

    private String validate(StressTestReq req) {
        if (req == null || req.url == null || req.url.trim().isEmpty()) return "目标 URL 必填";
        String u = req.url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "URL 必须以 http:// 或 https:// 开头";
        if (normalizeMethod(req.method) == null) return "不支持的请求方法：" + req.method;
        return null;
    }

    private String normalizeMethod(String m) {
        if (m == null) return "GET";
        String up = m.trim().toUpperCase();
        return ALLOWED_METHODS.contains(up) ? up : null;
    }

    private String buildUrl(String url, Map<String, String> query) {
        if (query == null || query.isEmpty()) return url.trim();
        StringBuilder sb = new StringBuilder(url.trim());
        String sep = url.contains("?") ? "&" : "?";
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (e.getKey() == null) continue;
            sb.append(sep).append(encode(e.getKey())).append("=").append(encode(e.getValue() == null ? "" : e.getValue()));
            sep = "&";
        }
        return sb.toString();
    }

    private String encode(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    private Map<String, Object> summarize(long[] arr, int n) {
        if (n <= 0) return Map.of("min", 0, "max", 0, "avg", 0, "p50", 0, "p95", 0, "p99", 0);
        long[] copy = Arrays.copyOf(arr, n);
        Arrays.sort(copy);
        long sum = 0; for (long v : copy) sum += v;
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("min", copy[0]);
        m.put("max", copy[n - 1]);
        m.put("avg", Math.round(sum / (double) n));
        m.put("p50", percentile(copy, n, 0.50));
        m.put("p95", percentile(copy, n, 0.95));
        m.put("p99", percentile(copy, n, 0.99));
        return m;
    }

    private long percentile(long[] sorted, int n, double p) {
        if (n <= 0) return 0;
        int idx = (int) Math.ceil(p * n) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return sorted[idx];
    }

    private Map<String, Object> statusMap(Map<Integer, AtomicInteger> counts) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<Integer, AtomicInteger> e : counts.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue().get());
        return m;
    }

    private int clamp(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.min(Math.max(v, min), max);
    }

    private double round1(double d) { return Math.round(d * 10.0) / 10.0; }

    private SseEmitter errEmitter(String message) {
        SseEmitter e = new SseEmitter(2000L);
        try {
            e.send(SseEmitter.event().name("error").data(Map.of("type", "error", "message", message)));
        } catch (IOException ignored) {}
        e.complete();
        return e;
    }
}
