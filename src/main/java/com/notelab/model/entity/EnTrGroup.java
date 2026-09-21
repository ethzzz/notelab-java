package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * en_tr_groups 表（每日英语翻译练习 · 句子组）。
 * status：draft 草稿 / queued 已入队待激活 / used 已激活；
 * activated_date：被 0 点定时任务激活时写入当天（也可由 B 端手动指定强制发布）。
 */
@TableName("en_tr_groups")
public class EnTrGroup {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String title;
    private String status;
    private LocalDate activatedDate;
    private String source;
    private String scenario;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getActivatedDate() { return activatedDate; }
    public void setActivatedDate(LocalDate activatedDate) { this.activatedDate = activatedDate; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
