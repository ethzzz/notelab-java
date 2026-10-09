package com.notelab.service;

import com.notelab.common.CPermGuard;
import com.notelab.common.PermGuard;
import com.notelab.common.PermSide;
import com.notelab.model.ApiModules;
import com.notelab.model.MenuTree;
import com.notelab.model.PageRoutes;
import com.notelab.model.RouteGroups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import com.notelab.dao.CPermDao;
import com.notelab.dao.CUserDao;
import com.notelab.dao.Db;
import com.notelab.dao.UserDao;
import com.notelab.dao.PermDao;

/**
 * RBAC 权限服务。
 *
 * 权限码规则：
 *  - 页面路由  code = "page:<path>"（如 page:/chat），驱动菜单可见性
 *  - API 路由  code = "api:<path>"（如 api:/api/chat），启动时从 SpringMVC 请求映射自动采集
 *
 * ⚠️ 2026-09-27 起 api:* **会被真正校验**：common.ApiPermInterceptor 对 /api/** 做**默认拒绝**，
 *    角色组没显式持有对应权限码则 403；user 组的 api 权限由 registerAllRoutes 第 5 步自动同步。
 *    C 端接口（/api/c/**）与未登录请求豁免 —— 本次只隔离 B 端登录用户。
 *
 * 自动注册：每次启动时把当前所有 Controller 路由 + 前端页面路由 upsert 进 perm_routes 表，
 * 以后新增路由无需手工登记，重启即自动出现在权限路由表里。
 *
 * 角色：super_admin（超级管理员，天然拥有全部路由，含未来新增）/ user（普通用户，按 perm_role_routes 分配）/
 *      external（外部账号，见下）。
 */
public final class PermService {

    public static final String ROLE_ADMIN = "super_admin";
    public static final String ROLE_USER = "user";

    /**
     * 外部账号：给**不注册账号的外部人员**使用的 B 端后台账号。
     *
     * <p>三条硬约束（与 ROLE_USER 的区别）：
     * <ol>
     *   <li><b>账号与密码只能由超管设置</b> —— 注册入口本就关闭；B 端也没有任何自助改密接口，
     *       唯一的改密入口 {@code POST /api/perm/users/{id}/password} 只对超管开放。
     *       所以外部账号连自己的密码都改不了，只能找超管重置。</li>
     *   <li><b>只能登录 admin 后台</b> —— B/C 端本就是两套身份体系（users vs c_users），外部账号天然进不了 C 端；
     *       而「后台之外」的子系统（ai-lab 走 nginx auth_request）由 {@code /api/auth/verify}
     *       按 {@code X-Auth-Purpose} 单独拒绝，见 AuthController#verify。</li>
     *   <li><b>不可被提升为超级管理员</b> —— PermController 的改角色接口显式拦截（单人 + 批量）。</li>
     * </ol>
     *
     * <p>默认权限：**只有「仪表盘」（page:/）**，其余路由由超管在「角色组管理 → 分配路由」里手工勾。
     * 之所以给一条底线而不是空权限：空清单会让登录后一片空白（连仪表盘都 403），是更糟的失败模式。
     */
    public static final String ROLE_EXTERNAL = "external";

    private static final Logger log = LoggerFactory.getLogger(PermService.class);

    private PermService() {}

