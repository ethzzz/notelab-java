package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.PermRole;
import com.notelab.model.entity.PermRoute;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** RBAC 域 DAO：perm_routes / perm_roles / perm_role_routes 三表访问（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class PermDao {

    private PermDao() {}

    /** 原 listRoutes SQL 投影列序 */
    private static final String[] ROUTE_COLS = {"code", "path", "method", "kind", "name"};
    /** 原 listRoles/getRole SQL 投影列序 */
    private static final String[] ROLE_COLS = {"code", "name"};

    /** 原 upsert SQL 由 Mapper 注解原样保留（ON DUPLICATE KEY UPDATE） */
    public static void upsertRoute(String code, String path, String method, String kind, String name) {
        DaoSupport.permRoute().upsertRoute(code, path, method, kind, name);
    }

    public static List<Map<String, Object>> listRoutes() {
        return RowUtil.rows(DaoSupport.permRoute().selectList(
                Wrappers.lambdaQuery(PermRoute.class)
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
     * @param kind      "page" / "api"
     * @param keepPaths 代码里当前真实存在的 path 集合
     * @return 删除的路由行数
     */
    public static int pruneRoutes(String kind, Collection<String> keepPaths) {
        if (keepPaths == null || keepPaths.isEmpty()) return 0;
        return DaoSupport.tx().execute(status -> DaoSupport.permRoute().delete(
                Wrappers.lambdaQuery(PermRoute.class)
                        .eq(PermRoute::getKind, kind)
                        .notIn(PermRoute::getPath, keepPaths)));
    }

    /** 清掉角色对已删权限码的持有（僵尸权限），见 {@code PermRoleRouteMapper#deleteOrphans}。 */
    public static int deleteOrphanRoleRoutes() {
        return DaoSupport.permRoleRoute().deleteOrphans();
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
