package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** en_tr_sentences 表（翻译练习句子）：tier 1=简单 / 2=中等 / 3=困难，ref_en 为可选参考译文。 */
@TableName("en_tr_sentences")
public class EnTrSentence {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private Integer tier;
    private Integer sortOrder;
    private String zhText;
    private String refEn;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    public Integer getTier() { return tier; }
    public void setTier(Integer tier) { this.tier = tier; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public String getZhText() { return zhText; }
    public void setZhText(String zhText) { this.zhText = zhText; }

    public String getRefEn() { return refEn; }
    public void setRefEn(String refEn) { this.refEn = refEn; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
