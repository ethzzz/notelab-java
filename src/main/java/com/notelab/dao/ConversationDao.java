package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.Conversation;
import com.notelab.model.entity.Message;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Map;

/** 会话域 DAO：conversations + messages 表访问（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class ConversationDao {

    private ConversationDao() {}

    /** 原列表 SQL 投影列序 */
    private static final String[] LIST_COLS = {"id", "title", "model", "created_at", "updated_at"};
    /** 原 SELECT * 列序（表列序） */
    private static final String[] ALL_COLS = {"id", "user_id", "title", "model", "created_at", "updated_at"};
    /** 原消息 SQL 投影列序 */
    private static final String[] MSG_COLS = {"role", "content", "created_at"};

    public static List<Map<String, Object>> listConversations(long userId) {
        return RowUtil.rows(DaoSupport.conversation().selectList(
                Wrappers.lambdaQuery(Conversation.class)
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getUpdatedAt)), LIST_COLS);
    }

    public static long createConversation(long userId, String title, String model) {
        Conversation c = new Conversation();
        c.setUserId(userId);
        c.setTitle(title);
        c.setModel(model);
        DaoSupport.conversation().insert(c);
        return c.getId() == null ? -1 : c.getId();
    }

    public static Map<String, Object> getConversation(long cid, long userId) {
        return RowUtil.row(DaoSupport.conversation().selectOne(
                Wrappers.lambdaQuery(Conversation.class)
                        .eq(Conversation::getId, cid)
                        .eq(Conversation::getUserId, userId)), ALL_COLS);
    }

    /** 与 db.py 一致：仅删会话行，消息由外键 ON DELETE CASCADE 级联删除。 */
    public static void deleteConversation(long cid, long userId) {
        DaoSupport.conversation().delete(
                Wrappers.lambdaQuery(Conversation.class)
                        .eq(Conversation::getId, cid)
                        .eq(Conversation::getUserId, userId));
    }

    public static void setConversationTitle(long cid, String title) {
        DaoSupport.conversation().update(Wrappers.lambdaUpdate(Conversation.class)
                .eq(Conversation::getId, cid)
                .set(Conversation::getTitle, title));
    }

    public static void setConversationModel(long cid, String model) {
        DaoSupport.conversation().update(Wrappers.lambdaUpdate(Conversation.class)
                .eq(Conversation::getId, cid)
                .set(Conversation::getModel, model));
    }

    /** updated_at=CURRENT_TIMESTAMP（原 SQL 由 Mapper 注解保留，不用实体值覆盖） */
    public static void touchConversation(long cid) {
        DaoSupport.conversation().touch(cid);
    }

    public static List<Map<String, Object>> listMessages(long cid) {
        return RowUtil.rows(DaoSupport.message().selectList(
                Wrappers.lambdaQuery(Message.class)
                        .eq(Message::getConversationId, cid)
                        .orderByAsc(Message::getId)), MSG_COLS);
    }

    public static void addMessage(long cid, String role, String content) {
        Message m = new Message();
        m.setConversationId(cid);
        m.setRole(role);
        m.setContent(content);
        DaoSupport.message().insert(m);
    }

    public static long countMessages(long cid) {
        try {
            return DaoSupport.message().selectCount(
                    Wrappers.lambdaQuery(Message.class).eq(Message::getConversationId, cid));
        } catch (DataAccessException e) {
            return 0;
        }
    }
}
