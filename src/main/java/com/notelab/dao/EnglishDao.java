package com.notelab.dao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.notelab.common.RowUtil;
import com.notelab.model.entity.EnglishConversation;
import com.notelab.model.entity.EnglishMessage;

import java.util.List;
import java.util.Map;

/** 英语学习域 DAO：english_conversations + english_messages 表访问（静态签名不变，内部委托 MyBatis-Plus）。 */
public final class EnglishDao {

    private EnglishDao() {}

    /** 原列表 SQL 投影列序 */
    private static final String[] LIST_COLS = {"id", "title", "scenario", "created_at", "updated_at"};
    /** 原 SELECT * 列序（表列序） */
    private static final String[] ALL_COLS = {"id", "user_id", "title", "scenario", "created_at", "updated_at"};
    /** 原消息 SQL 投影列序 */
    private static final String[] MSG_COLS = {"role", "content", "correction", "error_note", "created_at"};

    public static List<Map<String, Object>> enListConversations(long userId) {
        return RowUtil.rows(DaoSupport.englishConversation().selectList(
                Wrappers.lambdaQuery(EnglishConversation.class)
                        .eq(EnglishConversation::getUserId, userId)
                        .orderByDesc(EnglishConversation::getUpdatedAt)), LIST_COLS);
    }

    public static long enCreateConversation(long userId, String title, String scenario) {
        EnglishConversation c = new EnglishConversation();
        c.setUserId(userId);
        c.setTitle(title);
        c.setScenario(scenario);
        DaoSupport.englishConversation().insert(c);
        return c.getId() == null ? -1 : c.getId();
    }

    public static Map<String, Object> enGetConversation(long cid, long userId) {
        return RowUtil.row(DaoSupport.englishConversation().selectOne(
                Wrappers.lambdaQuery(EnglishConversation.class)
                        .eq(EnglishConversation::getId, cid)
                        .eq(EnglishConversation::getUserId, userId)), ALL_COLS);
    }

    /** 与 db.py 一致：仅删会话行，消息由外键级联删除。 */
    public static void enDeleteConversation(long cid, long userId) {
        DaoSupport.englishConversation().delete(
                Wrappers.lambdaQuery(EnglishConversation.class)
                        .eq(EnglishConversation::getId, cid)
                        .eq(EnglishConversation::getUserId, userId));
    }

    public static void enSetTitle(long cid, String title) {
        DaoSupport.englishConversation().update(Wrappers.lambdaUpdate(EnglishConversation.class)
                .eq(EnglishConversation::getId, cid)
                .set(EnglishConversation::getTitle, title));
    }

    /** updated_at=CURRENT_TIMESTAMP（原 SQL 由 Mapper 注解保留，不用实体值覆盖） */
    public static void enTouch(long cid) {
        DaoSupport.englishConversation().enTouch(cid);
    }

    public static List<Map<String, Object>> enListMessages(long cid) {
        return RowUtil.rows(DaoSupport.englishMessage().selectList(
                Wrappers.lambdaQuery(EnglishMessage.class)
                        .eq(EnglishMessage::getConversationId, cid)
                        .orderByAsc(EnglishMessage::getId)), MSG_COLS);
    }

    public static void enAddMessage(long cid, String role, String content, String correction, String errorNote) {
        EnglishMessage m = new EnglishMessage();
        m.setConversationId(cid);
        m.setRole(role);
        m.setContent(content);
        m.setCorrection(correction);
        m.setErrorNote(errorNote);
        DaoSupport.englishMessage().insert(m);
    }
}
