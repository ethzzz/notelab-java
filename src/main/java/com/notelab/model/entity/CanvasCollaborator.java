package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * canvas_collaborator 表（画布级 ACL 的授权行）。
 *
 * <p><b>创建者不落本表</b> —— 它是 {@code canvas_doc.created_by} 隐含的 owner，
 * 本表只存「被邀请进来的协作者」。超管同理不落表（视为全权 owner）。
 * 这样「创建者一定是 owner」不需要任何同步逻辑，改不掉也删不掉。
 */
@TableName("canvas_collaborator")
public class CanvasCollaborator {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String roomId;
    private Long userId;
    /** view | edit（白名单由 CanvasController 收敛，库里不做 CHECK） */
    private String permission;
    /** 邀请人（= 画布创建者）。留痕用，不参与鉴权 */
    private Long invitedBy;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }

    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long invitedBy) { this.invitedBy = invitedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
