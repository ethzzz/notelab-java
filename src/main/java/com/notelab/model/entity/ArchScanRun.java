package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** arch_scan_runs 表（ArchGuard 一次扫描的汇总快照，趋势图的纵轴数据） */
@TableName("arch_scan_runs")
public class ArchScanRun {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    /** 依赖图节点数（模块/包数） */
    private Integer nodeCount;
    /** 依赖图边数（import 关系数） */
    private Integer edgeCount;
    /** 循环依赖环数（Tarjan 强连通分量） */
    private Integer cycleCount;
    /** 规则违规总数 */
    private Integer violationCount;
    /** 其中 error 级违规数（>0 即门禁失败） */
    private Integer errorCount;
    /** warn 级违规数 */
    private Integer warnCount;
    /** 扫描耗时（毫秒） */
    private Integer buildMs;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getNodeCount() { return nodeCount; }
    public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }

    public Integer getEdgeCount() { return edgeCount; }
    public void setEdgeCount(Integer edgeCount) { this.edgeCount = edgeCount; }

    public Integer getCycleCount() { return cycleCount; }
    public void setCycleCount(Integer cycleCount) { this.cycleCount = cycleCount; }

    public Integer getViolationCount() { return violationCount; }
    public void setViolationCount(Integer violationCount) { this.violationCount = violationCount; }

    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }

    public Integer getWarnCount() { return warnCount; }
    public void setWarnCount(Integer warnCount) { this.warnCount = warnCount; }

    public Integer getBuildMs() { return buildMs; }
    public void setBuildMs(Integer buildMs) { this.buildMs = buildMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
