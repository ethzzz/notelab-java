package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** arch_scan_violations 表（一次扫描里命中的每个分层规则违规） */
@TableName("arch_scan_violations")
public class ArchScanViolation {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long runId;
    /** 规则 id，如 no-controller-to-dao */
    private String ruleId;
    /** error / warn */
    private String level;
    /** 依赖起点模块 */
    private String fromModule;
    /** 依赖终点模块 */
    private String toModule;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getFromModule() { return fromModule; }
    public void setFromModule(String fromModule) { this.fromModule = fromModule; }

    public String getToModule() { return toModule; }
    public void setToModule(String toModule) { this.toModule = toModule; }
}
