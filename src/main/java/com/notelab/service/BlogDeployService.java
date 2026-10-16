package com.notelab.service;

import com.notelab.common.AppConfig;
import com.notelab.dao.BlogDeployDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 博客**部署**（构建 + 发布到线上）—— admin 发布之后的那一步。
 *
 * <p>为什么需要它：博客是静态站，改完 md 必须重新构建才会出现在页面上。原来只有两条路：
 * ① 等 GitHub Actions（实测 scp 上传要 11–25 分钟，还失败过）；② 人上服务器手跑 ops 脚本。
 * 现在第三条、也是 admin 侧的主路：**后台触发服务器本地构建**（P0 实测 80 秒、峰值 1.73GB）。
 *
 * <h3>五个必须守住的点</h3>
 * <ol>
 *   <li><b>并发必须挡住</b>：两条构建同时 `find /var/www/blog -mindepth 1 -delete` + 覆盖写会互相
 *       覆盖出半成品站点。所以单线程池 + 有界队列，且 {@link #trigger} 先查库里的活跃任务；
 *       满了就**拒绝**（返回原因）而不是排队硬碰。</li>
 *   <li><b>限权</b>：跑脚本时套 `nice -n 19` —— 这台机器只有 2 核，构建会把 CPU 打满
 *       （P0 实测 load 峰值 2.46），不能让后台构建把 nginx / java / 前端拖慢。</li>
 *   <li><b>输出要收全</b>：读流必须在独立线程（管道缓冲满了进程会卡在 write 上，双方互等）。
 *       失败时那几百行输出就是唯一的线索，尾部要落库。</li>
 *   <li><b>超时</b>：脚本卡住（网络、npm、pagefind）不能把 job 永远吊在 running ——
 *       超时强制结束并按失败收尾。默认 600 秒，`BLOG_DEPLOY_TIMEOUT_SEC` 可调。</li>
 *   <li><b>探活按语义选路径</b>：发布/更新（已发布）探文章 URL，必须 200；下线/删除时
 *       **不能**探文章 URL —— 那一篇本来就该 404。所以调用方只在「期待它可访问」时给 verifyPath，
 *       否则退化成探 `/blog/`（站点活着）。</li>
 * </ol>
 *
 * <p>⚠️ 进程执行这段代码与 {@link BlogRepoService#run} 是同一模式的第二份实现
 * （独立读流线程 + 超时 + 强制结束）。按仓里的 Rule of Three 先不抽象 ——
 * 第三次出现时再抽公共 runner，届时一并把那边迁过去（它是 git 写入的关键路径，单独改动风险更大）。
 */
public final class BlogDeployService {

    private static final Logger log = LoggerFactory.getLogger(BlogDeployService.class);

    /** 落库的输出尾巴长度（TEXT 够放，但没必要存几 MB） */
    private static final int MAX_LOG = 8000;

    private static final String SITE = "https://haola.cloud";

    /**
     * 探活用的共享 client。
     * ⚠️ 不能写成 `try (HttpClient c = ...)`：{@code HttpClient} 到 **Java 21** 才 implements AutoCloseable，
     * 本仓是 Java 17（pom 里 java.version=17），那样写直接编译不过。
     * 共享一个实例也是官方建议（连接复用），与 QwenClient / LlmHealth 的写法一致。
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 单线程 + 有界队列（容量 2）。拒绝策略用 Abort，由 {@link #trigger} 翻译成「队列已满」。
     * 线程是 daemon：不要把 JVM 的退出挂在一场构建上。
     */
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
            r -> {
                Thread t = new Thread(r, "blog-deploy");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private BlogDeployService() {}

    /** 部署脚本路径（默认博客仓自带的那个；换位置只改配置，不改代码） */
    public static String script() {
        return AppConfig.get("BLOG_DEPLOY_SCRIPT", "/root/blog-src/ops/deploy-ssr.sh");
    }

    /** 「发布后自动部署」开关（`BLOG_AUTO_DEPLOY=0` 关掉；关掉后只能手动点部署） */
    public static boolean autoEnabled() {
        return !"0".equals(AppConfig.get("BLOG_AUTO_DEPLOY", "1"));
    }

    private static long timeoutSec() {
        String raw = AppConfig.get("BLOG_DEPLOY_TIMEOUT_SEC", "600");
        try {
            long v = Long.parseLong(raw.trim());
            return v >= 60 && v <= 3600 ? v : 600;
        } catch (NumberFormatException e) {
            return 600;
        }
    }

    // ==================================================================================
    // 触发
    // ==================================================================================

    /**
     * 建一条部署任务并入队。
     *
     * @param verifyPath 期望可访问的路径（如 {@code /blog/posts/<slug>/}）；只想确认站点活着就传空串
     * @return {@code {ok:true, jobId, status, commit, script}}；被拒时 {@code {ok:false, reason}}
     */
    public static Map<String, Object> trigger(Long userId, String userName, String kind, String verifyPath) {
        int active = BlogDeployDao.countActive();
        if (active > 0 || POOL.getActiveCount() > 0 || !POOL.getQueue().isEmpty()) {
            return Map.of("ok", false, "reason", "已经有一个部署在跑（或排队中），等它结束再试",
                    "active", active);
        }
        String head = BlogRepoService.headCommit();
        long id = BlogDeployDao.create(userId, userName, kind, head, verifyPath);
        try {
            POOL.execute(() -> run(id, verifyPath));
        } catch (RejectedExecutionException e) {
            BlogDeployDao.finish(id, BlogDeployDao.FAILED, null, null, 0L, "入队被拒：部署队列已满");
            return Map.of("ok", false, "reason", "部署队列已满，稍后再试");
        }
        log.info("博客部署已入队：job={} kind={} commit={} verify={} by={}", id, kind, head, verifyPath, userName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("jobId", id);
        out.put("status", BlogDeployDao.QUEUED);
        out.put("commit", head);
        out.put("verifyPath", verifyPath);
        out.put("script", script());
        return out;
    }

    // ==================================================================================
    // 执行
    // ==================================================================================

    private static void run(long jobId, String verifyPath) {
        long t0 = System.currentTimeMillis();
        BlogDeployDao.markRunning(jobId);
        int exit = -1;
        String out;
        try {
            Result r = exec(List.of("nice", "-n", "19", "bash", script()), timeoutSec());
            exit = r.code();
            out = r.out();
        } catch (Exception e) {
            out = "执行失败（没能跑起来）：" + e;
            log.warn("博客部署 job={} 执行失败", jobId, e);
        }

        String path = verifyPath == null || verifyPath.isBlank() ? "/blog/" : verifyPath;
        Integer probe = null;
        if (exit == 0) probe = probe(path);

        boolean ok = exit == 0 && (probe == null || probe == 200);
        StringBuilder note = new StringBuilder();
        if (exit != 0) note.append("\n[失败] 部署脚本退出码 ").append(exit).append("（见上方输出尾部）");
        else if (probe != null && probe != 200) note.append("\n[失败] 脚本成功，但探活 ").append(path).append(" 得到 ").append(probe);
        else note.append("\n[完成] 探活 ").append(path).append(" → ").append(probe == null ? "未取到" : probe);

        long ms = System.currentTimeMillis() - t0;
        String status = ok ? BlogDeployDao.SUCCESS : BlogDeployDao.FAILED;
        BlogDeployDao.finish(jobId, status, exit, probe, ms, tail(out + note));
        log.info("博客部署 job={} 结束：{}（exit={} probe={} {}ms）", jobId, status, exit, probe, ms);
    }

    /**
     * 跑一条命令并收全输出。
     *
     * <p>⚠️ 超时**返回**（code = -1 + 已收到的输出）而不是抛异常：部署失败时那段输出就是唯一线索，
     * 超时时把它丢掉等于把「为什么卡住」一起丢了。
     */
    private static Result exec(List<String> cmd, long timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } catch (IOException ignored) {
                // 进程被强杀时读流会抛，属预期
            }
        });
        reader.setDaemon(true);
        reader.start();

        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(3000);
            return new Result(-1, sb + "\n[超时] 超过 " + timeoutSec + " 秒被强制结束（" + String.join(" ", cmd) + "）");
        }
        reader.join(3000);
        return new Result(p.exitValue(), sb.toString());
    }

    /**
     * 探活。
     *
     * <p>服务器**能直连自己的域名**（实测 200），所以不必像 ops 脚本那样 `curl --resolve`；
     * 探不到（网络/证书/超时）返回 {@code null} 并记「未取到」—— 那是「不知道」，
     * 不能写成「失败」，否则一次网络抖动会把一次成功的部署标成红的。
     */
    private static Integer probe(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SITE + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            log.warn("博客部署探活失败 {}：{}", path, e.toString());
            return null;
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        return s.length() <= MAX_LOG ? s : s.substring(s.length() - MAX_LOG);
    }

    private record Result(int code, String out) {}
}
