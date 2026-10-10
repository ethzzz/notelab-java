package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.PermRoute;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** perm_routes 表 Mapper。 */
public interface PermRouteMapper extends BaseMapper<PermRoute> {

    /**
     * 路由注册 upsert（原 SQL 原样保留 + super_only / side 随启动回填）。
     *
     * <p>side 也进 UPDATE 列表：存量库里 {@code /api/c/**} 的行在加列时是默认值 'b'，
     * 靠每次启动的 upsert 纠正为 'c' —— 不必手工刷数据，也避免「刷过一次就再也不管」的漂移。
     */
    @Insert("INSERT INTO perm_routes (code,path,method,kind,name,super_only,side) "
            + "VALUES (#{code},#{path},#{method},#{kind},#{name},#{superOnly},#{side}) "
            + "ON DUPLICATE KEY UPDATE path=VALUES(path), method=VALUES(method), kind=VALUES(kind), "
            + "name=VALUES(name), super_only=VALUES(super_only), side=VALUES(side)")
    int upsertRoute(@Param("code") String code, @Param("path") String path, @Param("method") String method,
                    @Param("kind") String kind, @Param("name") String name,
                    @Param("superOnly") int superOnly, @Param("side") String side);

    /**
     * 按权限码前缀整类删除路由行（如旧格式的 {@code "api:/"}）。
     *
     * <p>⚠️ 这类删除**不能交给 pruneRoutes**：它按 {@code path} 判僵尸，而权限码口径迁移时
     * 新旧格式的 path 完全一样（变的只是 code），旧行会被判成「path 还活着」而保留下来 ——
     * 结果是树上同一个接口出现多条，且旧码仍然等价于「所有方法全放行」，把按方法授权整个架空。
     */
    @Delete("DELETE FROM perm_routes WHERE kind=#{kind} AND code LIKE CONCAT(#{prefix}, '%')")
    int deleteByCodePrefix(@Param("kind") String kind, @Param("prefix") String prefix);
}
