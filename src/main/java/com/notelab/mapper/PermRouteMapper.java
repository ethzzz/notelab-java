package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.PermRoute;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** perm_routes 表 Mapper。 */
public interface PermRouteMapper extends BaseMapper<PermRoute> {

    /** 路由注册 upsert（原 SQL 原样保留 + super_only 随启动回填） */
    @Insert("INSERT INTO perm_routes (code,path,method,kind,name,super_only) "
            + "VALUES (#{code},#{path},#{method},#{kind},#{name},#{superOnly}) "
            + "ON DUPLICATE KEY UPDATE path=VALUES(path), method=VALUES(method), kind=VALUES(kind), "
            + "name=VALUES(name), super_only=VALUES(super_only)")
    int upsertRoute(@Param("code") String code, @Param("path") String path, @Param("method") String method,
                    @Param("kind") String kind, @Param("name") String name,
                    @Param("superOnly") int superOnly);
}
