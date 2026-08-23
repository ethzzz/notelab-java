package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** perm_roles 表（角色组；VARCHAR 主键） */
@TableName("perm_roles")
public class PermRole {

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