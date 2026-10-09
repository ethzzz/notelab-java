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
    /**
     * 归属端：'b' = B 端后台（默认，含绝大多数接口与全部后台页面），'c' = C 端（{@code /api/c/**} 与 C 端页面）。
     *
     * <p>用途：B 端角色组只能持有 {@code side='b'} 的码，C 端用户组只能持有 {@code side='c'} 的码。
     * 两端各自一套身份体系，交叉授予没有意义（B 端角色码管不到 C 端用户，反之亦然）。
     */
    private String side;
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

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

}