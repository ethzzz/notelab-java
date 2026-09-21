package com.notelab.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * en_tr_submissions 表（C 端用户逐句提交 + 大模型判分结果）。
 * 去重覆盖核心：uk_user_sentence_date (c_user_id, sentence_id, submit_date) 唯一键 + upsert。
 */
@TableName("en_tr_submissions")
public class EnTrSubmission {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer cUserId;
    private Long sentenceId;
    private Long groupId;
    private LocalDate submitDate;
    private String enText;
    /** 0/1（TINYINT），null 表示判分未完成 */
    private Integer accurate;
    private Integer score;
    private String corrected;
    private String explanation;
    /** 逐点错误标注 JSON 数组 */
    private String errorsJson;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getCUserId() { return cUserId; }
    public void setCUserId(Integer cUserId) { this.cUserId = cUserId; }

    public Long getSentenceId() { return sentenceId; }
    public void setSentenceId(Long sentenceId) { this.sentenceId = sentenceId; }

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    public LocalDate getSubmitDate() { return submitDate; }
    public void setSubmitDate(LocalDate submitDate) { this.submitDate = submitDate; }

    public String getEnText() { return enText; }
    public void setEnText(String enText) { this.enText = enText; }

    public Integer getAccurate() { return accurate; }
    public void setAccurate(Integer accurate) { this.accurate = accurate; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getCorrected() { return corrected; }
    public void setCorrected(String corrected) { this.corrected = corrected; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getErrorsJson() { return errorsJson; }
    public void setErrorsJson(String errorsJson) { this.errorsJson = errorsJson; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
