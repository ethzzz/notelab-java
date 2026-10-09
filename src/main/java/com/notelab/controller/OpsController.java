package com.notelab.controller;

import com.notelab.common.AppConfig;
import com.notelab.common.JsonUtil;
import com.notelab.dao.LoginAuditDao;
import com.notelab.service.PermService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 运维看板 / 发布自检：前缀 {@code /api/admin}（受限前缀，仅 super_admin）。
 *
 * <p>要解决的问题：现在每次部署完都要 ssh 手敲一串命令验证（pm2 list、各端口 curl、df/free、
 * git rev-parse），而且"保存了但没发布""构建了但 pm2 没重启""服务器副本落后远端"这类问题只能靠人记。
 * 本控制器把这些**只读**信息聚成两个接口，页面上一次看完。
 *
 * <p>⚠️ 安全约束（改动时务必保持）：
 * <ul>
 *   <li>只做**只读**探测：git 查询、pm2 jlist、df/free、本机 HTTP GET。不提供任何"重启/拉取/构建"入口 ——
 *       那类写操作仍走 ssh，避免把部署能力暴露到 Web。</li>
 *   <li>执行外部命令一律用**参数数组**（ProcessBuilder(List)），绝不拼 shell 字符串；
 *       命令与路径全部是本类里的常量，不接受任何用户输入作为命令或路径。</li>
 *   <li>接口本身受 PermGuard 的受限前缀保护，控制器内再校验一次 super_admin（双保险）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/ops")
public class OpsController {

    /** 服务清单：pm2 进程名 / 展示名 / 监听端口 / 服务器工作副本目录 / 探活路径 */
    private record Svc(String pm2, String name, int port, String dir, String probe) {}

    // ⚠️ 三个 Notelab 仓的目录必须走 AppConfig.repoRoot()（= /root/Notelab，大写 N）：
    //    早年这里写的是小写 /root/notelab-*，而该目录在服务器上不存在 —— git() 取不到 HEAD，
    //    看板上每条都报「目录不存在或不是 git 仓库」，看起来像"仓库没克隆"其实是路径拼错。
    //    ai-lab 是平铺在 /root 下的独立仓，不在 repoRoot 里，所以单独写死。
    private static final String R = AppConfig.repoRoot();

    private static final List<Svc> SVCS = List.of(
            new Svc("notelab-java", "后端 API", 8001, R + "/notelab-java", "/api/menu"),
            new Svc("notelab-b", "B 端后台", 3020, R + "/notelab-b", "/admin/login"),
            new Svc("notelab-c", "C 端站点", 3010, R + "/notelab-c", "/"),
            new Svc("ai-lab", "AI 实验室", 8002, "/root/ai-lab", "/")
    );

    /** 单条命令的输出上限（防 pm2 jlist / git log 异常时吃内存） */
    private static final int MAX_OUT = 200_000;
    private static final int CMD_TIMEOUT_SEC = 8;
    private static final int HTTP_TIMEOUT_MS = 4000;

    // ------------------------------------------------------------------ 接口