    /** Bootstrap 在 Db.init() 之后调用。 */
    public static void registerAllRoutes(RequestMappingHandlerMapping mapping) {
        // 0) C 端页面路由的历史行自愈（一次性，2026-10-10）。
        //    ⚠️ 为什么必须排在 B 端页面注册**之前**：perm_routes 主键是 code，而 C 端首页与 B 端仪表盘
        //    的 code 都是 "page:/" —— 两端各 upsert 一次会互相覆盖 side，最后一行写进去的赢。
        //    先删掉 side='c' 的 page 行，再让第 1 步把 "page:/" 以 side='b' 重新插回来，才能收敛。
        //    C 端从此只管接口（页面不做显隐，见 CPermGuard 类注释），这步跑过一次后就不再有匹配行。
        int droppedCPage = PermDao.deleteRoutesByKindSide("page", PermSide.C);
        if (droppedCPage > 0) {
            log.info("已清除 {} 条 C 端页面路由（C 端权限只管接口，页面不做显隐）", droppedCPage);
        }
        int droppedCPageHold = CPermDao.deleteGroupRoutesByPrefix("page:");
        if (droppedCPageHold > 0) {
            log.info("已清除 {} 条 C 端用户组持有的页面权限码", droppedCPageHold);
        }
        // 1) 页面路由（B 端）
        Set<String> pagePaths = new LinkedHashSet<>();
        for (String[] p : PageRoutes.PAGE_ROUTES) {
            PermDao.upsertRoute("page:" + p[0], p[0], "", "page", p[1], false, PermSide.B);
            pagePaths.add(p[0]);
        }
        // 1b) 两份常量一致性：MenuTree.MENUS 的叶子 path 与 PageRoutes.PAGE_ROUTES 必须一一对应。
        //     ⚠️ 失配是**静默**的：只加菜单 → 叶子被 RBAC 过滤谁都看不见；只加路由 → 没有入口。
        //     所以这里只做**告警**不阻断（启动不该因为一个漏登记就起不来），把线索写进日志。
        List<String> leafPaths = MenuTree.leafPaths();
        Set<String> leafSet = new LinkedHashSet<>(leafPaths);
        List<String> onlyMenu = new ArrayList<>();
        for (String p : leafPaths) if (!pagePaths.contains(p)) onlyMenu.add(p);
        List<String> onlyRoute = new ArrayList<>();
        for (String p : pagePaths) if (!leafSet.contains(p)) onlyRoute.add(p);
        if (!onlyMenu.isEmpty() || !onlyRoute.isEmpty()) {
            log.warn("菜单树与页面路由表不一致：只在 MenuTree 有 {}；只在 PageRoutes 有 {}（前者会被 RBAC 过滤掉、后者没有入口）",
                    onlyMenu, onlyRoute);
        }
        // 2) API 路由：从 SpringMVC 请求映射自动采集（新增 Controller 重启即自动注册）
        Map<String, Set<String>> methodsByPath = new TreeMap<>();
        Map<String, String> nameByPath = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = new LinkedHashSet<>();
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatterns().forEach(pp -> patterns.add(pp.getPatternString()));
            } else if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            String methods = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("ANY");
            for (String path : patterns) {
                methodsByPath.computeIfAbsent(path, k -> new TreeSet<>()).add(methods);
                nameByPath.putIfAbsent(path, e.getValue().getMethod().getName());
            }
        }
        // superOnly 由**代码常量**决定（PermGuard.RESTRICTED_PREFIXES），启动回填进库。
        // 这样「仅超管」这件事有了数据形态：树能标锁、setRoleRoutes 能拒、denyReason 能兜底；
        // 而权威仍是常量 —— 改受限范围只需改常量，重启自动同步，不用手工改库。
        for (Map.Entry<String, Set<String>> e : methodsByPath.entrySet()) {
            PermDao.upsertRoute("api:" + e.getKey(), e.getKey(), String.join("|", e.getValue()),
                    "api", nameByPath.getOrDefault(e.getKey(), ""), PermGuard.isRestricted(e.getKey()));
        }
        // 2a) 接口按端分组：B 端 keepPaths 与 C 端 keepPaths 分开收集（第 2b 步按 (kind,side) 清理要用）。
        //     ⚠️ 不能只留一份合集：那样 C 端路径不在 B 端清单里会被当成僵尸删掉，反之亦然。
        Set<String> bApiPaths = new LinkedHashSet<>();
        Set<String> cApiPaths = new LinkedHashSet<>();
        for (String p : methodsByPath.keySet()) {
            (PermSide.C.equals(PermSide.ofApi(p)) ? cApiPaths : bApiPaths).add(p);
        }

        // 2b) 清理僵尸路由：代码里已不存在的 page/api 行。
        //     upsert 只增不删，旧行会一直挂在「角色组管理 → 分配路由」的「未挂菜单的页面」里
        //     （实测遗留：page:/spire-editor）。必须在第 5 步 syncUserApiPerms 之前做，
        //     否则同步会把僵尸 api 码重新发给 user 组。
        //     ⚠️ B/C 分流后必须**按 (kind, side) 四次调用**：端的归属不同，keepPaths 也不同。
        //     ⚠️ pruneRoutes 在 keepPaths 为空时不动手（见其注释），不会把权限表清空。
        int prunedPage = PermDao.pruneRoutes("page", PermSide.B, pagePaths);
        int prunedApi = PermDao.pruneRoutes("api", PermSide.B, bApiPaths);
        int prunedCApi = PermDao.pruneRoutes("api", PermSide.C, cApiPaths);
        int prunedHold = PermDao.deleteOrphanRoleRoutes();
        int prunedCHold = CPermDao.deleteOrphanGroupRoutes();
        int prunedCWrongSide = CPermDao.deleteNonCSideGroupRoutes();
        if (prunedPage + prunedApi + prunedCApi + prunedHold + prunedCHold + prunedCWrongSide > 0) {
            log.info("已清理僵尸路由：B端 page {} / api {} 条；C端 api {} 条；"
                            + "角色残留持有 {} 条 / C端组残留 {} 条 / C端组误持B端码 {} 条",
                    prunedPage, prunedApi, prunedCApi, prunedHold, prunedCHold, prunedCWrongSide);
        }
        // 2c) 收回历史上被误授的「仅超管」权限码。
        //     这些码在 2026-10-09 之前是**可以**勾给非超管角色的（PermGuard 不校验 RESTRICTED_PREFIXES，
        //     setRoleRoutes 也不拦），而 /api/c-admin、/api/analytics、/api/ui-config 三个前缀的
        //     Controller 自带零超管校验 —— 等于把 C 端用户管理、全站经营数据、全站界面配置送出去了。
        //     现在 denyReason 已兜底拦截（授权残留inert），这里再把残留行删掉，让界面上的勾选状态诚实。
        List<String> superOnlyCodes = PermDao.superOnlyCodes();
        if (!superOnlyCodes.isEmpty()) {
            int revoked = PermDao.deleteRoleRoutesByCodes(superOnlyCodes);
            if (revoked > 0) {
                log.warn("已收回 {} 条「仅超管」权限码的历史授予（这些接口非超管一律 403，授予本就无效）", revoked);
            }
        }
        // 3) 普通角色默认权限（仅首次为空时写入，不覆盖已有配置）：全部功能页，不含 ui/perm 管理页
        //    /stress-test（接口压测）、/ops（运维看板）也排除：都是超管专属运维工具，普通角色默认无权看到入口
        if (PermDao.roleRouteCodes(ROLE_USER).isEmpty()) {
            List<String> defaults = new ArrayList<>();
            for (String[] p : PageRoutes.PAGE_ROUTES) {
                if (!p[0].equals("/ui") && !p[0].equals("/perm") && !p[0].equals("/stress-test") && !p[0].equals("/ops"))
                    defaults.add("page:" + p[0]);
            }
            PermDao.setRoleRoutes(ROLE_USER, defaults);
        }
        // 3b) 外部账号默认权限（同样只在为空时写入）：**只给仪表盘**。
        //     比 user 组保守得多 —— 外部账号的定位就是「先能进后台，再按需逐条加」。
        //     代价：超管若把 external 的权限全部取消，重启后仪表盘会回来（与 user 组同构的既有行为）。
        if (PermDao.roleRouteCodes(ROLE_EXTERNAL).isEmpty()) {
            PermDao.setRoleRoutes(ROLE_EXTERNAL, List.of("page:/"));
        }
        // 4) 首次启动若尚无超级管理员：把最早注册的用户提升为超级管理员（避免无人可管理）
        if (UserDao.countSuperAdmins() == 0) {
            UserDao.promoteFirstUserToAdmin();
        }
        // 5) 默认拒绝下的普通用户组接口权限：非受限 api 补齐、受限 api 移除（见方法注释）
        syncUserApiPerms();
        // 5b) C 端用户组默认权限：**首次为空时给全部 C 端路由**（见 initCGroupRoutes 注释）
        initCGroupRoutes();
        // 5c) 归属表自检：页面↔接口模块的键有没有悬空（只 warn，见方法注释）
        checkRouteGroups();
        // 6) 刷新拦截器用的路由表。**必须排在最后**：要晚于上面所有 upsert，否则新路由不在表里
        PermGuard.reload();
        CPermGuard.reload();
    }

    /**
     * C 端用户组（c_user_groups）的默认路由：**空则补全部 C 端路由**，有配置就不动。
     *
     * <p>为什么默认全量而不是最小权限：C 端现状是所有人都能用全部功能（游戏、工具、内容下发），
     * 分流上线时若默认零权限，一次重启就把线上用户全闸掉 —— 那是事故不是安全。
     *  defaults 组持有全部码之后，超管想收哪个功能就取消哪条，是**收敛**动作。
     *
     * <p>⚠️ 只在「该组一条持有都没有」时写入（与 B 端 {@code ROLE_USER} 的同构逻辑一致）：
     * 超管把某组的权限全部取消后，重启会把全量再补回来 —— 这是已知取舍，
     * 与 B 端「取消 page:/ 后重启仪表盘会回来」是同一套行为，改动它要连 B 端一起改。
     */
    private static void initCGroupRoutes() {
        List<String> all = CPermDao.allCRouteCodes();
        if (all.isEmpty()) return;   // C 端路由表还没建起来（首启时序）→ 这次不动，下次启动补
        int seeded = 0;
        for (Map<String, Object> g : CUserDao.listGroupsWithCount()) {
            String code = String.valueOf(g.get("code"));
            if (code == null || code.isEmpty()) continue;
            if (!CPermDao.groupRouteCodes(code).isEmpty()) continue;
            CPermDao.setGroupRoutes(code, all);
            seeded++;
        }
        if (seeded > 0) {
            log.info("C 端用户组默认权限已初始化：{} 个组 × {} 条 C 端接口", seeded, all.size());
        }
    }

    /**
     * 普通用户组的 {@code api:*} 权限码**补齐 + 裁剪**（幂等，规则确定性）。
     *
     * <p>为什么非做不可：接口拦截器是**默认拒绝**的，角色组"恰好没勾"就等于"全禁止"。
     * 改造前 user 组能随便调 {@code /api/c-admin/users} 拿到全部 C 端用户，
     * 正是因为从来没人管过 {@code api:*} 这一栏 —— 光加拦截器不补数据，会把功能全闸掉。
     *
     * <p>为什么可以自动写：划分规则是确定的（非受限全给、受限全不给），没有需要人肉判断的余地，
     * 且用户已确认过受限范围（用户管理系统 + 界面配置）。
     *
     * <p>⚠️ **只动 {@code api:*}**：管理员工调整的 {@code page:*}（页面可见性）一律保留原样 ——
     * 那是另一回事，在这里重算会吃掉手工配置。
     *
     * <p>⚠️ 只处理 {@link #ROLE_USER}。自建角色组仍需管理员到「角色组管理」里勾 ——
     * 它们的意图没法推断，不该被代码覆盖。{@link #ROLE_EXTERNAL} 同理：它默认零 api 权限，
     * 由超管在「分配路由」里按需勾（这和「默认只给仪表盘」是配套的 ——
     * 仪表盘只调 /api/me 与 /api/menu，二者都在 PermGuard.SESSION_ENDPOINTS 里豁免，不需要任何 api 权限码）。
     */
    private static void syncUserApiPerms() {
        List<String> before = PermDao.roleRouteCodes(ROLE_USER);
        Set<String> keep = new LinkedHashSet<>();
        for (String c : before) {
            if (c != null && c.startsWith("page:")) keep.add(c);
        }
        int restricted = 0;
        int given = 0;
        // ⚠️ 只遍历 side='b'：C 端接口（/api/c/**）由 CPermGuard 按 c_user_groups 校验，
        //    发给 B 端 user 组毫无意义（B 端用户压根不会去调 C 端接口），只会把 permission 表搅浑。
        for (Map<String, Object> r : PermDao.listRoutes(PermSide.B)) {
            if (!"api".equals(String.valueOf(r.get("kind")))) continue;
            String p = String.valueOf(r.get("path"));
            if (p == null || p.isEmpty()) continue;
            // 判定用库里的 super_only（启动时已按 PermGuard.isRestricted 回填）——
            // 刻意不再直接问 PermGuard：让「仅超管」在运行期只有一个数据源，
            // 将来若改成可配置的受限范围，这里不用跟着改。
            if (PermGuard.isTruthyFlag(r.get("super_only"))) {
                restricted++;
                continue;
            }
            keep.add("api:" + p);
            given++;
        }
        List<String> want = new ArrayList<>(keep);
        // 无变化就不写库 —— 每次启动都做一遍 delete + insert 没意义
        if (before.size() == want.size() && new HashSet<>(before).containsAll(want)) {
            log.info("普通用户组接口权限已是最新（api {} 条给 / {} 条仅超管），跳过写库", given, restricted);
            return;
        }
        PermDao.setRoleRoutes(ROLE_USER, want);
        log.info("已同步普通用户组接口权限：api {} 条给 / {} 条仅超管，page 权限保留 {} 条",
                given, restricted, want.size() - given);
    }

    public static boolean isSuperAdmin(Map<String, Object> user) {
        return user != null && ROLE_ADMIN.equals(user.get("role"));
    }

    /** 是否外部账号（只由超管建号/改密、只允许在 B 端后台内使用、不可提升为超管） */
    public static boolean isExternal(Map<String, Object> user) {
        return user != null && ROLE_EXTERNAL.equals(String.valueOf(user.get("role")));
    }

    /** 角色是否有效：以 perm_roles 表为准（支持自建角色组） */
    public static boolean isValidRole(String role) {
        return role != null && PermDao.getRole(role) != null;
    }

    /** 用户是否拥有某页面路由权限（super_admin 一律放行） */
    public static boolean hasPageRoute(Map<String, Object> user, String path) {
        if (isSuperAdmin(user)) return true;
        if (user == null) return false;
        Object role = user.get("role");
        return role != null && PermDao.roleRouteCodes(role.toString()).contains("page:" + path);
    }

    /**
     * 当前用户可进入的页面路径清单（不含 "page:" 前缀）——「下发的路由」，供 B 端前端做页面级守卫：
     * 无论点菜单还是直接敲 URL，不在本清单内的页面一律跳无权限页。
     *
     * 取值方式：**以 PageRoutes.PAGE_ROUTES 为准遍历、再看角色是否持有对应权限码**，
     * 而不是直接回吐 perm_role_routes 里的 page:* 行——这样数据库里的历史/脏权限码
     * （比如早已下线的页面）不会凭空开通任何路径，清单永远只包含当前代码里真实存在的页面。
     *
     * super_admin：全部页面（与 MenuTree 一致），不受 role_routes 影响；
     * 未登录 / 无 role：空清单（调用方 /api/menu 本身要求登录，此处只作兜底）。
     */
    public static List<String> allowedPagePaths(Map<String, Object> user) {
        List<String> out = new ArrayList<>();
        if (user == null) return out;
        if (isSuperAdmin(user)) {
            for (String[] p : PageRoutes.PAGE_ROUTES) out.add(p[0]);
            return out;
        }
        Object role = user.get("role");
        if (role == null) return out;
        List<String> codes = PermDao.roleRouteCodes(role.toString());
        for (String[] p : PageRoutes.PAGE_ROUTES) {
            if (codes.contains("page:" + p[0])) out.add(p[0]);
        }
        return out;
    }


    // ---------- 数据访问收敛（ArchGuard no-bypass-existing-service）----------

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static List<Map<String, Object>> listRoles() {
        return PermDao.listRoles();
    }   // listRoles(0)

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static List<String> roleRouteCodes(String roleCode) {
        return PermDao.roleRouteCodes(roleCode);
    }   // roleRouteCodes(1)

    /**
     * 全部「仅超管」的 api 权限码（读库，启动时已按 PermGuard.RESTRICTED_PREFIXES 回填）。
     *
     * <p>ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService
     */
    public static List<String> superOnlyCodes() {
        return PermDao.superOnlyCodes();
    }   // superOnlyCodes(0)

    /**
     * 某一端（{@link PermSide#B} / {@link PermSide#C}）的全部权限码（页面 + api），
     * api 行**附带模块键与展示名**（来自 {@link ApiModules}）。
     *
     * <p>为什么按端下发而不是全量下发让前端过滤：前端过滤只是"看不见"，码仍然会被提交、
     * 仍可能经手工请求写库。下发阶段就截掉，非法码连「合法候选集合」都进不去。
     *
     * <p>为什么在服务端拼：模块名必须跟着**路由**走。放前端会出现「后端加了模块、前端忘了加」
     * 的静默失配 —— 此前前端硬编码 6 条，125 条路由里 71 条只能显示英文路径段。
     *
     * <p>ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService
     */
    public static List<Map<String, Object>> listRoutes(String side) {
        List<Map<String, Object>> rows = PermDao.listRoutes(side);
        for (Map<String, Object> r : rows) {
            String path = String.valueOf(r.get("path"));
            if ("api".equals(String.valueOf(r.get("kind")))) {
                // 键与展示名都由后端算好后下发，前端不再持有任何模块逻辑
                String module = ApiModules.keyOf(path);
                r.put("module", module);
                r.put("module_name", ApiModules.label(path));
                // 多个页面共用的模块：告诉前端挂到哪个菜单分组下（见 RouteGroups.SHARED_MODULES）
                String grp = RouteGroups.MODULE_MENU_GROUP.get(module);
                if (grp != null) r.put("menu_group", grp);
            } else if ("page".equals(String.valueOf(r.get("kind")))) {
                // 页面用到的接口模块：分配路由时「勾页面连带勾这些接口」的依据。
                // 权威在后端 RouteGroups，前端不再持有归属表（避免两端各写一半的静默失配）。
                r.put("modules", RouteGroups.modulesOfPage(path));
            }
        }
        return rows;
    }   // listRoutes(1)

    /**
     * 校验「页面 ↔ 接口模块」归属表里有没有悬空的键（启动时跑一次，只 warn 不阻断）。
     *
     * <p>悬空 = 归属表里写了某个模块键，但当前没有任何接口用它。成因通常是拼错或模块已下线 ——
     * 这类错误在前端持有归属表时完全静默（模块落进「系统通用」分组，看着像配过了），
     * 搬到后端后才能在这里被喊出来。
     */
    private static void checkRouteGroups() {
        Set<String> live = new LinkedHashSet<>();
        for (Map<String, Object> r : PermDao.listRoutes(PermSide.B)) {
            if (!"api".equals(String.valueOf(r.get("kind")))) continue;
            live.add(ApiModules.keyOf(String.valueOf(r.get("path"))));
        }
        // ① 正向：归属表里声明了、却没有任何接口在用（拼错 / 模块已下线）
        List<String> bad = RouteGroups.unknownModules(live);
        if (!bad.isEmpty()) {
            log.warn("页面↔接口归属表里有 {} 个悬空模块键（没有任何接口在用，通常是拼错或模块已下线）：{}",
                    bad.size(), bad);
        }
        // ② 反向：有接口在用、却没有页面或分组认领（新增接口时漏了 RouteGroups.PAGE_MODULES 那一步）。
        //    后果是它落进分配树底部的「系统通用」分组，管理员找不到 —— 症状比①更难察觉。
        List<String> unclaimed = RouteGroups.unclaimedModules(live);
        if (!unclaimed.isEmpty()) {
            log.warn("有 {} 个接口模块无人认领（会落进「系统通用」分组）：{}"
                            + " —— 新增接口后请到 model/RouteGroups.PAGE_MODULES 声明它属于哪个页面",
                    unclaimed.size(), unclaimed);
        }
    }

    // ---------- C 端用户组路由（与 B 端角色组完全分离） ----------

    /** C 端用户组持有的权限码。ArchGuard 收敛：controller 不再直调 CPermDao */
    public static List<String> cGroupRouteCodes(String groupCode) {
        return CPermDao.groupRouteCodes(groupCode);
    }   // cGroupRouteCodes(1)

    /** 覆写 C 端用户组的权限码集合。ArchGuard 收敛：controller 不再直调 CPermDao */
    public static void setCGroupRoutes(String groupCode, List<String> codes) {
        CPermDao.setGroupRoutes(groupCode, codes);
    }   // setCGroupRoutes(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static List<Map<String, Object>> listUsersForPerm() {
        return UserDao.listUsersForPerm();
    }   // listUsersForPerm(0)

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static boolean createRole(String code, String name) {
        return PermDao.createRole(code, name);
    }   // createRole(2)

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static Map<String, Object> getRole(String code) {
        return PermDao.getRole(code);
    }   // getRole(1)

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static void updateRoleName(String code, String name) {
        PermDao.updateRoleName(code, name);
    }   // updateRoleName(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static long countUsersByRole(String role) {
        return UserDao.countUsersByRole(role);
    }   // countUsersByRole(1)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static void migrateUsersToRole(String fromRole, String toRole) {
        UserDao.migrateUsersToRole(fromRole, toRole);
    }   // migrateUsersToRole(2)

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static void deleteRole(String code) {
        PermDao.deleteRole(code);
    }   // deleteRole(1)

    /** ArchGuard 收敛：controller 不再直调 PermDao，统一经 PermService */
    public static void setRoleRoutes(String roleCode, List<String> codes) {
        PermDao.setRoleRoutes(roleCode, codes);
    }   // setRoleRoutes(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static long countUsersFiltered(String q, String role) {
        return UserDao.countUsersFiltered(q, role);
    }   // countUsersFiltered(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static List<Map<String, Object>> listUsersPaged(String q, String role, int limit, long offset) {
        return UserDao.listUsersPaged(q, role, limit, offset);
    }   // listUsersPaged(4)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static Map<String, Object> getUserByUsername(String username) {
        return UserDao.getUserByUsername(username);
    }   // getUserByUsername(1)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static Map<String, Object> getUserByEmail(String email) {
        return UserDao.getUserByEmail(email);
    }   // getUserByEmail(1)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static long createUserWithRole(String username, String passwordHash, String email, String role) {
        return UserDao.createUserWithRole(username, passwordHash, email, role);
    }   // createUserWithRole(4)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static Map<String, Object> getUserById(long id) {
        return UserDao.getUserById(id);
    }   // getUserById(1)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static void updateUserInfo(long id, String username, String email) {
        UserDao.updateUserInfo(id, username, email);
    }   // updateUserInfo(3)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static long countSuperAdmins() {
        return UserDao.countSuperAdmins();
    }   // countSuperAdmins(0)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static void setUserRole(long uid, String role) {
        UserDao.setUserRole(uid, role);
    }   // setUserRole(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static List<Map<String, Object>> listUsersByIds(List<Long> ids) {
        return UserDao.listUsersByIds(ids);
    }   // listUsersByIds(1)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static void setUsersRole(List<Long> ids, String role) {
        UserDao.setUsersRole(ids, role);
    }   // setUsersRole(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static void setUserPassword(long uid, String passwordHash) {
        UserDao.setUserPassword(uid, passwordHash);
    }   // setUserPassword(2)

    /** ArchGuard 收敛：controller 不再直调 UserDao，统一经 PermService */
    public static void deleteUser(long id) {
        UserDao.deleteUser(id);
    }   // deleteUser(1)
}
