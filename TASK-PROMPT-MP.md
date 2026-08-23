# 任务：notelab-java 持久层从手写 JDBC 迁移到 MyBatis-Plus

你在服务器 /root/notelab-java（Spring Boot 3.4.5 / Java 17，pm2 进程 notelab-java，:8001）。
本任务是一次**行为零变更**的持久层重构：所有对外 API 的请求/响应契约必须逐字节保持现状。

## 硬性纪律（违反即失败）

1. 共享 MySQL（notelab 库）与 Python 版 /root/notelab 共用：**禁止任何 DDL 变更**，建表层 dao/DbSchema.java 原样保留、仍由启动流程执行；MyBatis-Plus 不得开启任何自动建表。
2. **不要动** /root/notelab（Python 版）、pm2 配置、前端 /root/myapp。
3. 全程单连接池：不允许出现第二个 HikariCP 池（与 Python 侧共享 MySQL，池参数维持 max=10 / minIdle=1 / connectionTimeout=5000）。
4. SSE 接口（/api/chat、/api/arena 等）的行为与代码不得被本次重构影响出任何语义变化。
5. 每个阶段完成后：`mvn -f /root/notelab-java/pom.xml -DskipTests -q package` → `pm2 restart notelab-java` → 验证（见文末验证节）→ 通过后再进入下一阶段；每阶段单独 `git add -A && git commit`（中文提交信息，格式参照现有 `git log`）。
6. 开工前先 `git status` 确认工作区干净，并打快照提交（若工作区有未提交改动，先单独提交并备注 "pre-mp-migration snapshot"）。
7. 若依赖无法解析（服务器拉不到 mybatis-plus 构件），立即停止并在最终输出中报告，不要降级用其它方案。

## 现状事实（已盘点确认，可直接采信）

- 持久层全部封闭在 `src/main/java/com/notelab/dao/`：
  - `Db.java`：手工 new HikariDataSource（非 Spring 托管），配置来自 `common/AppConfig`（环境变量 → ./.env → /root/notelab/.env 三级回退）；提供静态 `conn()/exec/queryOne/queryAll`；`row()` 把 ResultSet 转成**小写列名**的 LinkedHashMap，且 LocalDateTime 格式化为 `yyyy-MM-dd HH:mm:ss`（对齐 Python `str(datetime)`，是前端契约）。
  - `DbSchema.java`：启动建表/补列/种子（只增不改），由 `Db.init()` 触发；**保留不动**。
  - 7 个静态工具类 DAO，共 68 条业务 SQL、13 张表：
    - UserDao（users，16 条）、TrpgDao（trpg_scenarios/trpg_playthroughs/trpg_gen_tasks，15 条）、PermDao（perm_routes/perm_roles/perm_role_routes，11 条）、ConversationDao（conversations/messages，10 条）、EnglishDao（english_conversations/english_messages，8 条）、ToolDao（tools，6 条）、UiConfigDao（ui_config，2 条）。
- 全项目无事务（无 @Transactional / setAutoCommit）。
- 上游调用方（controller/service/Bootstrap）全部通过 DAO 静态方法访问，消费 `Map<String,Object>` 行数据（如 AuthController 直接 `u.get("password_hash")`）。
- 项目**没有 Lombok**；JSON 走 Spring MVC 默认 Jackson 与 common/JsonUtil。
- `Db.UniqueViolation`（SQLState 23000）是 UserDao 建号重复的对外异常契约，上层依赖。

## 迁移总策略（关键决策，照此执行）

**静态门面 + Map 出口**：7 个 DAO 保持现有静态方法签名与返回类型（Map/List<Map>/long/void）完全不变 → 上游调用方**零改动**，JSON 契约零风险。DAO 方法体内部改为委托 MyBatis-Plus Mapper。实体类仅作内部模型，不直接参与 API 序列化。

### 阶段 0：基座（本阶段结束后行为必须与现状完全一致）

1. `pom.xml` 增加（保留 spring-boot-starter-jdbc）：
   ```xml
   <dependency>
     <groupId>com.baomidou</groupId>
     <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
     <version>3.5.9</version>
   </dependency>
   ```
   先执行一次 `mvn -f /root/notelab-java/pom.xml -DskipTests -q package` 确认构件可解析。
