package com.notelab.mapper;

import com.notelab.model.entity.PermRoleRoute;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * perm_role_routes 表 Mapper。
 * 复合主键 (role_code,route_code)，不继承 BaseMapper，全部走注解 SQL。
 */
public interface PermRoleRouteMapper {

    @Select("SELECT route_code FROM perm_role_routes WHERE role_code=#{roleCode} ORDER BY route_code")
    List<String> roleRouteCodes(@Param("roleCode") String roleCode);

    /** 批量 INSERT IGNORE（保持原语义：重复键静默跳过） */
    @Insert("<script>INSERT IGNORE INTO perm_role_routes (role_code,route_code) VALUES "
            + "<foreach collection='codes' item='c' separator=','>(#{roleCode},#{c})</foreach></script>")
    int insertIgnoreBatch(@Param("roleCode") String roleCode, @Param("codes") List<String> codes);

    @Delete("DELETE FROM perm_role_routes WHERE role_code=#{roleCode}")
    int deleteByRoleCode(@Param("roleCode") String roleCode);

    /**
     * 清掉「权限码已不在 perm_routes 里」的角色持有行（僵尸权限）。
     *
     * <p>配合 {@code PermDao.pruneRoutes} 使用：路由行被清掉后，角色对它的持有若不一起清，
     * 「角色组管理」里「已勾选 N 条」的计数会含幽灵条目（实际又不开通任何东西）。
     */
    @Delete("DELETE rr FROM perm_role_routes rr "
            + "LEFT JOIN perm_routes r ON r.code = rr.route_code WHERE r.code IS NULL")
    int deleteOrphans();

    /**
     * 批量收回指定权限码在**所有角色**上的持有。
     *
     * <p>用途：2026-10-09 起「仅超管」的接口非超管一律 403，历史上被勾出去的那些授予已是死数据，
     * 清掉才能让界面上的勾选状态变得诚实。
     * ⚠️ {@code codes} 不能为空 —— 空集合会被拼成 {@code IN ()} 语法错误，调用方必须先判空。
     */
    @Delete("<script>DELETE FROM perm_role_routes WHERE route_code IN "
            + "<foreach collection='codes' item='c' open='(' separator=',' close=')'>#{c}</foreach></script>")
    int deleteByCodes(@Param("codes") List<String> codes);

    /**
     * 把「持有 oldCode 的每个角色」都复制一份持有到 newCode 上（INSERT IGNORE，已持有则跳过）。
     *
     * <p>用途：权限码口径迁移（{@code api:<path>} → {@code api:<METHOD>:<path>}）。
     * 授权行里存的是**码**，码变了旧授权就成了孤儿，会被 prune/deleteOrphans 清掉 ——
     * 那等于把自建角色手上的接口权限静默清零。所以先把旧码的持有者镜像到新码上。
     *
     * <p>⚠️ 用 {@code INSERT ... SELECT} 而不是「查出角色列表再逐条插」：一条语句搞定，
     * 且不受「查完到写回之间又有人改了授权」的影响。
     */
    @Insert("INSERT IGNORE INTO perm_role_routes (role_code, route_code) "
            + "SELECT role_code, #{newCode} FROM perm_role_routes WHERE route_code = #{oldCode}")
    int cloneByOldCode(@Param("oldCode") String oldCode, @Param("newCode") String newCode);
}
