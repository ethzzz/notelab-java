package com.notelab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notelab.model.entity.Conversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** conversations 表 Mapper。 */
public interface ConversationMapper extends BaseMapper<Conversation> {

    /** updated_at 由数据库 CURRENT_TIMESTAMP 生成（与原 SQL 语义一致，不能用实体值覆盖） */
    @Update("UPDATE conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=#{cid}")
    int touch(@Param("cid") long cid);
}
