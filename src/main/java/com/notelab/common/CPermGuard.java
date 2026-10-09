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
     * @param routeCodes 该用户组持有的权限码（只看 {@code api:*}）
     * @return null 表示放行；非 null 是拒绝原因（写进 403 响应体，便于排障）
     */
    public static String denyReason(String path, Set<String> routeCodes) {
        if (path == null || path.isEmpty()) return null;
        String clean = normalize(path);

        // 非 C 端路径：不归本门禁管（C 端用户访问 B 端接口时，由那个 Controller 自己的会话判断拦）
        if (!(clean.equals("/api/c") || clean.startsWith(PermGuard.C_ENDPOINT_PREFIX))) return null;
        // 会话基础：登录/登出/拉自己 —— 一律放行
        if (C_SESSION_ENDPOINTS.contains(clean)) return null;

        List<String> ps = patterns();
        if (ps.isEmpty()) return null;   // 路由表没加载出来：fail-open

        List<String> hits = new ArrayList<>();
        for (String p : ps) {
            if (MATCHER.match(p, clean)) hits.add(p);
        }
        if (hits.isEmpty()) return null; // 未登记：fail-open（见类注释第 2 条）

        hits.sort(MATCHER.getPatternComparator(clean));
        String best = hits.get(0);
        String code = "api:" + best;
        if (routeCodes != null && routeCodes.contains(code)) return null;
        return "无权访问该接口（当前用户组缺少权限码 " + code + "）";
    }

    private static String normalize(String path) {
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
