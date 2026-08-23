package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.PermRole;
import com.notelab.model.entity.PermRoute;
import org.springframework.dao.DuplicateKeyException;

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

    public static List<String> roleRouteCodes(String roleCode) {
        return DaoSupport.permRoleRoute().roleRouteCodes(roleCode);
    }

    public static void setRoleRoutes(String roleCode, List<String> codes) {
        DaoSupport.permRoleRoute().deleteByRoleCode(roleCode);
        if (!codes.isEmpty()) {
            DaoSupport.permRoleRoute().insertIgnoreBatch(roleCode, codes);
        }
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

    public static void deleteRole(String code) {
        DaoSupport.permRoleRoute().deleteByRoleCode(code);
        DaoSupport.permRole().deleteById(code);
    }
}
