package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * canvas_doc 表（B 端协作画布）。
 *
 * <p>只存**元数据**（房间号 / 标题 / 引擎 / 创建人 / 时间）：画布内容（tldraw 文档快照 或
 * Excalidraw 场景）由协作服务（notelab-b/collab）的 SQLite 按 room_id 持有，两边靠 room_id
 * 关联、各管一半。
 */
@TableName("canvas_doc")
public class CanvasDoc {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String roomId;
    private String title;
    /** 渲染引擎：tldraw | excalidraw。画布级属性，建好后不可改（要换就新建画布） */
    private String engine;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
