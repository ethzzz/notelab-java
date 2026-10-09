package com.notelab.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * c_group_routes 表 Mapper（C 端用户组的路由持有）。
 *
 * <p>与 {@link PermRoleRouteMapper} 同构（复合主键 + 全部注解 SQL），但**刻意独立一张表**：
 * 两端共用一张持有表后，「B 端角色持有 C 端接口码」这种错配在库里无法与正常数据区分，
 * 只能靠应用逻辑拦；分表之后错配根本写不进去（外键虽未建，但 deleteNonCSide 会周期性清掉）。
 */
public interface CGroupRouteMapper {

    @Select("SELECT route_code FROM c_group_routes WHERE group_code=#{groupCode} ORDER BY route_code")
    List<String> groupRouteCodes(@Param("groupCode") String groupCode);

    /** 批量 INSERT IGNORE（对齐 PermRoleRouteMapper：重复键静默跳过） */
    @Insert("<script>INSERT IGNORE INTO c_group_routes (group_code,route_code) VALUES "
            + "<foreach collection='codes' item='c' separator=','>(#{groupCode},#{c})</foreach></script>")
    int insertIgnoreBatch(@Param("groupCode") String groupCode, @Param("codes") List<String> codes);

    @Delete("DELETE FROM c_group_routes WHERE group_code=#{groupCode}")
    int deleteByGroupCode(@Param("groupCode") String groupCode);

    /** 清掉「权限码已不在 perm_routes 里」的持有行（僵尸权限），语义同 PermRoleRouteMapper#deleteOrphans */
    @Delete("DELETE gr FROM c_group_routes gr "
            + "LEFT JOIN perm_routes r ON r.code = gr.route_code WHERE r.code IS NULL")
    int deleteOrphans();

    /**
     * 清掉持有**非 C 端**路由码的行（历史脏数据 / 手工 SQL 写入）。
     *
     * <p>为什么需要：授权接口只在写入时校验，而 B/C 分流上线前若有人往这张表写过 B 端码
     * （或改了某条路由的 side），那些行就会一直挂着。周期性清一次，让数据自己收敛到正确形态。
     */
    @Delete("DELETE gr FROM c_group_routes gr "
            + "JOIN perm_routes r ON r.code = gr.route_code WHERE r.side <> 'c'")
    int deleteNonCSide();
}
