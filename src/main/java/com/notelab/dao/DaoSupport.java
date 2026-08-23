package com.notelab.dao;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 静态 DAO 门面的支撑件：Spring 构造器注入的依赖统一沉淀到静态字段，
 * 供 dao 包内各静态工具类（UserDao/ConversationDao/...）委托 MyBatis-Plus Mapper 使用。
 * 另暴露静态 tx()（TransactionTemplate）：静态方法无法使用 @Transactional，多语句事务用它包裹。
 */
@Component
public class DaoSupport {

    private static volatile TransactionTemplate tx;
    private static volatile boolean ready = false;

    public DaoSupport(PlatformTransactionManager txManager) {
        tx = new TransactionTemplate(txManager);
        ready = true;
    }

    /** 静态门面可用的事务模板（阶段3 用于多语句操作包裹） */
    public static TransactionTemplate tx() {
        return tx;
    }

    /** Spring 上下文是否已把依赖注入完毕（未就绪时静态门面应保持旧行为/安全跳过） */
    public static boolean ready() {
        return ready;
    }
}
