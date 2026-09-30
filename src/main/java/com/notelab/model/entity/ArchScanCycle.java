package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** arch_scan_cycles 表（一次扫描里每个循环依赖环的各环成员，按环内顺序列） */
@TableName("arch_scan_cycles")
public class ArchScanCycle {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long runId;
    /** 环内序号（0 起，还原依赖环的走向） */
    private Integer memberSeq;
    /** 环成员节点 id，形如 notelab-java:common */
    private String member;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public Integer getMemberSeq() { return memberSeq; }
    public void setMemberSeq(Integer memberSeq) { this.memberSeq = memberSeq; }

    public String getMember() { return member; }
    public void setMember(String member) { this.member = member; }
}
