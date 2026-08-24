package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** c_user_groups 表（C 端用户组；VARCHAR 主键，风格对齐 perm_roles） */
@TableName("c_user_groups")
public class CUserGroup {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;
    private String name;
    private LocalDateTime createdAt;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
