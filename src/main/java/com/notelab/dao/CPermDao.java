package com.notelab.dao;

import com.notelab.common.PermSide;
import com.notelab.common.RowUtil;
import com.notelab.model.ApiModules;
import com.notelab.model.entity.PermRoute;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * C 端权限域 DAO：{@code perm_routes(side='c')} 的读取 + {@code c_group_routes} 的读写。
 *
 * <p>与 {@link PermDao} 的分工：PermDao 管 B 端那一套（perm_roles / perm_role_routes），
 * 本类管 C 端这一套（c_user_groups / c_group_routes）。两端**不共用持有表**，
 * 从数据形态上杜绝「B 端角色持有 C 端接口码」这类错配。
 */
public final class CPermDao {

    private CPermDao() {}

    /** 路由行投影列序（与 PermDao.ROUTE_COLS 同构，多带 side 便于前端按端渲染） */
    private static final String[] ROUTE_COLS = {"code", "path", "method", "kind", "name", "super_only", "side"};

    /** 某用户组持有的权限码 */
    /** 权限码口径迁移：把「持有 oldCode 的用户组」镜像一份到 newCode 上（同 PermDao#cloneRoleRouteCode） */
    public static int cloneGroupRouteCode(String oldCode, String newCode) {
        return DaoSupport.cGroupRoute().cloneByOldCode(oldCode, newCode);
    }

    public static List<String> groupRouteCodes(String groupCode) {
        return DaoSupport.cGroupRoute().groupRouteCodes(groupCode);
    }

    /** DELETE + 批量 INSERT 原子执行（对齐 PermDao.setRoleRoutes） */
    public static void setGroupRoutes(String groupCode, List<String> codes) {
        DaoSupport.tx().executeWithoutResult(status -> {
            DaoSupport.cGroupRoute().deleteByGroupCode(groupCode);
            if (!codes.isEmpty()) {
                DaoSupport.cGroupRoute().insertIgnoreBatch(groupCode, codes);
            }
        });
    }

    /**
     * C 端全部路由（{@code side='c'}）。**只可能是 {@code /api/c/**} 接口** ——
     * C 端不做页面显隐（2026-10-10 起页面路由整类下线，见 {@code PermService.registerAllRoutes} 第 0 步），
     * 权限的作用点就只剩接口一层。
     *
     * <p>模块名由后端算好下发（同 {@link PermDao#listRoutes(String)} 的约定）：前端不持有「路径 → 模块」映射。
     */
    public static List<Map<String, Object>> listCRoutes() {
        List<Map<String, Object>> rows = RowUtil.rows(DaoSupport.permRoute().selectList(
                Wrappers.lambdaQuery(PermRoute.class)
                        .eq(PermRoute::getSide, PermSide.C)
                        .orderByAsc(PermRoute::getKind)
                        .orderByAsc(PermRoute::getCode)), ROUTE_COLS);
        for (Map<String, Object> r : rows) {
            if (!"api".equals(String.valueOf(r.get("kind")))) continue;
            String path = String.valueOf(r.get("path"));
            r.put("module", ApiModules.keyOf(path));
            r.put("module_name", ApiModules.label(path));
        }
        return rows;
    }

    /**
     * C 端全部权限码（不含会话基础端点，见 {@link com.notelab.common.CPermGuard#C_SESSION_ENDPOINTS}）。
     *
     * <p>用途：新建用户组 / 旧组首次初始化时**默认全量授予**，让「配权限」是收敛动作而不是放开动作 ——
     * C 端现状是所有人都能用全部功能，若默认零权限，一次重启就会把线上用户全闸掉。
     */
    public static List<String> allCRouteCodes() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : listCRoutes()) {
            String code = String.valueOf(r.get("code"));
            if (code != null && !code.isEmpty()) out.add(code);
        }
        return out;
    }

    /** 清掉「权限码已不存在」的持有行 */
    public static int deleteOrphanGroupRoutes() {
        return DaoSupport.cGroupRoute().deleteOrphans();
    }

    /** 清掉持有非 C 端路由码的行（历史脏数据自愈） */
    public static int deleteNonCSideGroupRoutes() {
        return DaoSupport.cGroupRoute().deleteNonCSide();
    }

    /** 清掉持有某类权限码的行（按 code 前缀），语义见 {@code CGroupRouteMapper#deleteByCodePrefix} */
    public static int deleteGroupRoutesByPrefix(String prefix) {
        return DaoSupport.cGroupRoute().deleteByCodePrefix(prefix);
    }
}