    /** 状态看板：进程 + 端口探活 + 各仓 git + 主机资源 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());
        out.put("host", host());
        out.put("pm2", pm2());
        List<Map<String, Object>> services = new ArrayList<>();
        for (Svc s : SVCS) services.add(service(s));
        out.put("services", services);
        return ResponseEntity.ok(out);
    }

    /**
     * 登录审计查询（**只读**）。
     *
     * <p>为什么要能查：修 SECRET_KEY 漏洞时发现最大的问题是「**判断不了那个洞有没有被利用过**」——
     * 仓库里完全没有登录留痕（{@code analytics_events} 只记成功、且 IP 是哈希、90 天就删）。
     * 这张表 + 这个接口补的就是「事后可回溯」。
     *
     * <p>⚠️ 只读，且与同类接口一样受 {@code /api/admin} 受限前缀（仅 super_admin）保护，
     * 控制器内再校验一次（双保险）。查询条件全部走 DAO 的 {@code ?} 占位符。
     *
     * @param day      日期 {@code yyyy-MM-dd}，缺省或格式非法 = 今天（与 days 同时缺省时生效）
     * @param days     时间范围（**优先于 day**）：1=今天，3/7/30/90/365=过去 N 天（含今天），
     *                 0=全部（保留期内，上限即 365 天清理策略）；负数或非法 = 忽略、回落 day 口径
     * @param ip       精确匹配
     * @param username 精确匹配（用户输入的原样）
     * @param result   success / bad_password / no_such_user / disabled / rate_limited / bad_request
     */
    @GetMapping("/login-audit")
    public ResponseEntity<Map<String, Object>> loginAudit(
            HttpServletRequest request,
            @RequestParam(required = false) String day,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));

        // 时间口径二选一：days（范围）优先于 day（单日）。days=null 时保持旧语义：缺省 = 今天。
        String d;
        String fromDay = null;
        if (days != null) {
            if (days == 0) {
                d = null;                       // 全部：不加时间条件（保留期 365 天兜底）
            } else if (days >= 1) {
                int n = Math.min(days, 365);    // 与清理策略同上限，防止 fromDay 早于数据存留
                d = null;
                fromDay = LocalDate.now().minusDays(n - 1L).toString();
            } else {
                d = normalizeDay(day);          // 负数/非法：回落单日口径
            }
        } else {
            d = normalizeDay(day);
        }
        String fIp = blankToNull(ip), fUser = blankToNull(username), fResult = blankToNull(result);

        int p = Math.max(1, page);
        int sz = Math.max(1, Math.min(LoginAuditDao.MAX_PAGE, size));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", d);
        out.put("fromDay", fromDay);
        out.put("page", p);
        out.put("size", sz);
        out.put("total", LoginAuditDao.count(d, fromDay, fIp, fUser, fResult));
        out.put("items", LoginAuditDao.search(d, fromDay, fIp, fUser, fResult, sz, (p - 1) * sz));
        // 各结果计数：[{result, n}]，页面顶部一眼看异常（bad_password/no_such_user 突然变多 = 有人在撞库）
        out.put("byResult", LoginAuditDao.countByResult(d, fromDay));
        return ResponseEntity.ok(out);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * day 参数归一化。
     *
     * <p>⚠️ 必须用 {@link LocalDate#parse} 做**语义**校验，光校验形状（正则 {@code \d{4}-\d{2}-\d{2}}）
     * 是不够的 —— {@code "2026-13-45"} 形状完全合法，透给 MySQL 会直接
     * {@code Incorrect DATE value} → 走全局异常处理器 → **500**。
     * （验收时就踩到了这一条。）
     *
     * <p>任何非法值一律**回落今天**，而不是报错或丢个空结果 —— 空结果看着像「今天没数据」，
     * 比报错更容易误导人。
     */
    private static String normalizeDay(String day) {
        if (day == null || day.isBlank()) return LocalDate.now().toString();
        try {
            return LocalDate.parse(day.trim()).toString();
        } catch (Exception e) {
            return LocalDate.now().toString();
        }
    }

    /**
     * 发布自检：把部署后人工做的那几项验证固化成一份可判定的清单。
     * 每项 level = ok / warn / fail —— fail 表示"确实有问题"，warn 表示"需要人确认"。
     */
    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(HttpServletRequest request) {
        Map<String, Object> me = AuthUtil.user(request);
        if (me == null) return AuthUtil.unauth();
        if (!PermService.isSuperAdmin(me)) return ResponseEntity.status(403).body(Map.of("error", "需要超级管理员权限"));

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> items = new ArrayList<>();
        int ok = 0, warn = 0, fail = 0;

        // ① 各服务端口探活：拿得到任何 HTTP 响应就说明进程在监听（401/404 都算活着）
        for (Svc s : SVCS) {
            Probe p = probe(s.port(), s.probe());
            boolean up = p.code > 0;
            items.add(item(up ? "ok" : "fail",
                    s.name() + " 端口 " + s.port() + " 探活",
                    up ? s.probe() + " → HTTP " + p.code + "（" + p.ms + "ms）" : "连不上 127.0.0.1:" + s.port() + "（进程未启动？）"));
            if (up) ok++; else fail++;
        }

        // ② 鉴权是否生效：不带 cookie 访问 /api/menu 必须是 401，若是 200 说明默认拒绝被关掉了
        Probe menu = probe(8001, "/api/menu");
        boolean authOn = menu.code == 401 || menu.code == 403;
        items.add(item(authOn ? "ok" : (menu.code == 200 ? "fail" : "warn"),
                "后端鉴权生效（未登录访问 /api/menu）",
                "HTTP " + menu.code + (authOn ? " —— 已拦截" : menu.code == 200 ? " —— 未登录竟能取到菜单，鉴权异常！" : " —— 非预期状态码，需人工确认")));
        if (authOn) ok++; else if (menu.code == 200) fail++; else warn++;

        // ③ C 端已发布内容：匿名接口能取到 assets 键，说明"保存+发布"两步都走完了。
        //    ⚠️ 这个接口在**后端**而不是 C 端：nginx 把 /api 全部转给 :8001，直连 :3010 是 404。
        String cBody = body(8001, "/api/c/spire/content");
        boolean published = cBody != null && cBody.contains("assets");
        items.add(item(published ? "ok" : "warn", "C 端爬塔内容已发布",
                published ? "/api/c/spire/content 返回含 assets（" + cBody.length() + " 字节）" : "取不到已发布内容（未发布？）"));
        if (published) ok++; else warn++;

        // ④ C 端页面可访问。⚠️ 探测 /play/spire 而不是老的 /games/spire：
        //    游戏台迁到 /play/* 后 /games/spire 只剩一条 308 重定向，这里要验的是"页面本身能不能开"。
        String cPage = body(3010, "/play/spire");
        boolean pageOk = cPage != null && cPage.length() > 1000;
        items.add(item(pageOk ? "ok" : "fail", "C 端爬塔页可访问",
                pageOk ? "/play/spire → 200（" + cPage.length() + " 字节）" : "/play/spire 打不开"));
        if (pageOk) ok++; else fail++;

        // ⑤ B 端页面构建与"正在跑的构建"是否一致：页面 HTML 里的 BUILD_ID 必须等于磁盘上 .next/BUILD_ID
        //    —— 这一步能抓到"构建成功但 pm2 没重启 / 重启的是旧进程"这类事故
        String buildIdPath = R + "/notelab-b/.next/BUILD_ID";
        String buildId = readFirstLine(buildIdPath);
        String bPage = body(3020, "/admin/login");
        if (buildId == null || buildId.isEmpty()) {
            items.add(item("warn", "B 端构建产物一致性", "读不到 " + buildIdPath + "（未构建？）"));
            warn++;
        } else if (bPage == null) {
            items.add(item("fail", "B 端构建产物一致性", "/admin/login 打不开"));
            fail++;
        } else {
            boolean fresh = bPage.contains(buildId);
            items.add(item(fresh ? "ok" : "warn", "B 端构建产物一致性",
                    fresh ? "页面引用的 BUILD_ID 与磁盘一致（" + buildId + "）" : "页面 BUILD_ID 与磁盘 " + buildId + " 不一致 —— 可能构建后未重启"));
            if (fresh) ok++; else warn++;
        }

        // ⑥ 各仓与远端是否一致 + 工作区是否干净（sync-from-server 会覆盖本地未提交改动）
        for (Svc s : SVCS) {
            Map<String, Object> g = git(s.dir());
            if (g.get("commit") == null) {
                items.add(item("warn", s.name() + " 代码副本", "目录不存在或不是 git 仓库：" + s.dir()));
                warn++;
                continue;
            }
            boolean dirty = Integer.parseInt(String.valueOf(g.getOrDefault("dirty", 0))) > 0;
            boolean behind = Integer.parseInt(String.valueOf(g.getOrDefault("behind", 0))) > 0;
            boolean ahead = Integer.parseInt(String.valueOf(g.getOrDefault("ahead", 0))) > 0;
            String detail = "HEAD " + g.get("commit")
                    + (dirty ? " · 工作区有 " + g.get("dirty") + " 处未提交改动" : " · 工作区干净")
                    + (behind ? " · 落后远端 " + g.get("behind") + " 个提交（未 pull）" : "")
                    + (ahead ? " · 领先远端 " + g.get("ahead") + " 个提交（未 push）" : "");
            items.add(item((dirty || behind || ahead) ? "warn" : "ok", s.name() + " 代码副本", detail));
            if (dirty || behind || ahead) warn++; else ok++;
        }

        // ⑦ 主机资源阈值
        Map<String, Object> h = host();
        int diskPct = intOf(h.get("diskPercent"));
        int memPct = intOf(h.get("memPercent"));
        items.add(item(diskPct >= 85 ? "fail" : diskPct >= 75 ? "warn" : "ok", "磁盘占用", diskPct + "%" + (diskPct >= 85 ? " —— 接近写满，尽快清理" : "")));
        if (diskPct >= 85) fail++; else if (diskPct >= 75) warn++; else ok++;
        items.add(item(memPct >= 90 ? "fail" : memPct >= 80 ? "warn" : "ok", "内存占用", memPct + "%"));
        if (memPct >= 90) fail++; else if (memPct >= 80) warn++; else ok++;

        // ⑧ pm2 开机快照新鲜度：改过进程启动配置后必须 pm2 save，否则重启按旧快照复活
        Path dump = Path.of("/root/.pm2/dump.pm2");
        if (Files.exists(dump)) {
            long ageDays = (System.currentTimeMillis() - dump.toFile().lastModified()) / 86_400_000L;
            items.add(item(ageDays > 7 ? "warn" : "ok", "pm2 快照（开机自启）",
                    "dump.pm2 更新于 " + ageDays + " 天前" + (ageDays > 7 ? " —— 若近期改过启动配置，请补一次 pm2 save" : "")));
            if (ageDays > 7) warn++; else ok++;
        } else {
            items.add(item("warn", "pm2 快照（开机自启）", "未找到 /root/.pm2/dump.pm2 —— 机器重启后不会自动 resurrect"));
            warn++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("summary", Map.of("ok", ok, "warn", warn, "fail", fail, "ms", System.currentTimeMillis() - t0,
                "generatedAt", System.currentTimeMillis()));
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ 采集

    private Map<String, Object> service(Svc s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.pm2());
        m.put("name", s.name());
        m.put("port", s.port());
        m.put("dir", s.dir());
        m.put("probe", s.probe());
        Probe p = probe(s.port(), s.probe());
        m.put("http", Map.of("code", p.code, "ms", p.ms));
        m.put("up", p.code > 0);
        m.put("git", git(s.dir()));
        return m;
    }

    /** 仓库状态：commit / 主题 / 日期 / 脏文件数 / 与远端的前后差 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> git(String dir) {
        Map<String, Object> g = new LinkedHashMap<>();
        String[] head = exec(List.of("git", "-C", dir, "rev-parse", "--short", "HEAD"), CMD_TIMEOUT_SEC);
        if (head[1] != null && !head[1].isEmpty() || head[0].isEmpty()) {
            g.put("commit", null);
            return g;
        }
        g.put("commit", head[0]);
        String[] line = exec(List.of("git", "-C", dir, "log", "-1", "--format=%s|%ad", "--date=short"), CMD_TIMEOUT_SEC);
        if (!line[0].isEmpty()) {
            String[] kv = line[0].split("\\|", 2);
            g.put("subject", kv[0]);
            g.put("date", kv.length > 1 ? kv[1] : "");
        }
        // -uno：只统计**已跟踪文件**的改动。untracked 的临时文档（如 TASK-PROMPT-*.md）
        //      不影响部署，算进去会让看板天天报"脏"，反而把真正的改动淹没掉。
        String[] st = exec(List.of("git", "-C", dir, "status", "--porcelain", "-uno"), CMD_TIMEOUT_SEC);
        g.put("dirty", st[0].isEmpty() ? 0 : st[0].split("\n").length);
        String[] ab = exec(List.of("git", "-C", dir, "rev-list", "--left-right", "--count", "HEAD...origin/main"), CMD_TIMEOUT_SEC);
        if (!ab[0].isEmpty()) {
            String[] parts = ab[0].trim().split("\\s+");
            if (parts.length == 2) {
                g.put("ahead", Integer.parseInt(parts[0]));
                g.put("behind", Integer.parseInt(parts[1]));
            }
        }
        return g;
    }

    /** pm2 jlist → 精简后的进程列表；失败时返回 ok:false 与原因（页面靠端口探活兜底） */
    private Map<String, Object> pm2() {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] r = exec(List.of("pm2", "jlist"), CMD_TIMEOUT_SEC);
        if (r[0].isEmpty()) {
            out.put("ok", false);
            out.put("warning", r[1] == null || r[1].isEmpty() ? "pm2 jlist 无输出" : r[1]);
            out.put("procs", List.of());
            return out;
        }
        try {
            List<Map<String, Object>> raw = JsonUtil.MAPPER.readValue(r[0], List.class);
            List<Map<String, Object>> procs = new ArrayList<>();
            for (Map<String, Object> p : raw) {
                Map<String, Object> env = p.get("pm2_env") instanceof Map ? (Map<String, Object>) p.get("pm2_env") : Map.of();
                Map<String, Object> mon = p.get("monit") instanceof Map ? (Map<String, Object>) p.get("monit") : Map.of();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.get("name"));
                m.put("pid", p.get("pid"));
                m.put("status", env.get("status"));
                m.put("restarts", env.get("restart_time"));
                m.put("memMb", mon.get("memory") instanceof Number n ? Math.round(n.longValue() / 1048576.0) : 0);
                m.put("cpu", mon.get("cpu"));
                m.put("startedAt", env.get("pm_uptime"));
                procs.add(m);
            }
            out.put("ok", true);
            out.put("procs", procs);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("warning", "pm2 jlist 解析失败：" + e.getMessage());
            out.put("procs", List.of());
            return out;
        }
    }

    /** 主机资源：磁盘 / 内存 / 负载 */
    private Map<String, Object> host() {
        Map<String, Object> h = new LinkedHashMap<>();
        String[] df = exec(List.of("df", "-h", "/"), CMD_TIMEOUT_SEC);
        if (!df[0].isEmpty()) {
            String[] parts = df[0].split("\n");
            if (parts.length > 1) {
                String[] f = parts[1].trim().split("\\s+");
                if (f.length >= 5) {
                    h.put("diskTotal", f[1]);
                    h.put("diskUsed", f[2]);
                    h.put("diskAvail", f[3]);
                    h.put("diskPercent", Integer.parseInt(f[4].replace("%", "")));
                }
            }
        }
        String[] free = exec(List.of("free", "-m"), CMD_TIMEOUT_SEC);
        if (!free[0].isEmpty()) {
            for (String line : free[0].split("\n")) {
                if (line.startsWith("Mem:")) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length >= 3) {
                        long total = Long.parseLong(f[1]);
                        long used = Long.parseLong(f[2]);
                        h.put("memTotalMb", total);
                        h.put("memUsedMb", used);
                        h.put("memPercent", total > 0 ? Math.round(used * 100.0 / total) : 0);
                    }
                }
            }
        }
        String[] up = exec(List.of("uptime"), CMD_TIMEOUT_SEC);
        h.put("uptime", up[0]);
        return h;
    }

    // ------------------------------------------------------------------ 工具

    /** 只取状态码（通了就算活着；401/404 也说明进程在监听） */
    private Probe probe(int port, String path) {
        long t0 = System.currentTimeMillis();
        try {
            URL u = new URL("http://127.0.0.1:" + port + path);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(HTTP_TIMEOUT_MS);
            c.setReadTimeout(HTTP_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            c.disconnect();
            return new Probe(code, (int) (System.currentTimeMillis() - t0));
        } catch (Exception e) {
            return new Probe(0, (int) (System.currentTimeMillis() - t0));
        }
    }

    /**
     * 取响应体（截断到 512KB，只用于关键字判定）。
     *
     * <p>⚠️ 必须**手动**跟随 3xx：{@code HttpURLConnection} 的自动跟随不认 308，
     * 而 C 端老路径（/games/spire → /play/spire）现在正是 308 —— 不跟随会把
     * "页面搬过家"误报成"页面挂了"，自检天天一条红，真故障反而被淹没。
     */
    private String body(int port, String path) {
        String cur = path;
        for (int hop = 0; hop < 3; hop++) {
            try {
                URL u = new URL("http://127.0.0.1:" + port + cur);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(HTTP_TIMEOUT_MS);
                c.setReadTimeout(HTTP_TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    cur = sameHostPath(loc, port);
                    if (cur == null) return null;
                    continue;
                }
                if (code != 200) { c.disconnect(); return null; }
                byte[] buf = c.getInputStream().readAllBytes();
                c.disconnect();
                String s = new String(buf, StandardCharsets.UTF_8);
                return s.length() > 524_288 ? s.substring(0, 524_288) : s;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Location 可能是相对路径或完整 URL；只跟随同机同端口的跳转，跨站一律放弃（不给探测开出去的后门）。 */
    private static String sameHostPath(String loc, int port) {
        if (loc == null || loc.isEmpty()) return null;
        if (loc.startsWith("/")) return loc;
        String prefix = "http://127.0.0.1:" + port;
        if (loc.startsWith(prefix)) {
            int i = loc.indexOf('/', prefix.length());
            return i < 0 ? "/" : loc.substring(i);
        }
        return null;
    }

    private static String readFirstLine(String path) {
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, Object> item(String level, String name, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("name", name);
        m.put("detail", detail);
        return m;
    }

    private record Probe(int code, int ms) {}

    /**
     * 执行外部命令（**参数数组**，绝不拼 shell）。
     * 返回 {stdout(可能为空), stderrOrReason}；超时强制杀进程。
     */
    private static String[] exec(List<String> cmd, int timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (sb.length() < MAX_OUT) sb.append(line).append('\n');
                    }
                } catch (IOException ignored) { /* 进程被杀时会走到这里 */ }
            });
            t.setDaemon(true);
            t.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new String[]{"", "执行超时（>" + timeoutSec + "s）"};
            }
            t.join(2000);
            return new String[]{sb.toString().trim(), ""};
        } catch (Exception e) {
            return new String[]{"", String.valueOf(e.getMessage())};
        }
    }
}
