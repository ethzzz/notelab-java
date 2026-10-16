package com.notelab.service;

import com.notelab.common.AppConfig;
import com.notelab.dao.BlogDeployDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    /**
     * 站点根。
     * ⚠️ 域名别手打：这里是 `haolo.cloud`（不是 haola）。2026-10-16 就因为写成 `haola.cloud`，
     * 探活一直返回 000 —— 而 curl 报的是「Could not resolve host: haola.cloud」，
     * 与 `--resolve haolo.cloud:...` 的目标拼写不一致，所以映射根本没生效、回落到 DNS 才失败。
     * 排查这类问题时**先逐字读报错里的主机名**，别急着怀疑环境。
     */
    private static final String SITE = "https://haolo.cloud";

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
        Probe pr = new Probe(null, "");
        if (exit == 0) pr = probe(path);

        Integer code = pr.code();
        boolean ok = exit == 0 && (code == null || code == 200);
        StringBuilder note = new StringBuilder();
        if (exit != 0) note.append("\n[失败] 部署脚本退出码 ").append(exit).append("（见上方输出尾部）");
        else if (code != null && code != 200) note.append("\n[失败] 脚本成功，但探活 ").append(path).append(" 得到 ").append(code);
        else note.append("\n[完成] 探活 ").append(path).append(" → ").append(code == null ? "未取到" : code);
        if (!pr.note().isEmpty()) note.append("\n[探活细节] ").append(pr.note());

        long ms = System.currentTimeMillis() - t0;
        String status = ok ? BlogDeployDao.SUCCESS : BlogDeployDao.FAILED;
        BlogDeployDao.finish(jobId, status, exit, code, ms, tail(out + note));
        log.info("博客部署 job={} 结束：{}（exit={} probe={} {}ms）{}", jobId, status, exit, code, ms, pr.note());
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
     * <p>⚠️ 走 <b>curl --resolve</b>，不用 JVM 自己的 HttpClient —— 2026-10-16 实测踩到：
     * {@code HttpClient} 连 `https://haolo.cloud` 抛 **ConnectException**（服务器把域名解析出 IPv6，
     * 而那条路由实际不通；curl 有 Happy Eyeballs 会回落到 IPv4，Java 不会）。
     * 而 `--resolve haolo.cloud:443:127.0.0.1` 直接绕开 DNS、按域名做 SNI/证书校验，
     * 与 ops 的部署脚本、`nginx-blog-ssr.sh` **同一个探活口径** —— 探活方式也只该有一份。
     *
     * <p>探不到（超时/网络抖动）返回 {@code null} 并记「未取到」：那是「不知道」，
     * 不能写成「失败」，否则一次网络抖动会把一次成功的部署标成红的。
     */
    private static Probe probe(String path) {
        // -sS：静默进度但**保留错误**（裸 -s 会把失败原因一起吞掉，只剩一个 000，等于没说为什么）
        List<String> cmd = List.of("curl", "-sS", "-o", "/dev/null", "-w", "HTTP_CODE=%{http_code}\\n",
                "--max-time", "20", "--resolve", "haolo.cloud:443:127.0.0.1", SITE + path);
        try {
            Result r = exec(cmd, 30);
            String out = r.out();
            int i = out.lastIndexOf("HTTP_CODE=");
            if (i < 0) return new Probe(null, "curl 没有输出状态码：" + oneLine(out));
            String digits = out.substring(i + "HTTP_CODE=".length()).trim();
            if (!digits.matches("\\d{3}")) return new Probe(null, "curl 状态码无法解析：" + oneLine(out));
            int code = Integer.parseInt(digits);
            return new Probe(code, code == 200 ? "" : "curl 输出：" + oneLine(out.substring(0, i)));
        } catch (Exception e) {
            return new Probe(null, "探活异常：" + e);
        }
    }

    /** 把多行输出压成一行（落库的日志尾巴是给人看的，别塞进一堆换行） */
    private static String oneLine(String s) {
        String t = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** 探活结果：状态码 + 失败时的细节（细节要留痕，否则「000」无法排查） */
    private record Probe(Integer code, String note) {}

    private static String tail(String s) {
        if (s == null) return "";
        return s.length() <= MAX_LOG ? s : s.substring(s.length() - MAX_LOG);
    }

    private record Result(int code, String out) {}
}
