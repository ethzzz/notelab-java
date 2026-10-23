package com.notelab.common;

import com.notelab.dao.CPermDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * C 端接口门禁：按**用户组**（{@code c_user_groups}）持有的路由码校验 {@code /api/c/**}。
 *
 * <p>⚠️ **只管接口，不管页面**（2026-10-10 定）：C 端页面（首页 / 笔记 / 游戏 / 工具）一律不做显隐，
 * 入口永远全部可见。代价是用户可能点进一个内容全空的页面 —— 可接受，因为页面本身不承载机密，
 * 且「入口还在但数据拉不到」比「入口莫名消失」更容易自查。权限的作用点收敛到接口一层。
 *
 * <p>与 B 端 {@link PermGuard} 的关系：同构但**刻意分开** —— 两端身份体系不同
 * （B 端 {@code users.role} / C 端 {@code c_users.group_code}），判定入口、豁免清单、
 * 缓存的路由表都不是一回事。混在一个类里只会让「这条路该按哪套规则走」变得暧昧。
 *
 * <p>三处与 B 端**不同**的取舍（都是为了让改造不打断线上）：
 * <ol>
 *   <li>**未登录一律放行**。C 端大量接口本来就是匿名的（站点配置 {@code /api/c/config/**}、
 *       爬塔/摸金内容下发、埋点 {@code /api/c/track}），登录态不是前提；
 *       它们各自的安全性由 Controller 的 {@code CAuthUtil.user(request) == null} 保证。</li>
 *   <li>**未登记的接口放行**（fail-open）。B 端是「未登记即拒绝」，因为 B 端路由全是内部接口、
 *       漏登记意味着有人新加了 Controller 忘了重启。C 端相反：内容下发类接口常有匿名入口，
 *       一次采集失败就全站 403 的代价太大。</li>
 *   <li>**新建用户组默认持有全部 C 端路由**（见 {@code PermService} 的初始化）。
 *       C 端现状是人人可用全部功能，若默认零权限，一次重启就把线上用户全闸了 ——
 *       权限配置应该是**收敛**动作（超管主动取消勾选），不是放开动作。</li>
 * </ol>
 */
public final class CPermGuard {

    private static final Logger log = LoggerFactory.getLogger(CPermGuard.class);

    /**
     * C 端会话基础端点：登录用户必须能访问，否则登录/登出/拉会话自身都走不通。
     * 它们的安全性由 Controller 自己的 {@code CAuthUtil.user(request) == null → 401} 保证。
     */
    public static final Set<String> C_SESSION_ENDPOINTS = Set.of(
            "/api/c/auth/login", "/api/c/auth/logout", "/api/c/auth/me", "/api/c/auth/register"
    );

    /**
     * C 端「我自己的」端点前缀（PRD-P5）：**登录即可用，不查用户组授权**。
     *
     * <p>为什么必须豁免而不是让大家去后台勾选：
     * <ol>
     *   <li><b>语义上不该有这道门</b>：「某些用户组不允许改自己的昵称/密码」不是一种合理的配置。
     *       权限码机制是给「内容下发」这类**真有启用/停用意义**的能力用的。</li>
     *   <li><b>不豁免就是静默事故</b>：{@code PermService.initCGroupRoutes()} 只在「该组一条持有都没有」
     *       时补全量，所以**新增接口对既有用户组默认是 403**（2026-10-12 实测：/api/c/me/saves
     *       对 default 组返回 403 而非 401/200）。每个 C 端用户组都得去后台手工勾一次，
     *       漏一个就是那个组全员不可用 —— 而这类端点根本没有「该不该给」的余地。</li>
     * </ol>
     *
     * <p>安全性由 Controller 自己保证（与 {@link #C_SESSION_ENDPOINTS} 同档）：
     * {@code CMeController} 每个方法都先过 {@code CAuthUtil.user(request) == null → 401}，
     * 且只操作**当前会话的 uid**，无法越权改到别人。
     */
    private static final List<String> C_SELF_PREFIXES = List.of("/api/c/me/");

    /**
     * C 端「**全体会员功能**」端点前缀（PRD-P9 社区）：登录即可用，不查用户组授权。
     *
     * <p>为什么不用「部署后去后台给每个组勾选」：
     * <ol>
     *   <li>社区是一级模块、接口有十几个，每次新增都要给每个 C 端用户组勾一次；漏一个就是那个组
     *       全员不可用，而报错是 403（像"没权限"）不是 401（像"没登录"），排查成本极高。
     *       这与 PRD-P5 撞过的坑完全同构。</li>
     *   <li>语义上不该有这道门：「某些用户组不许看帖/发帖」不是合理的配置维度。
     *       要限制某人**发帖**，正确手段是按人禁言（{@code cm_user_profile.muted_until}）；
     *       要封号，手段是 {@code c_users.status='disabled'}（登录就被 CAuthController 拦住）。
     *       两者都比按用户组关接口更精确。</li>
     * </ol>
     *
     * <p>⚠️ 代价（明确接受）：后台**无法**通过用户组关停社区功能。若将来真有「仅付费用户可发帖」
     * 这类需求，应当把它做成业务判定（读某个 config 或按人授予资格），而不是把这行删掉改回权限码
     * —— 删掉这行的后果是既有用户组全部 403。
     *
     * <p>⚠️ 判定必须在 {@code hits} **之前**返回（与 {@link #C_SELF_PREFIXES} 同一位置）：
     * 这些路径也是被 SpringMVC 采集登记进 perm_routes 的，走到下面就会因为「组里没这个码」而 403。
     */
    private static final List<String> C_MEMBER_PREFIXES = List.of("/api/c/community/");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 已登记的 C 端 api 路径 pattern（含 {var}），启动时由 PermService 刷新 */
    private static volatile List<String> cPatterns;

    private CPermGuard() {}

    /** 从 perm_routes(side='c') 重新加载。读库失败时**保留原值**（不清空），避免抖动把站点闸死。 */
    public static void reload() {
        List<String> loaded = new ArrayList<>();
        try {
            for (java.util.Map<String, Object> r : CPermDao.listCRoutes()) {
                if (!"api".equals(String.valueOf(r.get("kind")))) continue;
                String p = String.valueOf(r.get("path"));
                if (p == null || p.isEmpty()) continue;
                loaded.add(p);
            }
        } catch (Exception e) {
            log.warn("加载 C 端路由表失败，C 端门禁维持原路由表（不清空）：{}", e);
            return;
        }
        cPatterns = List.copyOf(loaded);
        log.info("C 端接口门禁已加载 {} 条 api 路由（会话基础豁免 {} 条）",
                loaded.size(), C_SESSION_ENDPOINTS.size());
    }

    private static List<String> patterns() {
        List<String> p = cPatterns;
        if (p == null) {
            synchronized (CPermGuard.class) {
                if (cPatterns == null) reload();
                p = cPatterns;
            }
            return p == null ? List.of() : p;
        }
        return p;
    }

    /**
     * 判定是否拒绝该 C 端请求。
     *
     * @param path       应用内路径（如 /api/c/game/save）
     * @param method     HTTP 方法。⚠️ 与 B 端同口径：权限码按方法拆分（{@link PermGuard#apiCode}）
     * @param routeCodes 该用户组持有的权限码（只看 {@code api:*}）
     * @return null 表示放行；非 null 是拒绝原因（写进 403 响应体，便于排障）
     */
    public static String denyReason(String path, String method, Set<String> routeCodes) {
        if (path == null || path.isEmpty()) return null;
        String clean = normalize(path);

        // 非 C 端路径：不归本门禁管（C 端用户访问 B 端接口时，由那个 Controller 自己的会话判断拦）
        if (!(clean.equals("/api/c") || clean.startsWith(PermGuard.C_ENDPOINT_PREFIX))) return null;
        // 会话基础：登录/登出/拉自己 —— 一律放行
        if (C_SESSION_ENDPOINTS.contains(clean)) return null;

        // 「我自己的」端点（改自己昵称/密码/看自己存档）：登录即可，不查用户组授权。
        // ⚠️ 必须在 hits 判定**之前**返回 —— 这些路径也是被 SpringMVC 采集登记过的，
        //    走到下面就会因为「组里没这个码」而 403。
        for (String pre : C_SELF_PREFIXES) {
            if (clean.startsWith(pre)) return null;
        }

        // 「全体会员功能」（社区，PRD-P9）：同 C_SELF_PREFIXES，登录即可用，不查用户组授权。
        // ⚠️ 同样必须在 hits 判定**之前** —— 这些路径已被采集登记，走到下面必然 403。
        for (String pre : C_MEMBER_PREFIXES) {
            if (clean.startsWith(pre)) return null;
        }

        List<String> ps = patterns();
        if (ps.isEmpty()) return null;   // 路由表没加载出来：fail-open

        List<String> hits = new ArrayList<>();
        for (String p : ps) {
            if (MATCHER.match(p, clean)) hits.add(p);
        }
        if (hits.isEmpty()) return null; // 未登记：fail-open（见类注释第 2 条）

        hits.sort(MATCHER.getPatternComparator(clean));
        String best = hits.get(0);
        String code = PermGuard.apiCode(method, best);
        if (routeCodes != null && routeCodes.contains(code)) return null;
        // 同 B 端：没写 method 的 handler 登记为 ANY，对所有方法都适用
        if (routeCodes != null && routeCodes.contains(PermGuard.apiCode(PermGuard.ANY_METHOD, best))) return null;
        return "无权访问该接口（当前用户组缺少权限码 " + code + "）";
    }

    private static String normalize(String path) {
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
