package com.notelab.service;

import com.notelab.common.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 博客仓（astro-blog-fuwari / GitHub ethzzz/blog）的读写收敛点。
 *
 * <p>为什么要有这个类：clone / pull / commit / push 这套东西一旦各写一份，就会出现
 * 「一边能 push、另一边因为没 pull 而 push 被拒」这类只在生产暴露的差异。
 *
 * <p>⚠️ 当前**唯一**的写入方是 {@link BlogArticleService}（文章起草：新建 / 更新 / 上下线 / 删除，
 * 见 {@code BlogContentController}）。历史沿革必须记住，否则会照着老注释做错判断：
 * 练习实践（{@code PracticeController}）**2026-10-15 起不再写仓库** —— 它的解答改成了
 * 「数据库为真相 + 博客解答页运行时读接口」，本仓对它只剩下**只读**用途（列路线文章清单做 relatedPost 推导）。
 *
 * <p>仓库写入流程固定为：{@link #ensure()}（clone 或 ff-only pull）→ {@link #writeText}/{@link #writeBinary}
 * → {@link #commitPush}。push 到 main 会触发博客的 GitHub Actions 构建部署。
 * ⚠️ 那条 CI 链路的 scp 上传实测要 11–25 分钟、而且失败过（2026-10-16 读 Actions API 逐步骤核实），
 * 所以 admin 侧发布**不依赖它**：发布后由后台触发服务器本地构建（实测 80 秒），见任务简报 P2。
 *
 * <h3>三个必须守住的点</h3>
 * <ol>
 *   <li><b>SSH 必须非交互</b>：{@code git pull} 卡在主机指纹确认上会让整个 HTTP 请求挂到超时。
 *       统一注入 {@code GIT_SSH_COMMAND}（BatchMode + accept-new）与 {@code GIT_TERMINAL_PROMPT=0}。</li>
 *   <li><b>并发要串行化</b>：git 索引锁（{@code index.lock}）撞上就是「另一个 git 进程在运行」。
 *       所有会碰工作区/索引的动作走同一把 {@link #LOCK}。
 *       ⚠️ 但它只保护**单条 git 命令** —— 调用方若要「读文件 → 改 → 写回」，
 *       必须自己在外面再串行化一次（{@link BlogArticleService} 为此有自己的写锁）。</li>
 *   <li><b>路径必须防穿越</b>：调用方传的是相对路径，直接 resolve 后要校验没跑出仓根
 *       （否则一个 {@code ../../etc/passwd} 就能写到仓外）。</li>
 * </ol>
 *
 * <p>⚠️ 服务器上必须有**一份可写的 clone**（{@code BLOG_REPO_LOCAL}，默认 {@code /root/blog-src}）。
 * 只有构建产物 {@code /var/www/blog} 是不够的 —— 那是 dist，改了会被下次构建覆盖。
 */
public final class BlogRepoService {

    private static final Logger log = LoggerFactory.getLogger(BlogRepoService.class);

    /** 所有 git 动作共用（同进程内串行）；单实例部署下足够，多实例需换文件锁 */
    private static final Object LOCK = new Object();

    /** 单条 git 命令的上限。clone 大仓 + 网络抖动留足余量 */
    private static final long CMD_TIMEOUT_SEC = 180;

    private BlogRepoService() {}

    public static String repoUrl() {
        return AppConfig.get("BLOG_REPO_URL", "git@github.com:ethzzz/blog.git");
    }

    public static Path local() {
        return Paths.get(AppConfig.get("BLOG_REPO_LOCAL", "/root/blog-src"));
    }

    /**
     * 把仓内相对路径解析成绝对路径，并**校验没跑出仓根**。
     *
     * <p>normalize 之后再比 startsWith：只查字符串前缀是不够的（{@code a/../..} 能绕过）。
     */
    public static Path resolve(String relative) {
        Path base = local().toAbsolutePath().normalize();
        Path p = base.resolve(relative).normalize();
        if (!p.startsWith(base)) {
            throw new IllegalArgumentException("路径越出博客仓：" + relative);
        }
        return p;
    }

    /** 仓根下该文件是否存在 */
    public static boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    /** 列出目录下的文件名（不带路径）。目录不存在返回空表，不抛错。 */
    public static List<String> listNames(String relativeDir) {
        Path d = resolve(relativeDir);
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("列目录失败 {}：{}", d, e.toString());
            return List.of();
        }
    }

    /** clone（首次）或 ff-only pull（其后）。pull 失败**不抛**：离线/分叉时仍允许本地提交，push 阶段会再兜一次 */
    public static void ensure() throws Exception {
        synchronized (LOCK) {
            Path local = local();
            if (!Files.isDirectory(local.resolve(".git"))) {
                Path parent = local.getParent();
                if (parent != null) Files.createDirectories(parent);
                exec(List.of("git", "clone", "--quiet", repoUrl(), local.toString()), true);
                log.info("博客仓已 clone 到 {}", local);
            } else {
                try {
                    exec(git("pull", "--ff-only", "--quiet"), false);
                } catch (Exception e) {
                    log.warn("博客仓 pull 失败（继续，push 时会重试）：{}", e.getMessage());
                }
            }
        }
    }

    /** 写文本文件（UTF-8，自动建父目录） */
    public static Path writeText(String relative, String content) throws IOException {
        Path p = resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8);
        return p;
    }

    /** 写二进制文件（插图导出用）；自动建父目录 */
    public static Path writeBinary(String relative, byte[] data) throws IOException {
        Path p = resolve(relative);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
        return p;
    }

    /** 删除仓内文件（存在才删）。用于「改了 slug 重新发布时清掉旧的解答文件」 */
    public static void deleteIfExists(String relative) throws IOException {
        Files.deleteIfExists(resolve(relative));
    }

    /**
     * 提交并推送。
     *
     * @param paths 仓内相对路径列表（git add 的目标）。⚠️ 只 add 这些 ——
     *              博客仓里可能有别人未提交的进行中改动，`git add -A` 会把它们一起带上去
     * @return 新的短 commit hash；`"no-change"` 表示内容与已提交版本一致（重复发布同一份内容时会出现）
     */
    public static String commitPush(String message, List<String> paths) throws Exception {
        synchronized (LOCK) {
            for (String rel : paths) {
                // -- 分隔符：文件名以 - 开头时不会被当成参数
                exec(git("add", "--", rel), true);
            }
            if (exitCode(git("commit", "-m", message)) != 0) return "no-change";
            try {
                exec(git("push", "--quiet"), true);
            } catch (Exception e) {
                // 远端有新提交导致非快进 → rebase 后再推一次。
                // 这一步是必要的：博客的 GitHub Actions 会在构建时回写文件，远端随时可能领先。
                log.warn("博客仓 push 被拒，尝试 pull --rebase 后重试：{}", e.getMessage());
                exec(git("pull", "--rebase", "--quiet"), true);
                exec(git("push", "--quiet"), true);
            }
            return exec(git("rev-parse", "--short", "HEAD"), true).trim();
        }
    }

    // ===================== 内部 =====================

    private static List<String> git(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(local().toString());
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static int exitCode(List<String> cmd) throws Exception {
        return run(cmd, false).code;
    }

    private static String exec(List<String> cmd, boolean fail) throws Exception {
        Result r = run(cmd, fail);
        return r.out;
    }

    private record Result(int code, String out) {}

    /**
     * 跑一条命令并收集输出。
     *
     * <p>⚠️ 读流必须在**独立线程**里：进程输出超过管道缓冲（约 64KB）就会阻塞在 write 上，
     * 而主线程还在 waitFor —— 双方互等，表现为「命令永远不返回」。
     */
    private static Result run(List<String> cmd, boolean fail) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // 非交互：否则遇上主机指纹确认/凭据提示会一直挂着
        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();

        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } catch (IOException ignored) {
                // 进程被 kill 时读流会抛，属预期
            }
        });
        reader.setDaemon(true);
        reader.start();

        if (!p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("命令超时(" + CMD_TIMEOUT_SEC + "s)：" + String.join(" ", cmd));
        }
        reader.join(3000);
        String out = sb.toString();
        int code = p.exitValue();
        if (code != 0 && fail) {
            throw new RuntimeException("命令失败(" + code + ")：" + String.join(" ", cmd) + "\n" + out);
        }
        return new Result(code, out);
    }
}
