package com.notelab.dao;

import com.notelab.mapper.ConversationMapper;
import com.notelab.mapper.EnglishConversationMapper;
import com.notelab.mapper.EnglishMessageMapper;
import com.notelab.mapper.MessageMapper;
import com.notelab.mapper.PermRoleMapper;
import com.notelab.mapper.PermRoleRouteMapper;
import com.notelab.mapper.PermRouteMapper;
import com.notelab.mapper.ToolMapper;
import com.notelab.mapper.TrpgGenTaskMapper;
import com.notelab.mapper.TrpgPlaythroughMapper;
import com.notelab.mapper.TrpgScenarioMapper;
import com.notelab.mapper.UiConfigMapper;
import com.notelab.mapper.UserMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 静态 DAO 门面的支撑件：Spring 构造器注入的 Mapper/事务模板统一沉淀到静态字段，
 * 供 dao 包内各静态工具类（UserDao/ConversationDao/...）委托 MyBatis-Plus 使用。
 * 静态方法无法使用 @Transactional，多语句事务用 tx()（TransactionTemplate）包裹。
 */
@Component
public class DaoSupport {

    private static volatile boolean ready = false;
    private static TransactionTemplate tx;

    private static UserMapper userMapper;
    private static ConversationMapper conversationMapper;
    private static MessageMapper messageMapper;
    private static EnglishConversationMapper englishConversationMapper;
    private static EnglishMessageMapper englishMessageMapper;
    private static PermRouteMapper permRouteMapper;
    private static PermRoleMapper permRoleMapper;
    private static PermRoleRouteMapper permRoleRouteMapper;
    private static ToolMapper toolMapper;
    private static TrpgScenarioMapper trpgScenarioMapper;
    private static TrpgPlaythroughMapper trpgPlaythroughMapper;
    private static TrpgGenTaskMapper trpgGenTaskMapper;
    private static UiConfigMapper uiConfigMapper;

    public DaoSupport(PlatformTransactionManager txManager,
                      UserMapper userMapper,
                      ConversationMapper conversationMapper,
                      MessageMapper messageMapper,
                      EnglishConversationMapper englishConversationMapper,
                      EnglishMessageMapper englishMessageMapper,
                      PermRouteMapper permRouteMapper,
                      PermRoleMapper permRoleMapper,
                      PermRoleRouteMapper permRoleRouteMapper,
                      ToolMapper toolMapper,
                      TrpgScenarioMapper trpgScenarioMapper,
                      TrpgPlaythroughMapper trpgPlaythroughMapper,
                      TrpgGenTaskMapper trpgGenTaskMapper,
                      UiConfigMapper uiConfigMapper) {
        DaoSupport.tx = new TransactionTemplate(txManager);
        DaoSupport.userMapper = userMapper;
        DaoSupport.conversationMapper = conversationMapper;
        DaoSupport.messageMapper = messageMapper;
        DaoSupport.englishConversationMapper = englishConversationMapper;
        DaoSupport.englishMessageMapper = englishMessageMapper;
        DaoSupport.permRouteMapper = permRouteMapper;
        DaoSupport.permRoleMapper = permRoleMapper;
        DaoSupport.permRoleRouteMapper = permRoleRouteMapper;
        DaoSupport.toolMapper = toolMapper;
        DaoSupport.trpgScenarioMapper = trpgScenarioMapper;
        DaoSupport.trpgPlaythroughMapper = trpgPlaythroughMapper;
        DaoSupport.trpgGenTaskMapper = trpgGenTaskMapper;
        DaoSupport.uiConfigMapper = uiConfigMapper;
        DaoSupport.ready = true;
    }

    /** 静态门面可用的事务模板（多语句操作包裹用） */
    public static TransactionTemplate tx() {
        return tx;
    }

    /** Spring 上下文是否已把依赖注入完毕 */
    public static boolean ready() {
        return ready;
    }

    public static UserMapper user() { return userMapper; }
    public static ConversationMapper conversation() { return conversationMapper; }
    public static MessageMapper message() { return messageMapper; }
    public static EnglishConversationMapper englishConversation() { return englishConversationMapper; }
    public static EnglishMessageMapper englishMessage() { return englishMessageMapper; }
    public static PermRouteMapper permRoute() { return permRouteMapper; }
    public static PermRoleMapper permRole() { return permRoleMapper; }
    public static PermRoleRouteMapper permRoleRoute() { return permRoleRouteMapper; }
    public static ToolMapper tool() { return toolMapper; }
    public static TrpgScenarioMapper trpgScenario() { return trpgScenarioMapper; }
    public static TrpgPlaythroughMapper trpgPlaythrough() { return trpgPlaythroughMapper; }
    public static TrpgGenTaskMapper trpgGenTask() { return trpgGenTaskMapper; }
    public static UiConfigMapper uiConfig() { return uiConfigMapper; }
}
