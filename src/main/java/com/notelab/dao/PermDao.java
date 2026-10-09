package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.PermSide;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.PermRole;
import com.notelab.model.entity.PermRoute;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** RBAC 域 DAO：perm_routes / perm_roles / perm_role_routes 三表访问（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class PermDao {

    private PermDao() {}

    /** 原 listRoutes SQL 投影列序 */
    private static final String[] ROUTE_COLS = {"code", "path", "method", "kind", "name", "super_only", "side"};
    /** 原 listRoles/getRole SQL 投影列序 */
    private static final String[] ROLE_COLS = {"code", "name"};

    /**
     * 接口路由注册：side 由路径推导（{@link PermSide#ofApi}），superOnly 见 {@code PermGuard.isRestricted}。
     * 原 upsert SQL 由 Mapper 注解原样保留（ON DUPLICATE KEY UPDATE）。
     */
    public static void upsertRoute(String code, String path, String method, String kind, String name, boolean superOnly) {
        upsertRoute(code, path, method, kind, name, superOnly, PermSide.ofApi(path));
    }

    /**
     * 显式指定 side 的注册（**页面路由必须用这个**）。
     *
     * <p>为什么页面不能走路径推导：{@link PermSide#ofApi} 只认 {@code /api/c/} 前缀，
     * 而 C 端页面路径（{@code /posts}、{@code /play/spire}）长得和 B 端页面一模一样，
     * 推导只会把它们全算成 B 端 —— 端的归属对页面而言是**声明**，不是路径能推出来的。
     */
    public static void upsertRoute(String code, String path, String method, String kind, String name,
                                   boolean superOnly, String side) {
        DaoSupport.permRoute().upsertRoute(code, path, method, kind, name, superOnly ? 1 : 0,
                PermSide.normalize(side));
    }

    public static List<Map<String, Object>> listRoutes() {
        return RowUtil.rows(DaoSupport.permRoute().selectList(
                Wrappers.lambdaQuery(PermRoute.class)
                        .orderByAsc(PermRoute::getKind)
                        .orderByAsc(PermRoute::getCode)), ROUTE_COLS);
    }

    /**
     * 按端取路由（{@code side='b'} / {@code 'c'}）。
     *
     * <p>为什么必须按端查而不是全量下发让前端过滤：前端过滤只是"看不见"，
     * 权限码仍然会被提交、仍可能被手工请求写入。下发阶段就截掉，
     * 非法码连"合法候选集合"都进不去（{@code PermController.setRoleRoutes} 的 known 校验直接判死）。
     */
    public static List<Map<String, Object>> listRoutes(String side) {
        return RowUtil.rows(DaoSupport.permRoute().selectList(
                Wrappers.lambdaQuery(PermRoute.class)
                        .eq(PermRoute::getSide, side)
                        .orderByAsc(PermRoute::getKind)
                        .orderByAsc(PermRoute::getCode)), ROUTE_COLS);
    }

    /**
     * 清掉「代码里已不存在」的僵尸路由行（幂等，由 {@code PermService.registerAllRoutes} 每次启动调用）。
     *
     * <p>为什么需要：{@link #upsertRoute} **只做 upsert、从不删除** —— 路由改名或下线后旧行会永远
     * 留在 perm_routes 里，并被 /api/perm/overview 下发给「角色组管理 → 分配路由」，在菜单树里
     * 找不到对应叶子 → 显示成「📦 未挂菜单的页面」。
     * 实测：爬塔工坊 2026-09-26 从「单页 4 Tab」拆成 8 个子页后，旧的 {@code page:/spire-editor}
     * 在权限页上挂了一年（DB 44 条 vs 代码 43 条，多出来的就是它）。
     *
     * <p>⚠️ {@code keepPaths} 为空时**直接返回 0、不删任何东西**：宁可留着僵尸行，也绝不能因为
     * 上游采集失败（如 RequestMappingHandlerMapping 尚未就绪）把整张权限表清空 ——
     * 那会让所有接口变成「未在权限路由表登记」而全站 403。
     *
     * <p>⚠️ B/C 分流后必须**按 (kind, side) 分别调用**：C 端页面与 B 端页面各有一份 keepPaths，
     * 若合成一次 {@code kind='page'} 的清理，会把另一端的合法路由当僵尸删掉
     * （B 端 keepPaths 里没有 C 端路径，反之亦然）。
     *
     * @param kind      "page" / "api"
     * @param side      "b" / "c"
     * @param keepPaths 代码里当前真实存在的 path 集合（该端、该 kind 下的）
     * @return 删除的路由行数
     */
    public static int pruneRoutes(String kind, String side, Collection<String> keepPaths) {
        if (keepPaths == null || keepPaths.isEmpty()) return 0;
        return DaoSupport.tx().execute(status -> DaoSupport.permRoute().delete(
                Wrappers.lambdaQuery(PermRoute.class)
                        .eq(PermRoute::getKind, kind)
                        .eq(PermRoute::getSide, side)
                        .notIn(PermRoute::getPath, keepPaths)));
    }

    /** 清掉角色对已删权限码的持有（僵尸权限），见 {@code PermRoleRouteMapper#deleteOrphans}。 */
    public static int deleteOrphanRoleRoutes() {
        return DaoSupport.permRoleRoute().deleteOrphans();
    }

    /**
     * 全部「仅超管」的 api 权限码（{@code super_only=1}）。
     *
     * <p>给两个调用方用：① 启动时收回历史上被误授给非超管的这些码；
     * ② {@code PermController.setRoleRoutes} 拒绝授予。判定读库而不是读常量 ——
     * 让「哪些接口仅超管」在运行期只有一个数据源（启动前已按常量回填）。
     */
    public static List<String> superOnlyCodes() {
        List<String> out = new ArrayList<>();
        for (PermRoute r : DaoSupport.permRoute().selectList(
                Wrappers.lambdaQuery(PermRoute.class)
                        .eq(PermRoute::getSuperOnly, 1)
                        .select(PermRoute::getCode))) {
            if (r.getCode() != null) out.add(r.getCode());
        }
        return out;
    }

    /**
     * 批量收回这些权限码在所有角色上的持有（见 {@code PermRoleRouteMapper#deleteByCodes}）。
     * ⚠️ {@code codes} 为空时直接返回 0 —— 空集合拼成 {@code IN ()} 是语法错误。
     */
    public static int deleteRoleRoutesByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return 0;
        return DaoSupport.permRoleRoute().deleteByCodes(codes);
    }

    public static List<String> roleRouteCodes(String roleCode) {
        return DaoSupport.permRoleRoute().roleRouteCodes(roleCode);
    }

    /** DELETE + 批量 INSERT 为原子操作：事务包裹（静态方法不能用 @Transactional，走 TransactionTemplate） */
    public static void setRoleRoutes(String roleCode, List<String> codes) {
        DaoSupport.tx().executeWithoutResult(status -> {
            DaoSupport.permRoleRoute().deleteByRoleCode(roleCode);
            if (!codes.isEmpty()) {
                DaoSupport.permRoleRoute().insertIgnoreBatch(roleCode, codes);
            }
        });
    }

    public static List<Map<String, Object>> listRoles() {
        return RowUtil.rows(DaoSupport.permRole().selectList(
                Wrappers.lambdaQuery(PermRole.class)
                        .orderByAsc(PermRole::getCreatedAt)
                        .orderByAsc(PermRole::getCode)), ROLE_COLS);
    }

    public static Map<String, Object> getRole(String code) {
        return RowUtil.row(DaoSupport.permRole().selectById(code), ROLE_COLS);
    }

    public static boolean createRole(String code, String name) {
        PermRole r = new PermRole();
        r.setCode(code);
        r.setName(name);
        try {
            DaoSupport.permRole().insert(r);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public static void updateRoleName(String code, String name) {
        DaoSupport.permRole().update(Wrappers.lambdaUpdate(PermRole.class)
                .eq(PermRole::getCode, code)
                .set(PermRole::getName, name));
    }

    /** 两条 DELETE 原子执行：事务包裹 */
    public static void deleteRole(String code) {
        DaoSupport.tx().executeWithoutResult(status -> {
            DaoSupport.permRoleRoute().deleteByRoleCode(code);
            DaoSupport.permRole().deleteById(code);
        });
    }
}
