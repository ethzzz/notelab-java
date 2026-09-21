package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.EnTrSubmission;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** en_tr_submissions 表 Mapper（C 端逐句提交 + 判分结果）。 */
public interface EnTrSubmissionMapper extends BaseMapper<EnTrSubmission> {

    /**
     * 去重覆盖核心：靠 uk_user_sentence_date (c_user_id, sentence_id, submit_date) 唯一键，
     * 同一 (用户 + 日期 + 句子) 重复提交时覆盖旧判分结果，不新增行。
     */
    @Insert("""
            INSERT INTO en_tr_submissions
                (c_user_id, sentence_id, group_id, submit_date, en_text, accurate, score,
                 corrected, explanation, errors_json, model)
            VALUES (#{cUserId}, #{sentenceId}, #{groupId}, #{submitDate}, #{enText}, #{accurate}, #{score},
                    #{corrected}, #{explanation}, #{errorsJson}, #{model})
            ON DUPLICATE KEY UPDATE
                group_id=VALUES(group_id),
                en_text=VALUES(en_text),
                accurate=VALUES(accurate),
                score=VALUES(score),
                corrected=VALUES(corrected),
                explanation=VALUES(explanation),
                errors_json=VALUES(errors_json),
                model=VALUES(model),
                updated_at=CURRENT_TIMESTAMP""")
    int upsert(@Param("cUserId") long cUserId, @Param("sentenceId") long sentenceId,
               @Param("groupId") long groupId, @Param("submitDate") LocalDate submitDate,
               @Param("enText") String enText, @Param("accurate") Integer accurate, @Param("score") Integer score,
               @Param("corrected") String corrected, @Param("explanation") String explanation,
               @Param("errorsJson") String errorsJson, @Param("model") String model);

    /** 某用户某天的提交（JOIN 句子/组，供 C 端回填与历史查询；句子被删时 zh_text 为 null） */
    @Select("""
            SELECT s.id, s.sentence_id, s.group_id, s.submit_date, s.en_text, s.accurate, s.score,
                   s.corrected, s.explanation, s.errors_json, s.model, s.created_at, s.updated_at,
                   t.tier, t.sort_order, t.zh_text, t.ref_en, g.title AS group_title
            FROM en_tr_submissions s
            LEFT JOIN en_tr_sentences t ON t.id = s.sentence_id
            LEFT JOIN en_tr_groups g ON g.id = s.group_id
            WHERE s.c_user_id = #{cUserId} AND s.submit_date = #{submitDate}
            ORDER BY t.tier, t.sort_order, s.id""")
    List<Map<String, Object>> selectByUserDate(@Param("cUserId") long cUserId, @Param("submitDate") LocalDate submitDate);
}
