package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.EnglishConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** english_conversations 表 Mapper。 */
public interface EnglishConversationMapper extends BaseMapper<EnglishConversation> {

    /** updated_at 由数据库 CURRENT_TIMESTAMP 生成（与原 SQL 语义一致，不能用实体值覆盖） */
    @Update("UPDATE english_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=#{cid}")
    int enTouch(@Param("cid") long cid);
}
