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
}
