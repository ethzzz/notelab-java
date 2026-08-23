package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** perm_role_routes 表（角色-路由多对多；复合主键 (role_code,route_code)，只走注解 SQL） */
@TableName("perm_role_routes")
public class PermRoleRoute {

    private String roleCode;
    private String routeCode;
    private LocalDateTime createdAt;

    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRouteCode() { return routeCode; }
    public void setRouteCode(String routeCode) { this.routeCode = routeCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

}
