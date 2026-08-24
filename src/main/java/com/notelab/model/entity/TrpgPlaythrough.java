package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** trpg_playthroughs 表（TRPG 对局） */
@TableName("trpg_playthroughs")
public class TrpgPlaythrough {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long scenarioId;
    private Long userId;
    private String currentNode;
    private String state;
    private String endingTitle;
    private Integer steps;
    private String historyJson;
    /** B/C 拆分阶段2：数据归属 'b'=B端 / 'c'=C端（DB 默认 'b'，存量零迁移） */
    private String scope;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScenarioId() { return scenarioId; }
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCurrentNode() { return currentNode; }
    public void setCurrentNode(String currentNode) { this.currentNode = currentNode; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getEndingTitle() { return endingTitle; }
    public void setEndingTitle(String endingTitle) { this.endingTitle = endingTitle; }

    public Integer getSteps() { return steps; }
    public void setSteps(Integer steps) { this.steps = steps; }

    public String getHistoryJson() { return historyJson; }
    public void setHistoryJson(String historyJson) { this.historyJson = historyJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

}