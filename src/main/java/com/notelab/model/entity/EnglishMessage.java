package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** english_messages 表（英语消息，含纠错） */
@TableName("english_messages")
public class EnglishMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private String correction;
    private String errorNote;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCorrection() { return correction; }
    public void setCorrection(String correction) { this.correction = correction; }

    public String getErrorNote() { return errorNote; }
    public void setErrorNote(String errorNote) { this.errorNote = errorNote; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

}