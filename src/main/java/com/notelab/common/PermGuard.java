package com.notelab.common;

import com.notelab.dao.PermDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口级权限门禁：**默认拒绝**（没显式持有权限码就放行？不 —— 一律拦下）。
 *
 * <p>背景：此前 {@code perm_routes} 里的 {@code api:*} 权限码**只登记、不校验** —— 全仓唯一的
 * {@code WebMvcConfigurer} 只配了 CORS，没有拦截器；于是接口能否访问完全取决于「那个 Controller
 * 有没有手写 {@code isSuperAdmin} 判断」。实测同一个 user 角色账号：
 * GET /api/perm/overview → 403（手写了判断），GET /api/c-admin/users → 200（没写）。
 * 本类把这层补齐，让「勾了权限码」这件事**真正有约束力**。
 *
 * <p>判定顺序（见 {@link #denyReason}）：C 端前缀 → 会话基础端点 → 路由表未 loading 兜底 →
 * 路径匹配 → 权限码校验。**越具体者胜**（/api/perm/roles/{code} 优先于 /api/perm/roles）。
 *
 * <p>⚠️ 两处刻意的设计取舍：
 * <ol>
 *   <li>**路径匹配漏了就等于拒绝**（默认拒绝的字面含义）。所以新增 Controller 后必须重启，
 *       路由才会进 {@code perm_routes} —— 这与既有机制一致，不是新约束。</li>
 *   <li>**路由表读不到时放行**（fail-open，仅限这一处）。数据库不可用时业务本来就全挂，
 *       再让门禁把存活的请求闸掉只会让故障更难判断；反之若强行拒绝，一次 DB 抖动就全站 403。</li>
 * </ol>
 */
public final class PermGuard {

    private static final Logger log = LoggerFactory.getLogger(PermGuard.class);

    /**
     * C 端接口前缀。C 端是**另一套身份体系**（{@code c_users} 表 + Cookie {@code notelab_c_session}），
     * 与 B 端 {@code notelab_session} 互不互认 —— 所以 C 端接口一律不参与本次 B 端权限隔离，
     * 仍由各自的 Controller 自行判断。
     */
    public static final String C_ENDPOINT_PREFIX = "/api/c/";

    /**
     * 受限前缀：这些接口**只给 super_admin**，对应「用户管理系统」+ 界面配置。
     *
     * <p>⚠️ {@code /api/c-admin} 名字看着像 C 端，实际是 **B 端管理员管理 C 端用户**（对应页面
     * /c-users、/user/invites），别因为它以 c 开头就误放进 C 端豁免。
     */
    public static final List<String> RESTRICTED_PREFIXES = List.of(
            "/api/perm",        // 角色组 / 权限码 / 成员角色（对应 /perm 页面）
            "/api/c-admin",     // C 端用户管理与邀请码（对应 /c-users 页面）
            "/api/ui-config"    // 界面配置（对应 /ui 页面，可改全站菜单名/图标/背景）
    );

    /**
     * 会话基础端点：任何**已登录**用户都必须能访问，否则连登录、钱包、菜单都走不通。
     * 它们自身的安全性由对应 Controller 的 {@code AuthUtil.user(request) == null} 保证（未登录 → 401），
     * 这里不能再叠一层权限码门槛。
     */
    public static final Set<String> SESSION_ENDPOINTS = Set.of(
            "/api/login", "/api/logout", "/api/me", "/api/register",
            "/api/auth/verify", "/api/menu"
    );

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 已登记的 api 路径 pattern（含 {var}），懒加载 + 启动时由 {@link PermService} 显式刷新 */
    private static volatile List<String> apiPatterns;

    private PermGuard() {}

    /** 该 api 路径是否属受限（精确等于该前缀，或位于其下；避免误伤 /api/ui-configuration 这类未来路径） */
    public static boolean isRestricted(String apiPath) {
        if (apiPath == null) return false;
        for (String p : RESTRICTED_PREFIXES) {
            if (apiPath.equals(p) || apiPath.startsWith(p + "/")) return true;
        }
        return false;
    }

    /** 从 perm_routes 重新加载 api 路径表。读库失败时**保留原值**（不清空），避免抖动把站点闸死。 */
    public static void reload() {
        List<String> loaded = new ArrayList<>();
        try {
            for (Map<String, Object> r : PermDao.listRoutes()) {
                if (!"api".equals(String.valueOf(r.get("kind")))) continue;
                String p = String.valueOf(r.get("path"));
                if (p != null && !p.isEmpty()) loaded.add(p);
            }
        } catch (Exception e) {
            log.warn("加载 perm_routes 失败，接口门禁维持原路由表（不清空）：{}", e);
            return;
        }
        apiPatterns = List.copyOf(loaded);
        log.info("接口门禁已加载 {} 条 api 路由，受限前缀 {} 个", loaded.size(), RESTRICTED_PREFIXES.size());
    }

    private static List<String> patterns() {
        List<String> p = apiPatterns;
        if (p == null) {
            synchronized (PermGuard.class) {
                if (apiPatterns == null) reload();
                p = apiPatterns;
            }
            return p == null ? List.of() : p;
        }
        return p;
    }

    /**
     * 判定是否拒绝该请求。
     *
     * @param path       应用内路径（如 /api/c-admin/users）
     * @param routeCodes 该角色当前持有的权限码集合（可能含 page:*，这里只看 api:*）
     * @return null 表示放行；非 null 是拒绝原因（写进 403 响应体，便于排障而不是干巴巴一个 403）
     */
    public static String denyReason(String path, Set<String> routeCodes) {
        if (path == null || path.isEmpty()) return "路径为空";
        String clean = normalize(path);

        // C 端体系：不参与 B 端权限隔离
        if (clean.startsWith(C_ENDPOINT_PREFIX)) return null;
        // 会话基础：任何登录用户都放行
        if (SESSION_ENDPOINTS.contains(clean)) return null;

        List<String> ps = patterns();
        if (ps.isEmpty()) return null;   // 路由表没加载出来：走 fail-open，别把整站闸死

        List<String> hits = new ArrayList<>();
        for (String p : ps) {
            if (MATCHER.match(p, clean)) hits.add(p);
        }
        if (hits.isEmpty()) {
            return "该接口未在权限路由表登记：" + clean;
        }
        // 越具体的 pattern 越优先（/api/perm/roles/{code} 胜过 /api/perm/roles）
        hits.sort(MATCHER.getPatternComparator(clean));
        String code = "api:" + hits.get(0);
        if (routeCodes != null && routeCodes.contains(code)) return null;
        return "无权访问该接口（缺少权限码 " + code + "）";
    }

    private static String normalize(String path) {
        String p = path.trim();
        // 去尾部斜杠（/api/login/ 与 /api/login 同一回事），但保留根路径
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
