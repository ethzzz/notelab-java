package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** perm_routes 表（路由权限码；VARCHAR 主键） */
@TableName("perm_routes")
public class PermRoute {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;
    private String path;
    private String method;
    private String kind;
    private String name;
    private Integer builtin;
    /** 1 = 仅超级管理员可访问（受限前缀）；启动时按 {@code PermGuard.isRestricted} 回填，不手工维护 */
    private Integer superOnly;
    private LocalDateTime createdAt;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getBuiltin() { return builtin; }
    public void setBuiltin(Integer builtin) { this.builtin = builtin; }

    public Integer getSuperOnly() { return superOnly; }
    public void setSuperOnly(Integer superOnly) { this.superOnly = superOnly; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

}