2. 新增 `common/DataSourceConfig.java`：@Configuration，用 `AppConfig.mysqlHost()/mysqlPort()/mysqlDb()/mysqlUser()/mysqlPassword()` 构建 **HikariDataSource @Bean**（poolName=notelab-mysql，jdbcUrl 参数串与现状 Db.init() 完全相同，max=10/minIdle=1/connectionTimeout=5000）。
3. 改造 `Db.java`：`init()` 改为 `init(javax.sql.DataSource ds)` —— 直接持有该 Bean（不再自建池），随后仍调用 `DbSchema.initSchema()`；`exec/queryOne/queryAll/conn` 保留（DbSchema 仍用）。
4. `Bootstrap.java`：`@Autowired DataSource`，`run()` 中改调 `Db.init(dataSource)`，其余不动（启动日志原样）。
5. 新增 `common/MybatisPlusConfig.java`：@Configuration + @MapperScan("com.notelab.mapper") + MybatisPlusInterceptor（PaginationInnerInterceptor(DbType.MYSQL)）。
6. 新增 `dao/DaoSupport.java`：@Component，构造器注入全部 Mapper 与 `PlatformTransactionManager`，在构造器里把它们赋给**静态字段**（供静态 DAO 门面使用），另暴露静态 `TransactionTemplate tx()`。
7. 构建 + 重启 + 冒烟（验证节 A），git 提交「阶段0：MP 基座接入（DataSource 收编 + MapperScan），行为无变化」。

### 阶段 1：实体（13 表）+ Mapper 骨架

1. 新建 `model/entity/` 包：User、Conversation、Message、EnglishConversation、EnglishMessage、PermRoute、PermRole、PermRoleRoute、Tool、TrpgScenario、TrpgPlaythrough、TrpgGenTask、UiConfig。
   - 纯 POJO（手写 getter/setter），字段驼峰命名，依赖 MP 默认下划线转驼峰。
   - 主键：自增表 `@TableId(type=IdType.AUTO)`；perm_routes / perm_roles 为 VARCHAR 主键 `@TableId(type=IdType.INPUT)`；perm_role_routes 复合主键只走注解 SQL，不依赖 BaseMapper 内置 CRUD。
   - LocalDateTime 类型承载 created_at/updated_at。
2. 新建 `mapper/` 包：对应 BaseMapper<Entity>；特殊 SQL 用 @Select/@Insert/@Update 注解（见阶段 2 清单）。
3. 本阶段实体与 Mapper 尚无人调用，构建+重启+冒烟（验证节 A）后提交「阶段1：实体与 Mapper 骨架」。

### 阶段 2：逐域迁移 DAO 门面（核心阶段）

按顺序迁移：ToolDao → UiConfigDao → EnglishDao → ConversationDao → UserDao → TrpgDao → PermDao。
每迁完一个 DAO 立即构建+重启+该域冒烟（验证节 B 对应域），全部通过后统一提交「阶段2：DAO 全量迁移至 MyBatis-Plus」。

**行数据转换规则（契约核心）**：新增 `common/RowUtil.java`：
- `Map<String,Object> row(Object entity)` / `List<Map<String,Object>> rows(List<?>)`：反射或显式映射把实体转成 **snake_case 小写 key** 的 LinkedHashMap，LocalDateTime/Timestamp 一律格式化为 `yyyy-MM-dd HH:mm:ss`，null 值保留（与 Db.row() 对齐）。
- 注意 `listUsersPaged/listUsersForPerm` 原 SQL 只投影 `id,username,email,role,created_at`——**不得**把 password_hash 混入这些出口（用 `selectMaps` + Wrapper.select 指定列，保持输出 key 集合与现状逐一致）。
- `SELECT *` 系方法（getUserByUsername/getUserByEmail/getUserById/getConversation/enGetConversation/getTrpgScenario/getTrpgGenTask/getTool）转换完整实体（含 password_hash，与现状一致）。

**各方法迁移对照**：
- 简单 CRUD（约 85%）：BaseMapper 内置方法 + LambdaQueryWrapper/LambdaUpdateWrapper；insert 后返回 `entity.getId()`（对齐原 getGeneratedKeys 语义）。
- 保留原 SQL 的注解方法（共 8 处）：
  - PermDao.upsertRoute（ON DUPLICATE KEY UPDATE）、UiConfigDao.saveUiConfig（ON DUPLICATE KEY UPDATE）→ @Insert 原样 SQL。
  - PermDao 的 `INSERT IGNORE` 批量（setRoleRoutes 的插入部分）→ @Insert `<script>` foreach 或循环单插（保持 INSERT IGNORE 语义）。
  - TrpgDao.getTrpgPlay / listTrpgPlays（双表 JOIN 别名列）→ @Select 原样 SQL，返回 Map（MyBatis map 结果 key 即列别名，确认小写一致）。
  - UserDao.promoteFirstUserToAdmin（子查询）→ @Update 原样 SQL。
  - UserDao.listUsersPaged / countUsersFiltered（动态条件）→ QueryWrapper 条件拼接 + MP Page（size=limit, 换算 offset）或 selectMaps；输出 key 集合必须与原 SQL 一致。
- 异常契约：所有 insert 路径捕获 `DuplicateKeyException`（org.springframework.dao），转抛 `Db.UniqueViolation`（可为其新增一个接受 RuntimeException 的构造器），保持上层"用户名已存在"行为。
- enTouch/touchConversation 的 `updated_at=CURRENT_TIMESTAMP` 用 @Update 注解保留原 SQL（不要用实体值覆盖，语义不同）。

### 阶段 3：事务补齐（净收益修复）

用 `DaoSupport.tx()`（TransactionTemplate）给以下 3 个多语句操作加事务包裹（静态方法不能用 @Transactional）：
- PermDao.setRoleRoutes（DELETE + 批量 INSERT）
- PermDao.deleteRole（两条 DELETE）
- TrpgDao.deleteTrpgScenario（两条 DELETE）
事务管理器使用 Boot 自动配置的 DataSourceTransactionManager（阶段 0 收编后天然可用），不新增配置。构建+重启+冒烟后提交「阶段3：多语句操作补齐事务」。

### 阶段 4：清理与收尾

1. 确认无任何代码再引用 `Db.exec/queryOne/queryAll/conn` 后**保留**它们（DbSchema 仍在用）；删除 DAO 内遗留的无用 import。
2. 全量回归（验证节 C）。
3. git 提交「阶段4：MyBatis-Plus 迁移收尾」。
4. 最终输出一份简明报告：各阶段提交 hash、回归结果、遗留事项。

## 验证

**生成会话 Cookie**（多数接口需登录，Cookie 名 `notelab_session`）：阅读 `common/Session.java` 的签发逻辑，写一个一次性脚本为超级管理员账户（users 表 role='super_admin'，如 tester1）生成合法 Cookie。不要修改任何生产代码来绕过认证。

- **A. 冒烟**：启动日志正常（`pm2 logs notelab-java --lines 50`，确认建表/路由注册/监听 8001 无异常）；`curl -s http://127.0.0.1:8001/api/menu` 带 Cookie 返回 200 且结构正常；不带 Cookie 返回未登录错误。
- **B. 域级验证**（每域迁移后执行对应项，重点比对迁移前后响应逐字段一致；可先在旧实现上抓取基线 JSON 存 /tmp，再比对）：
  - tools：GET /api/tools 列表 → POST 新建 → PUT 改 → 启停 → DELETE，全链路。
  - ui-config：GET /api/ui-config → POST 保存 → GET 复核一致。
  - english：会话列表/建会话/改名/删会话（带 Cookie）。
  - conversations：同上（对话列表接口）。
  - users：/api/perm/users 分页 + 关键字/角色筛选 + total；建号重复须仍返回原错误语义。
  - trpg：剧本列表、对局列表（JOIN 别名字段 scenario_title/scenario_genre 必须在）、生成任务查询接口。
  - perm：角色列表（含 route_codes）、路由列表、建角色/改名/删角色、角色路由分配。
- **C. 全量回归**：B 全部 + 登录接口（错误密码 401）+ 事务路径抽查（角色路由分配后刷新一致）+ `pm2 logs` 无 ERROR/WARN 异常。
- 若任一验证失败：`git revert` 到上一个通过阶段的提交 → 重新构建重启恢复服务 → 修复后重试；绝不允许带着失败状态结束任务。

## 完成标准

- 7 个 DAO 静态签名零变化、上游调用方零改动、API 响应契约逐字段一致；
- 全项目只剩 DbSchema 使用 Db 的 JDBC 工具；
- 4 个阶段提交齐全，最终报告清晰。
