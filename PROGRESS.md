# PROGRESS

## 阶段1（2026-08-06）✅ 完成

### 完成内容
- Spring Boot 3.4.5 骨架：端口 8001，绑定 127.0.0.1（同 Python 版 uvicorn host）。
- 配置加载：`进程环境变量 → ./.env → /root/notelab/.env（只读）→ 默认值`；实测读到
  QWEN_BASE_URL=token-plan 网关、QWEN_MODEL=qwen3.8-max、QWEN_API_KEY、SECRET_KEY（与 Python 版同源，保证 Cookie 兼容）。
- SQLite 独立数据文件 `data/notelab-java.db`：users / conversations / messages / english_conversations /
  english_messages / ui_config 全表结构（后阶段接口直接使用）。
- 认证四接口：register（用户名/密码/邮箱校验、限流 5次10分钟、PBKDF2-HMAC-SHA256 120000 轮）、
  login（限流 10次5分钟）、logout、me；HMAC 会话 Cookie 与 Python 版算法完全一致。
- GET /api/menu（含 ui-config 覆盖逻辑 + 30s 缓存）、GET /api/models（600s 缓存 + image/audio/tts/wan2/vl 过滤）。
- 限流：与 Python 版共用 Redis（键 rl:*），Redis 不可用退回内存滑动窗口。
- 404/405/422 错误响应风格对齐 FastAPI（{"detail": ...}）。
- CORS：允许 `https?://.*:3000` 携带 Cookie。
- pm2 进程 `notelab-java` 已启动；README.md 已更新。

### 验证（构建 + pm2 + curl 对比 8000 vs 8001）
```bash
mvn -DskipTests package                      # BUILD SUCCESS（target/notelab-java.jar）
pm2 start "java -jar target/notelab-java.jar" --name notelab-java
bash /tmp/cmp_stage1.sh                      # 对比脚本（内容见下）
```
结果：
- 未登录 /api/me、/api/menu、/api/models：两端均 `{"error":"请先登录"} | 401` ✅
- register：两端均 `{"ok":true} | 200` 并下发 notelab_session Cookie ✅
- **Cookie 双向互认**：
  - Python 签发的 cookie 经 Java 侧 HMAC 验签通过（用 Python 算法为 Java 用户 id=1 生成 token，
    Java /api/me 返回 `{"id":1,"username":"crosstest..."} | 200`）✅
  - Java 签发的 cookie 打 Python /api/me 验签通过并解析出用户（uid=2 → Python 库用户）✅
- /api/me 键集合一致：["email","id","username"] ✅
- /api/menu 完全一致：10 个菜单项、首项 dashboard、background 配置相同 ✅
- /api/models 完全一致：16 个模型、顺序相同、首项 qwen3.6-plus、默认模型 qwen3.8-max 在 index 15 ✅
- login 成功/失败、logout：响应体一致；Set-Cookie 细节差异见 README「已知差异」 ✅
- register 校验：用户名/密码/邮箱/非法JSON 错误文案与状态码一致（400/400/400/422）✅
- 404：两端均 `{"detail":"Not Found"} | 404` ✅
- 限流联动：Python 用尽 `rl:register:127.0.0.1` 配额后 Java 同样返回 429（共用 Redis 生效）✅

### 下一步（阶段2）
- conversations 全套（list/create/messages/delete/set-model）
- POST /api/chat SSE 流式（历史入库、首条消息自动命名）
- POST /api/toolbox、POST /api/extract

## 阶段2（2026-08-06）✅ 完成

### 完成内容
- conversations 全套：GET/POST /api/conversations、GET /api/conversations/{cid}/messages、
  DELETE /api/conversations/{cid}、POST /api/conversations/{cid}/model（默认模型、404 语义与 Python 一致）。
- POST /api/chat：SSE 流式（`data: {"delta":...}` 帧 + 结束 `{"done":true}`），用户消息先入库、
  首条消息自动设为标题（前 30 字符）、助手回复完整入库、上游非 200/超时/异常分别发 error 事件，文案与 Python 一致。
- POST /api/toolbox：4 个动作（summarize/translate/rewrite/sentiment）提示词照抄 Python 版。
- POST /api/extract：结构化抽取，raw + result（首个 `{` 到末尾 `}` 的 JSON 解析）逻辑与 Python 一致。

### 验证（pm2 restart + /tmp/cmp_stage2.sh，8000 vs 8001）
- 创建会话：`{"id":N,"title":"新对话","model":"qwen3.8-max"}` 两端一致；默认模型创建一致 ✅
- 会话列表：键结构、条目字段、updated_at 格式（`2026-08-06 23:29:28`）一致 ✅
- 会话消息：空会话/404 语义一致 ✅
- chat SSE 第一轮：两端事件序列均为 `{"delta":...}* + {"done":true}` ✅
- 多轮历史：第二轮问「刚才记住的数字」，两端均正确回答 42（历史入库生效）✅
- 标题自动命名：两端标题均被设为首条消息前 30 字 ✅；消息数 4 条、角色交替一致 ✅
- chat 错误路径：空消息 400、会话不存在 404，文案一致 ✅
- set-model/delete：200/404 行为一致，删除后查消息 404 ✅
- toolbox：summarize 返回 `{action,name,result}` 结构一致；未知动作 400、空文本 400 文案一致 ✅
- extract：`{raw,result}` 结构一致，抽取结果完全一致（姓名/电话/城市）✅

### 下一步（阶段3）
- RAG：/api/rag/upload（JSON {name,content}，注意不是 multipart，以 main.py 为准）、/api/rag/docs、/api/rag/ask
- 英语：scenarios / conversations（创建时生成开场白）/ messages / delete / chat（JSON 语法纠错，非流式）

## 阶段3（2026-08-06）✅ 完成

### 完成内容
- RAG：POST /api/rag/upload（JSON {name,content}，文件名清洗规则与 Python 一致）、GET /api/rag/docs、
  POST /api/rag/ask（bigram 重叠打分 top3 + 引用编号提示词，照抄 Python）。文档持久化在 data/uploads/*.txt，
  启动时自动加载；与 Python 版 uploads 目录互相独立。
- 英语学习全套：GET /api/english/scenarios（8 场景常量照抄）、GET|POST /api/english/conversations
  （创建时调用模型生成开场白、非法场景回退 free）、GET .../messages（含 correction/error_note 字段）、
  DELETE、POST /api/english/chat（语法纠错 JSON 提示词照抄 Python；注意该接口在 Python 版即为非流式 JSON，
  任务简报中的「SSE」描述以 main.py 源码为准）。

### 验证（pm2 restart + /tmp/cmp_stage3.sh，8000 vs 8001）
- RAG upload：名称清洗 `我的 文档@v1 → 我的_文档_v1` 两端一致；长文分块数一致（2 块）；空内容 400 一致 ✅
- RAG docs：条目结构一致 ✅
- RAG ask：**citations 完全一致（同一片段、同分 0.438）**，证明 chunk/bigram/round 逻辑逐位对齐；
  两端回答都正确引用 [1]；空问题 400 一致 ✅
- English scenarios：diff 无任何差异 ✅
- English 创建会话：结构一致、开场白正常生成、非法 scenario 回退 free ✅
- English chat：两端均把 "I go ... yesterday and eat noodles" 纠错为 "I went ... and ate noodles"，
  error_note 为中文说明；响应键集合一致 ✅
- English messages/list/delete：字段结构、404、删除语义一致 ✅

### 下一步（阶段4）
- POST /api/arena（并行多模型 + 10s 心跳）
- GET|POST /api/ui-config（30s 缓存 + 失效逻辑）
- 全量回归所有接口

## 阶段4（2026-08-06）✅ 完成

### 完成内容
- POST /api/arena：并行调用最多 6 个模型（CachedThreadPool + 队列聚合），SSE 事件序列与 Python 一致：
  `{"started":true,"models":[...]}` → 每个模型完成即发 `{"model","content"}` 或 `{"model","error"}`（按完成顺序），
  期间每 10s 无结果发 `{"keepalive":true}` 心跳，最后 `{"done":true}`。
- GET /api/ui-config：`{config, defaults}`，config 走 30s 缓存（DB → 默认值兜底）。
- POST /api/ui-config：原始入参解析（非法 JSON → 400 {"error":"请求体不是合法 JSON"}，与 Python 一致），
  `config = body.config 或 body`，仅保留 background/menus 两键（缺省用默认值），入库后缓存失效。

### 验证（pm2 restart + /tmp/cmp_stage4.sh，8000 vs 8001）
- arena 双模型并行：两端事件序列完全一致（started → qwen-plus 404 error 事件（该网关无此模型，两端同样报错，
  文案 `HTTP 404: {"error":{"message":"Model not exist."...` 一致）→ qwen3.8-max content 事件 → done）✅
- arena 坏模型名：error 事件结构一致 ✅
- arena 校验：空消息 400、无模型 400 文案一致 ✅
- **arena 心跳实测**：单模型长任务（约 70s）期间每 10s 收到一个 `{"keepalive":true}`（共 7 个），
  随后 content + done ✅
- ui-config GET：键结构与 defaults 完全一致 ✅
- ui-config POST 往返：保存 `{"menus":{"chat":{"name":"对话Pro","icon":"🚀"}}}` 后 /api/menu 立即生效，
  background 颜色同步生效；还原默认成功 ✅
- ui-config 非法 JSON / config 非对象：两端均 400 且文案一致 ✅

## 全量回归（2026-08-06）✅ 通过

重跑阶段1~4 全部对比脚本（/tmp/cmp_stage1.sh ~ cmp_stage4.sh），所有接口 8000 vs 8001 行为一致：

| 模块 | 接口 | 结果 |
|---|---|---|
| 认证 | register/login/logout/me | ✅ 校验文案、状态码、Cookie 双向互认一致 |
| 菜单/模型 | menu / models | ✅ 结构、顺序、缓存行为一致 |
| 智能对话 | conversations* / chat SSE | ✅ 流式帧、历史、自动命名、404/400 一致 |
| 文本工具箱 | toolbox | ✅ 4 动作 + 错误路径一致 |
| RAG | upload/docs/ask | ✅ 名称清洗、分块数、检索打分（0.438）逐位一致 |
| 英语学习 | scenarios/conversations/messages/delete/chat | ✅ scenarios diff 为空、纠错结构一致 |
| 结构化抽取 | extract | ✅ raw/result 一致 |
| 模型竞技场 | arena | ✅ 事件序列 + 心跳一致 |
| 界面配置 | ui-config GET/POST | ✅ 往返 + 错误路径一致 |

期间还验证：共享 Redis 限流（Python 用尽配额后 Java 同样 429；本轮回归 Java 登录也被同一限流键拦下，
换 X-Forwarded-For 后通过——与 Python 行为完全一致）。

### 剩余工作（切流前，属后续步骤）
1. 数据迁移：Python MySQL 的 users/conversations/messages/english_*/ui_config → data/notelab-java.db
   （用户密码哈希与 id 需原样迁移，迁移后交叉登录态即可无缝衔接）。
2. 人工切流：/root/myapp/next.config.ts 的 rewrites 目标 8000 → 8001，重启 myapp。
3. 观察期后 pm2 stop notelab。

## 阶段5（2026-08-07）✅ 完成：数据层切换为 Python 版同一个 MySQL（两服务共享数据）

### 改动内容
- **切换前备份**：`mysqldump --single-transaction` 全库导出到 `data/backup-before-mysql-switch.sql`
  （6 表：users/conversations/messages/english_conversations/english_messages/ui_config，含全部数据；
  备份文件在 data/ 下，已被 .gitignore 排除，不入库）。
- **pom.xml**：移除 `org.xerial:sqlite-jdbc`，新增 `spring-boot-starter-jdbc`（HikariCP 5.1.0）+
  `com.mysql:mysql-connector-j`（9.1.0，版本由 Spring Boot 3.4.5 管理）。
- **Db.java 全面重写**（方法签名不变，控制器零改动）：
  - HikariCP 连接池 `notelab-mysql`，**maximumPoolSize=10**，连接串
    `jdbc:mysql://<MYSQL_HOST>:<MYSQL_PORT>/<MYSQL_DB>?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true`；
  - 配置来自 `MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD/MYSQL_DB`（AppConfig 新增读取，
    优先级：进程环境变量 → ./.env → /root/notelab/.env，默认值与 db.py 一致；密钥不硬编码、不提交）；
  - 建表语句逐条照抄 db.py（CREATE TABLE IF NOT EXISTS，MySQL 方言，InnoDB/utf8mb4），
    并按 db.py migrate_schema 补 users.email 列（仅缺列时 ALTER）与 ui_config 表——只增不改；
  - 方言适配：占位符 ?（JDBC 通用）、时间列交给 MySQL `DEFAULT CURRENT_TIMESTAMP`（与 db.py 一致）、
    `ON DUPLICATE KEY UPDATE`（ui_config upsert）、唯一键冲突按 SQLState 23000 映射 UniqueViolation；
  - 删除会话/英语会话改为与 db.py 一致的单条 DELETE（消息由外键 ON DELETE CASCADE 级联，
    顺带消除了 SQLite 版「兜底删消息」可能误删他人会话消息的隐患）；
  - DATETIME 读取统一格式化为 `yyyy-MM-dd HH:mm:ss`（对齐 Python `str(datetime)`），
    updated_at 等字段两端输出逐字节一致（已实测）。
- **NoteLabApplication**：`exclude = DataSourceAutoConfiguration`（连接池由 Db 手动管理，不走 Spring 装配）。
- **Bootstrap**：启动日志打印 MySQL 连接信息（不含密码）。
- **EnglishController**：scenarios 改用 LinkedHashMap 固定键序 id/name/en/desc，与 Python
  ENGLISH_SCENARIOS 字节级一致（此前 Map.of 迭代序导致 JSON 键序差异，回归中发现并修复）。
- SQLite 相关依赖与代码已全部移除；`data/notelab-java.db` 保留为历史产物，不再读写。

### 验收（逐条执行）
1. **Java 注册 → Python 登录**：Java 侧注册 `s5j2p1786106786`（users.id=12）→ Python /api/login 成功，
   /api/me 返回同一用户 ✅
2. **Java 建会话 → Python 可见**：Java 侧创建会话 id=7 → Python /api/conversations 列表可见 ✅
3. **Python 已有用户 → Java 登录读历史**：
   - Python 侧新注册 `s5p2j1786106786`（id=13）→ Java 登录成功 ✅（反向互认）
   - 切换前已存在的老用户 `crosstest1786029852`（id=8，密码哈希来自 Python 侧写入）在 Java 登录成功，
     并读到我切换前在 MySQL 中的历史会话 id=4,6 ✅
   - 消息级共享:Python 侧 chat 一轮（会话 id=8）→ Java 侧读取该会话得 2 条消息、角色交替正确 ✅
4. **全量接口回归**（/tmp/cmp_stage5_share.sh、cmp_stage5_full.sh、cmp_stage5_full2.sh，共享库适配版）：
   - 未登录 me/menu/models、404：两端一致 ✅
   - Cookie 双向互认（共享用户表，同一 uid=8 两端互打 /api/me 一致）✅
   - menu / models：字节级一致（16 模型、10 菜单项、默认背景）✅
   - login 成功/失败、logout、register 全部校验文案与状态码一致；**重复注册两侧均 409「用户名已存在」**
     （共享唯一约束生效）✅
   - conversations：**同一用户会话列表两端 id 序列完全相同 [9,10,6,4]**；create/404/set-model/
     跨端删除（Python 删 Java 建的会话）全部一致 ✅
   - chat SSE：两端事件序列一致，多轮历史均答 42，标题自动命名一致，空消息 400/会话不存在 404 一致 ✅
   - toolbox 4 动作 + 错误路径一致 ✅；extract raw/result 完全一致 ✅
   - RAG：upload 名称清洗、chunks 一致；ask citations 同文档同分（0.733）——两端 citations 条数不同仅因
     RAG 文档为各自 uploads 目录的文件态数据（非 DB，设计如此，阶段3 已说明）✅
   - 英语：scenarios 字节级一致（修复键序后复验）；create/chat 纠错/messages/404/delete 一致 ✅
   - arena：两端事件序列一致（started → qwen-plus 404 error（网关无此模型，报错文案一致）→
     qwen3.8-max content → done）；校验文案一致 ✅（10s 心跳已在阶段4 实测，数据层切换不影响，未重跑 70s 长任务）
   - ui-config：GET 结构一致；**Java 保存配置 → Python 侧 /api/menu 同步生效（对话Pro/🚀/#123456）**，
     证明 ui_config 表真共享 ✅；非法 JSON / config 非对象文案一致 ✅
5. **Python 服务不受影响**：pm2 status `notelab` online（自 08-06 00:08 起零重启，切换期间未重启）；
   /api/menu、/api/conversations（含共享数据）、404 行为正常 ✅

### 过程中发现并处理的问题
- **ui_config 测试残留**：B14 往返测试在共享表写入了一行 `menus:{}`，导致两端菜单 icon 回退为空串
  （build_menu_items 对空 menus 的行为，两端逻辑一致，但切换前该表为 0 行、菜单 icon 取默认值 💬）。
  已删除该行（仅回滚本次测试自己写入的数据，非既有生产数据），复验两端菜单恢复默认 icon，
  表恢复切换前状态（0 行）。今后对共享 ui_config 的写操作即生产操作，需谨慎。
- extract 缺 `fields` 字段：Python 返回 422 detail（Pydantic），Java 返回 400 {"error":"文本和目标字段都不能为空"}；
  属 README「已知差异」同类（缺字段时 FastAPI 422 vs Java 400），正常请求行为一致。

### 剩余风险
1. ui_config / 限流外的所有数据现为单点共享：任何一侧的写入即时影响另一侧（这是本阶段目标，但意味着
   误操作影响面变大）；备份文件 data/backup-before-mysql-switch.sql 可用于恢复。
2. 并发写同一行（如两端同时保存 ui_config）为后写覆盖，与 Python 单服务时的语义一致，可接受。
3. Java 侧连接池 10 + Python 侧 pymysql 短连接并存，当前负载余量充足；如后续上量需关注 MySQL
   max_connections 与慢查询。
4. arena 10s 心跳未在本阶段重跑长任务（阶段4 已实测；数据层不参与心跳路径）。

### 下一步（人工）
1. 切流：/root/myapp/next.config.ts rewrites 目标 8000 → 8001，重启 myapp（数据已共享，无需迁移）。
2. 观察期后 pm2 stop notelab。

## 2026-08-07 22:45 切流完成：前端流量 8000 → 8001
- /root/myapp/next.config.ts 的 rewrites 目标改为 8001，原文件备份为 /root/myapp/next.config.ts.bak-8000
- npm run build 成功（Next 16.2.12 Turbopack，13 个静态页），pm2 restart myapp
- 验证：经 3000 的 /api/models 与 /api/menu 响应头无 uvicorn server 头、响应体与直连 8001 逐字节一致、Python uvicorn access 日志行数前后不变、登录页 HTTP 200、公网 IP 访问正常 → 流量全部走 Java
- 副作用：pm2 watch 使 myapp-dev 随构建自动重启，3001 开发服也一并切到 8001
- 回滚方法：next.config.ts 改回 8000（或恢复备份文件）后 npm run build 并 pm2 restart myapp
- 下一步：观察期无异常后 pm2 stop notelab 退役 Python 版

## 2026-08-08 RBAC 权限体系上线（关注册 + users.role + 权限路由表 + 权限管理）
### 改动
- 数据库（改前已备份 data/backup-before-rbac.sql）：
  - users 表 ADD COLUMN role VARCHAR(20) DEFAULT 'user'（只增不改，Python 版不受影响）
  - 新表 perm_routes（权限路由表，code 主键）、perm_roles（super_admin/user）、perm_role_routes（角色↔路由组）
- 后端：PermService（启动时从 RequestMappingHandlerMapping 自动采集全部 API 路由 + 页面路由 upsert 入库=自动注册；首次启动无超管则提升最早用户）、PermController（overview/角色路由组/建号/角色赋予/重置密码，全部超管守卫）、MenuController 按角色过滤菜单、/api/register 改 403、/api/me 增加 role
- 前端：新增 /perm 权限管理页；登录页移除注册链接；/register 改为关闭提示页
### 验收（curl 实测）
1. POST /api/register → 403「注册入口已关闭」✅（直连 8001 与经 3000 代理均验证）
2. 启动日志 perm_routes=40（11 页面 + 29 API 自动注册）✅；tester1 自动提升 super_admin ✅
3. 超管登录 → /api/menu 全量 11 项含 /perm /ui ✅；/api/perm/overview 200 ✅；/api/me 含 role ✅
4. 普通用户登录 → 菜单仅 9 项（无 /ui /perm）✅；/api/perm/overview 403 ✅
5. 超管给普通角色路由组加 page:/ui → 普通用户菜单即时多出 /ui（无需重登）✅
6. 超管建号 POST /api/perm/users ✅（修复：空邮箱存 NULL，多账号不撞 UNIQUE）
7. 生产验证：/perm 页 200、/login 无注册入口、/register 显示关闭提示 ✅
- 临时测试账户已全部删除，普通角色路由组已恢复默认 9 项
### 备注
- Python 版 8000 的 register 仍在（内网不可达，前端流量只到 Java），退役 Python 时一并消失
- API 级权限码已登记备用，当前仅页面路由参与菜单过滤；后续可做接口级拦截
## 2026-08-24 B/C 拆分阶段1：C 端独立身份体系
### 前置
- 开工备份：`mysqldump notelab > data/bc-p1-dump.sql`（74KB）。
- 基线：perm_routes=65，pm2 notelab-java 在线。

### 改动清单（仅 /root/notelab-java 内）
- `dao/DbSchema.java`：migrateSchema 末尾新增 c_users / c_user_groups 建表（CREATE TABLE IF NOT EXISTS）+ `INSERT IGNORE` 默认组种子。
- `model/entity/CUser.java`、`model/entity/CUserGroup.java`（新）、`mapper/CUserMapper.java`、`mapper/CUserGroupMapper.java`（新，后者含注解 SQL：组列表+成员数）。
- `dao/DaoSupport.java`：注入 CUserMapper / CUserGroupMapper（构造器 + 静态 accessor）。
- `dao/CUserDao.java`（新）：静态门面，Map 出口（RowUtil 键序），用户分页/筛选/改密/删除 + 用户组 CRUD。
- `common/AppConfig.java`：新增常量 `SESSION_COOKIE_C = "notelab_c_session"`。
- `common/Session.java`：**原方法零改动**；新增 `makeCToken`（4 段 `c.<uid>.<exp>.<sig>` 的 base64url）、`parseCToken`（恰好 4 段且首段 `c` 才认）、`currentUserC`（查 c_users 且要求 status='active'）、`setCookieC/deleteCookieC`。
- `controller/CAuthUtil.java`（新）、`controller/CAuthController.java`（新，/api/c/auth：login/me/logout/register，register 受 `C_REGISTER_OPEN` 开关控制默认关闭）、`controller/CAdminController.java`（新，/api/c-admin：users + groups 全套，全部 B 端登录守卫）。

### 验证（127.0.0.1:8001 直连，全部实测）
1. ✅ `mvn -DskipTests package` 通过；`pm2 restart notelab-java` 后启动日志无 ERROR，perm_routes 65→74（+9：c-auth 4 条 + c-admin 5 条）。
2. ✅ 超管 Cookie（按 Session.java 签发逻辑的一次性脚本，SECRET_KEY 取自 /root/notelab/.env，uid=18/admin）
   经 POST /api/c-admin/users 建 `ctest1`（组 default，昵称「C测试一」）→ 列表可见（含 created_at 格式 `yyyy-MM-dd HH:mm:ss`）→ 重复建 409「用户名已存在」。
3. ✅ POST /api/c/auth/login ctest1 → 200 `{"ok":true,"id":1,"username":"ctest1","nickname":"C测试一","group_code":"default"}` + Set-Cookie notelab_c_session（4 段 c. token）；
   GET /api/c/auth/me（C Cookie）→ 200；**同一 C Cookie 打 B 端 /api/menu → 401**；**B Cookie 打 /api/c/auth/me → 401**（双端互不认）。
4. ✅ 错密码 → 401「用户名或密码错误」；连打 20 次 → 前 8 次 401（桶内余量，此前登录已计数 2 次）后全部 429「尝试过于频繁，请 5 分钟后再试」，语义与 B 端一致（10 次/300s，Redis 桶 `rl:c-login:<ip>`）。
5. ✅ POST /api/c/auth/register → 403「注册未开放」（开关默认关闭）；另临时 `C_REGISTER_OPEN=true` 重启验证开启路径：
   注册成功自动登录（Set-Cookie + me 200，默认入 default 组），测毕删除该行并重启恢复 403。
6. ✅ 回归：B 端错密码登录 401、/api/me、/api/menu、/api/perm/users 分页行为不变；Python :8000 可达，
   /api/login 错密码仍 401「用户名或密码错误」、/api/models 未登录 401（原有行为）。
   前后 mysqldump diff：现有表结构/数据零变化（唯一差异为 perm_routes 数据行——重启自动 upsert 时同路径多方法路由的 name 取值变化，属既有行为）。
7. ✅ 用户组：建组 vip → 重复建 409 → 改名 200 → ctest1 移入后删组 409「该用户组下仍有 1 名成员，不可删除」→
   移回 default 后删空组 200 → 删 default 组 400「默认组 default 不可删除」（保护）→ 删不存在组 404。
8. ✅ 附加：重置密码后新密码可登录/旧密码 401；禁用账号后登录 403「账号已被禁用」、其已发 Cookie 即时视同未登录（me 401）；
   重新启用恢复；/api/c-admin/* 未登录 → 401「请先登录」；无效 status 400；删用户后重复删 404。

### 遗留事项 / 下一步
- 阶段2：C 端业务接口（对话等）与数据隔离；C 端菜单/模型范围按组控制。
- `C_REGISTER_OPEN=true` 开启注册时暂无图形化管理入口，靠环境变量/`.env`。
- perm_routes 中同路径多方法路由的 name 字段在每次重启时可能变化（putIfAbsent 遍历顺序），展示性字段，无功能影响（既有行为，非本阶段引入）。
- 测试数据：保留 `ctest1`（密码已重置为 newpass888）作为验收证据；组表仅剩种子组 default。

## 2026-08-24 B/C 拆分阶段2：C 端数据域（scope/发布标记）+ /api/c/trpg 游玩接口 + /api/c/config 背景与 Spire 发布
### 前置
- 开工备份：`mysqldump notelab > data/bc-p2-dump.sql`（76KB）。
- 基线：perm_routes=74，pm2 notelab-java 在线；trpg_scenarios 2 行 / trpg_playthroughs 2 行（均属 B 端 admin）。

### 改动清单（仅 /root/notelab-java 内）
- `dao/DbSchema.java`：migrateSchema 末尾新增阶段2补列——`trpg_playthroughs.scope CHAR(1) NOT NULL DEFAULT 'b'` + 索引 `idx_trpg_p_scope_user(scope,user_id)`、`trpg_scenarios.published TINYINT NOT NULL DEFAULT 0`；
  新增私有守护 `hasColumn/hasIndex`（information_schema 探测，MySQL 无 ADD COLUMN IF NOT EXISTS），重启幂等。
- `model/entity/TrpgPlaythrough.java`（+scope）、`model/entity/TrpgScenario.java`（+published）。
- `mapper/TrpgPlaythroughMapper.java`：`listPlaysJoined` 增加 scope 参数（`WHERE p.scope=#{scope} AND p.user_id=#{userId}`，投影列不变）。
- `dao/TrpgDao.java`：剧本列表/详情投影尾部新增 `published`；对局行投影新增 `scope`（仅内部使用，响应经 playPayload 选键，对外契约不变）；
  新增 `listPublishedTrpgScenarios`、`setTrpgScenarioPublished`、`createTrpgPlay(.., scope)` 重载、`listTrpgPlays(scope,userId)` 重载（原 `listTrpgPlays(userId)` 内部固定 'b'，B 端签名不变）。
- `service/TrpgService.java`：`playPayload` / `scenarioOfPlay` 自 TrpgController 原样迁入；新增 `applyChoice`（choose 推进引擎：校验选项→服务端掷骰→日志→更新存档）+ `ChooseException`（携带状态码）。
- `controller/TrpgController.java`：新增 `POST /scenarios/{id}/publish|unpublish`（登录态即可，不存在 404）；
  plays 详情/choose/删除补 `scope='b'` 守护；choose 委托 `TrpgService.applyChoice`（检查顺序与原实现逐条一致）；响应键集合不变。
- `controller/SpireContentController.java`：新增 `POST /publish`（spire 键整体快照 → 顶层键 `spire_published`，合并写保留 background/menus/spire）、`POST /unpublish`（删除该键）。
- `controller/CTrpgController.java`（新，/api/c/trpg，CAuthUtil 守卫）：
  `GET /scenarios`（仅 published=1，RowUtil 同 B 端风格）、`GET /scenarios/{id}`（未发布/不存在 404）、`POST /scenarios/{id}/play`（scope='c'、user_id=C uid）、
  `GET /plays`、`GET /plays/{id}`、`POST /plays/{id}/choose`、`DELETE /plays/{id}`（一律 `scope='c' AND user_id=当前C用户`，他人存档 404；剧本下架后存量存档可继续）。
- `controller/CConfigController.java`（新，匿名）：`GET /api/c/config/background`（ui_config.background，空时回退 UiConfigService 默认）、`GET /api/c/spire/content`（spire_published，未发布返回空三数组，键序 cards/characters/skills 与 B 端一致）。

### 验证（127.0.0.1:8001 直连，全部实测；脚本 tmp-bc-p2/verify.sh，测后已删）
1. ✅ 构建 + `pm2 restart` 启动日志无 ERROR，perm_routes 74→86（+12：trpg 发布/下架 2、spire 发布/下架 2、c/trpg 6 路径、c 配置 2）；
   SHOW CREATE TABLE 确认 `scope char(1) NOT NULL DEFAULT 'b'`、`KEY idx_trpg_p_scope_user (scope,user_id)`、`published tinyint NOT NULL DEFAULT '0'`；
   再次重启（幂等性）启动正常、列/索引不重复、接口健康。
2. ✅ 发布流：SQL 建测试剧本 A（owner=超管）默认 unpublished → C 列表空、C 详情 404、C 开局 404 → B publish → B 列表 published=1、C 列表可见、C 详情 200（含 scenario 与 published）→
   C 开局（start/playing）→ choose c1 到 n2（steps=1）→ B unpublish → C 列表空、新开 404，**存量存档继续 choose 到结局（steps=2、state=ended、ending_title 正确）**；
   publish 不存在剧本 404；未登录 publish 401。
3. ✅ 隔离性（对抗用例：构造 B 用户 uid=4 恰等于 C 测试用户 uid=4）：
   B4 的 /api/trpg/plays 只见自己的 scope='b' 存档、C-A 的 /api/c/trpg/plays 只见自己的 scope='c' 存档、另一 C 用户列表为空；
   B4 看/choose/删 C 存档均 404，C-A 看/choose B 存档均 404，C-B 看 C-A 存档 404，未登录 401。
   DB 核对：同一 uid=4 下 scope='c' 与 scope='b' 存档并存互不串。
4. ✅ 背景：匿名 `GET /api/c/config/background` 200，内容与 B 端 /api/ui-config 的 config.background（`{"theme":"tech"}`）逐字节一致。
5. ✅ Spire：发布前匿名 `GET /api/c/spire/content` 空三数组 → B 端写入非空测试内容后 publish → C 端返回与 B 工坊当前内容逐字节一致 →
   ui_config 键保留（background,menus,spire,spire_published）且 B 端 GET /api/spire-content 不受影响 → unpublish → C 端回空、键删除。
6. ✅ 回归：B 端 /api/trpg/scenarios 键序 `id,user_id,title,genre,summary,created_at,updated_at,published`（新增字段在尾部）；
   /api/trpg/plays 存量存档（2,5）仍全部可见、存档响应键集合 `play_id,scenario_id,scenario_title,state,steps,ending_title,node,history` 不变；
   /api/menu、/api/ui-config 逻辑零改动（与 :8000 的差异仅为 Java 版 MenuTree/defaults 页面更多，属既有差异，非本阶段引入；两端 config 段一致）；
   Python :8000 正常（/api/models、/api/menu 带 Cookie 200，ui_config 同表读取一致）。
7. ✅ 清理：删除测试剧本 8/9、测试存档 10/11、测试 C 用户 ctest_p2_a/ctest_p2_b（经 /api/c-admin 删除）、B 端对抗用户 ctest_b4（uid=4）；
   ui_config 恢复开工前快照（Java/Python 双端校验与快照逐字节一致）；c_users 仅剩阶段1 保留的 ctest1。

### 遗留事项 / 下一步
- 阶段3（待排期）：C 端菜单/模型范围按用户组控制、其余 C 端业务域（对话等）拆分。
- `POST /api/trpg/scenarios/{id}/publish|unpublish` 按任务约定为「登录态即可」，未做剧本归属校验（任意 B 端登录用户可发布他人剧本，属内容运营场景设计）；如需仅属主/管理员可发布，后续加守卫即可。
- /vs（吸血鬼幸存者）纯前端无后端接口，本阶段零改动（符合预期）。
- C 端剧本列表未分页（与 B 端现状一致，LIMIT 未设）；内容量增大后再议。

## 2026-08-25 B/C 端拆分（阶段 0-5）总结
### 架构总览

```
                     公网/内网用户
                          │ :80（唯一推荐入口）
                       ┌──▼────────────── nginx ──────────────┐
                       │ /api/*（含 SSE 直通配置）  ──────────► 127.0.0.1:8001  notelab-java（Spring Boot，API 主后端）
                       │ ^~ /admin（前缀保留，含 _next 静态） ► 127.0.0.1:3020  notelab-b（B 端 antd 管理后台，basePath=/admin）
                       │ /（其余全部）              ──────────► 127.0.0.1:3010  notelab-c（C 端游戏中心）
                       └──────────────────────────────────────┘
旧链路（观察期保留，回滚依赖）：
  :3000 myapp（旧 Next 站，/api rewrites → 8001）；:3001 myapp-dev；:8000 Python FastAPI（pm2 notelab）
数据库：共享 MySQL notelab 库（notelab-java 连接池 + Python pymysql 短连接并存；ui_config 等表双端同读）
```

| 进程 (pm2)   | 端口 | 角色                                   | 状态（阶段5 回归确认） |
|--------------|------|----------------------------------------|------------------------|
| notelab      | 8000 | Python FastAPI（旧，观察期，未动）      | online |
| notelab-java | 8001 | Spring Boot API 主后端                  | online |
| myapp        | 3000 | 旧 Next 生产站（观察期，回滚依赖）      | online |
| myapp-dev    | 3001 | 旧 Next dev（观察期，回滚依赖）         | online |
| notelab-c    | 3010 | C 端游戏中心（新）                      | online |
| notelab-b    | 3020 | B 端 antd 管理后台（新，/admin）        | online |
| nginx        | 80   | 统一入口（systemd，非 pm2）             | active |

pm2 dump（`pm2 save`）已含全部 6 进程（阶段5 复核：notelab-c/notelab-b 均在 dump 内）。

### 各阶段 commit 清单

| 阶段 | 仓库 | commit | 内容 |
|------|------|--------|------|
| 0 | notelab-java | `3acaec2` | nginx 前缀代理与双前端壳（运维归档，无 Java 代码改动）；服务器侧装 nginx 1.24 + 建 notelab-c/notelab-b 两个 Next 16.2.12 壳 + pm2 登记 |
| 1 | notelab-java | `15df99c` | C 端身份体系：c_users/c_user_groups 表、4 段 `c.` token 隔离、/api/c/auth（login/me/logout/register 受 C_REGISTER_OPEN 开关）、/api/c-admin（users/groups 全套） |
| 2 | notelab-java | `1cfb02b` | C 端数据域：trpg_playthroughs.scope + trpg_scenarios.published（只增不改）、/api/c/trpg 游玩全套、/api/c/config 匿名接口、剧本与 Spire 内容 publish/unpublish |
| 3 | notelab-c | `3eda8c5` | C 端前端：游戏中心外壳（顶部导航+移动端 Tab+登录守卫）+ 落地页 + TRPG/爬塔/VS 三游戏页（引擎零改动，接口改 /api/c/*） |
| 3 | notelab-java | `cee707a` | 阶段3 记录与任务简报归档 |
| 4 | notelab-b | `ac181cb` | B 端前端：antd 管理后台（21 路由，myapp 19 页 1:1 + trpg 旧跳转 + 新增 /c-users）、SSE 代码与 myapp 逐字节一致、/admin/api/tts 保留、localStorage b_ 前缀 |
| 5 | notelab-java | 本次提交 | 全量回归 + 测试数据清理 + 文档收尾（PROGRESS 本章节 + ops/BC-SPLIT-SUMMARY.md） |

### 阶段5 全量回归结论（2026-08-25，经 nginx :80 实测）

**B 端**：登录错密码/无此用户 401 ✅；/api/me、/api/menu（7 项）、/api/models、/api/conversations、/api/tools、
/api/toolbox（summarize 真实调用 200）、/api/extract（结构化抽取正确）、/api/rag/docs、/api/english/scenarios（8 场景）、
/api/perm/overview（roles=super_admin/user）、/api/perm/users、/api/ui-config、/api/c-admin/users、/api/c-admin/groups、
/api/trpg/scenarios（含 published 字段）全部 200 ✅；/admin 307→dashboard、/admin/login 200、_next 静态 200 ✅；
POST /admin/api/tts → 200 audio/mpeg 11KB（Kokoro 本地合成）✅；:8001 直连对照一致 ✅。
SSE：/api/chat 分块渐进（ttfb 0.42s → done）✅；/api/arena 双模型并行（started/双 content/done，keepalive 代码在位、
模型响应快未触发，P4 已长跑实测）✅；/api/english/chat 含语法纠错 correction 事件 ✅。
trpg-gen 轮询：POST /api/trpg/scenarios → 202 task_id → GET tasks/{id} state=done 产出剧本 ✅（测试剧本已清理）。
spire-editor 发布闭环：写测试草稿→publish→C 端匿名可见→unpublish→恢复原草稿逐字节一致 ✅。

**C 端**：登录 ctest1 200 ✅、错密码 401 ✅、me ✅、发布前剧本列表空 ✅、临时发布剧本 3 后列表/详情可见 ✅、
开局（play_id 分配、state=playing）✅、choose 推进（steps=1）✅、存档列表/详情 ✅、删除存档 ✅、下架后列表归空 ✅、
匿名 /api/c/spire/content 空三数组 ✅、匿名 /api/c/config/background ✅、登出后（浏览器语义）me 401 ✅。

**隔离对抗**：B Cookie → /api/c/auth/me 401 ✅；C Cookie → /api/menu、/api/me 401 ✅；
C 活跃存档对 B 不可见（列表不含、详情 404、choose 404）✅。

**旧链路**：:3000 / 307、:3000/api/menu 401 ✅；:8000 /docs 200、登录错密码 401、未登录 /api/menu 401 ✅；:8001 直连 ✅。
**进程**：pm2 六进程全 online，notelab-c/notelab-b 在 dump ✅。

**测试数据清理**（清理前 SELECT 展示、清理后复验归零）：
- 删：会话 23（本阶段 chat SSE 测试）、剧本 10「bctest_p5_temp」（本阶段 trpg-gen 测试）及其 gen task 4、
  回归过程中产生的 C 端存档（play 13/14，随测随删）。
- 恢复：剧本 3「雾港惊魂」的临时发布已下架复位；ui_config 与回归前快照一致（spire_published 键已随 unpublish 移除）。
- 保留：c_users 仅剩 **ctest1**（阶段1 建、阶段3/4/5 回归 fixture，任务矩阵依赖；是否删除由用户决定）；
  正式数据（admin/user/11 及全部真实内容）零触碰。
- 另发现旧系列遗留（不在本系列清理范围，未动）：trpg_gen_tasks id=2（MP 迁移系列测试任务，剧本 6 已不存在）、
  users 表 crosstest*/dup*/s5* 旧测试账号（数据层迁移系列遗留）。

### 回滚方法汇总（自下而上，均不删数据）

1. **C 端前端**：改 nginx `^~ /admin` / `/` 的 proxy_pass 目标后 `systemctl reload nginx`（秒级止血）；
   或代码级回退（notelab-b 占位壳备份在 /root/.notelab-b-p0-backup；notelab-c 见 ops/BC-SPLIT-P3.md 回滚节）。
2. **B 端前端**：同上；如需回到旧 myapp 单站入口，把 nginx `/` 指向 :3000 并保留 `/api` → :8001 即可。
3. **Java 后端 C 端接口**：接口为纯增量（新表/新列/新路由），无需回滚；如必须撤销，停 notelab-java 回退到
   `1cfb02b` 之前的构建（`3acaec2` 及以前不含 C 接口）再重启，新表新列保留无害。
4. **nginx 整体**：`systemctl stop nginx && systemctl disable nginx` → 流量入口回到原状（用户直连 :3000）。
5. **Python 版**：保持运行未动，:8000 随时可作为后端兜底（myapp 的 next.config 备份 next.config.ts.bak-8000 仍在）。

### 遗留事项（详见 ops/BC-SPLIT-SUMMARY.md）
Python 版与旧 myapp 的退役决定权在用户（观察期建议见 SUMMARY）；C_REGISTER_OPEN 开放方式、公网安全组 :80 确认、
/c-users 菜单项、/api/c-admin 超管限制等事项均已记录。

---

## 2026-08-25 · BC 拆分 P6：B 端移除游玩功能 + 传统管理系统样式改造

详见 `ops/BC-SPLIT-P6.md`（含 /api/menu 前后对比、构建输出、404/307 验证、备份与提交号）。

### Part A：移除 B 端游玩功能
- MenuTree 删除 `trpg-play`/`vs`/`spire` 三叶子（g_games 拍平为仅 `trpg-gen`）；PageRoutes 同步删除，
  启动 upsert 不再重建（实测重启后 perm_routes 三行未复活，page 路由 20→17）。
- 共享 MySQL 先 mysqldump 备份（/root/backups/notelab-before-p6-20260825-201209.sql）再删
  perm_routes 3 行 + perm_role_routes 3 条悬空引用。
- notelab-b 删除 vs/、spire/、trpg/play/ 页面与孤儿 vs-engine；`/trpg` 改 next.config 服务端 307 → `/trpg/gen`；
  trpg/gen 移除跳转游玩页的两个按钮。玩法 API（/api/trpg/plays 等）保留未动。
- 验证：/api/menu 无玩剧本/吸血鬼/爬塔 ✅；/admin/vs|/spire|/trpg/play 404 ✅；/admin/trpg 307 ✅（nginx 同）。

### Part B：传统 antd 管理后台样式（功能与契约零变化）
- 外壳：白底固定 Sider + 白底 Header（用户/超管 Tag/退出）+ #f0f2f5 内容区；移除主题背景铺底、毛玻璃、ThemePicker。
- 登录/注册/仪表盘 antd 化；403 态改 antd Result；各 Table 去透明 hack。
- chat/english（SSE/TTS/打字机逻辑保留，外壳与气泡中性化）、trpg/gen（antd Modal/Form）、
  lowcode（Tabs/Card/antd 控件，逻辑不变）、tools（卡片网格→Table）、ui（antd 化，C 端背景管理保留）。
- 删除 components/ui/{modal,confirm,select,form}、ThemePicker；globals.css 删毛玻璃/主题工具类（-51 行）。
- 验证：`npm run build` ✅；16 个页面 curl 200 ✅；`/admin/api/tts` 200 audio/mpeg ✅；pm2 六进程 online。

### 提交
notelab-java `b183951`；notelab-b `4cf3b38`（A）、`f1151ad`（B1）、`23243a1`（B2）。

---

## 2026-08-25 · BC 拆分 P7：B 端 light/dark 双主题切换

详见 `ops/BC-SPLIT-P7.md`（含改动清单、自检清单、验证输出、提交号）。

### 实现
- 主题状态：localStorage `notelab_b_theme`（light|dark，默认 light）；`AntdProvider` 内 ThemeContext + `useTheme()`，
  ConfigProvider `algorithm` 动态切换 `darkAlgorithm`/`defaultAlgorithm`（AntdRegistry SSR 样式收集保持不变）。
- 防闪烁：根 layout `<body>` 顶部内联脚本，首帧前按 localStorage 给 `<html>` 加/去 `.dark`（App Router 中 `<head>` 由框架托管，此为官方等效写法）；`<html suppressHydrationWarning>`。
- Tailwind v4：globals.css `@custom-variant dark (&:where(.dark, .dark *));` + `.dark` 深色 CSS 变量（#141414/#e4e4e7）；
  speak-btn/tts 流光/骨架等自定义件加 `.dark` 覆盖。
- 切换入口：Header 右侧（用户信息左边）antd Button(type=text) + lucide Sun/Moon（目标模式图标），点击即持久化。
- 深色适配 24 文件 230 处 `dark:` 变体：外壳（Sider/Header/内容区/Drawer）、登录/注册、15 业务页残留浅色
  （chat/english 气泡与侧栏选中态、lowcode 画布节点、spire-editor 弹窗块、ui/rag/arena 等）；
  SpireCardView 卡面美术为固定设计不反转；themes.ts 属 C 端背景数据未动。**交互逻辑零改动。**

### 验证
- `npm run build` ✅（0 错）；`pm2 restart notelab-b` 后 `/admin/login` 200、`/admin/register` 200、
  `/admin/dashboard` 未登录 200+客户端守卫跳登录（与 P6 契约一致）、`/admin/trpg` 307→/admin/trpg/gen。
- 超管 Cookie：16 个 (admin) 页全 200；`:8001/api/me` 返回 super_admin（会话机制未受影响）。
- SSR HTML 含首帧脚本；编译 CSS 含 `.dark{--background:#141414}` 与全系 `dark:` 选择器。
- 自检：裸 `bg-white`/`bg-[#f0f2f5]` 均 0 残留；仅存预期例外（亮色模式专属 Moon 图标、卡面美术、C 端背景数据）。

### 提交
notelab-b `f15a058`（主题本体）、`d89402f`（gitignore）；本记录归档于 notelab-java。

---

## 2026-09-22 · 每日英语翻译练习（三仓联动：服务端 + B 端 + C 端）

需求：每天 0 点自动激活一组中文句子（B 端预建 + 入队），句子分 3 阶梯（1 简单 / 2 中等 / 3 困难，每阶约 3-5 句，
中文 10-50 字），C 端用户逐句提交英文译文 → 大模型判分（是否准确 + 0-100 分 + 修正译文 + 中文讲解 + 逐点错误标注），
同一 (用户 + 日期 + 句子) 只留一条记录（重复提交覆盖）；B 端支持手动输入 / 批量导入（中文句末标点+换行切分）/ AI 批量生成。

### 实现（本仓 notelab-java）
- **建表（纯只增，重启幂等）**：`dao/DbSchema.translateSchema()` 新增 `en_tr_groups` / `en_tr_sentences` / `en_tr_submissions`
  三表（`CREATE TABLE IF NOT EXISTS`，`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`），由 `initSchema()` 追加调用。
  `en_tr_submissions` 的 `UNIQUE KEY uk_user_sentence_date (c_user_id, sentence_id, submit_date)` 是去重覆盖核心；
  `en_tr_sentences` 带 `fk_entr_s_group ... ON DELETE CASCADE`；未对 `c_users` 建外键（避免耦合，仅索引）。
  **不改/删任何现有表**；验收数据备份 `data/backup/en_tr_acceptance_data_*.sql`。
- **持久层（照 EnglishDao/TrpgDao 范式）**：`model/entity/EnTrGroup|EnTrSentence|EnTrSubmission`、
  `mapper/EnTrGroupMapper|EnTrSentenceMapper|EnTrSubmissionMapper`（含 `@Insert` upsert 与 JOIN 查询）、
  `dao/TranslateDao`（静态门面 + RowUtil Map 出口）；`DaoSupport` 构造器注入三个新 Mapper。
- **RowUtil 增强**：`norm()` 支持 `LocalDate` / `java.sql.Date` → `'yyyy-MM-dd'`（DATE 列出口字符串确定化，
  DATETIME 仍为 `'yyyy-MM-dd HH:mm:ss'`，存量契约不变）。
- **业务层** `service/TranslateService`：判分提示词（只输出 JSON 对象，明确「允许合理多样译法、ref_en 仅供参考」）、
  出题提示词（只输出 JSON 数组 `[{tier,zh_text,ref_en}]`）、`splitZhText()` 中文切句（句末标点 `。！？；…!?;` + 换行，
  trim/去空/去重/过滤 <4 字，>50 字仅提示仍入库）、`todayPayload()` 组装（组 + 阶梯元信息 + 句子 + 既有提交回填）、
  `parseJsonObject/parseJsonArray`（容忍三反引号围栏）。上游异常/解析失败统一抛 `GradeException`/`GenerateException`，
  Controller 转明确 error（502），不冒泡 500。
- **C 端** `controller/TranslateController`（`/api/translate`，`CAuthUtil`）：`GET /today`、`POST /submit`（校验登录 /
  非空 / ≤2000 字 / 句子属于当天激活组，判分后 upsert）、`GET /history?date=`。当天无激活组返回空态（不报错）。
- **B 端** `controller/TranslateAdminController`（`/api/admin/translate`，`AuthUtil` + RBAC `page:/translate`，无权 403）：
  组列表/新建/详情/改（含 draft↔queued、手动设 activated_date 强制发布、清空退草稿）/删、入队、手动加句、改删单句、
  批量导入（返回 `{imported, skipped, long_count}`）、AI 生成（限流 `entr-gen:<ip>` 6 次/300s）、`POST /activate-today`（手动补跑）。
- **定时任务（本仓首次启用 Spring Scheduling）**：`common/SchedulingConfig`（`@EnableScheduling`）+
  `scheduler/TranslateScheduler`（`@Scheduled(cron = "0 0 0 * * ?")`）：取 `queued` 中 `created_at` 最早的组置 `used` +
  `activated_date=CURDATE()`；幂等（当天已有 used 组则跳过）+ CAS（`WHERE status='queued'`，受影响行数=1 才算成功）；异常只记日志不影响存活。
- **RBAC/菜单**：`PageRoutes` 加 `{"/translate", "翻译句子库"}`（启动自动 upsert 进 `perm_routes`），
  `MenuTree` 在「工具箱」组加菜单项（超管默认可见，可在 `/perm` 分配角色）。

### 验证（自验命令与结果）
```bash
cd /root/notelab-java && mvn -DskipTests package     # BUILD SUCCESS，产物 target/notelab-java.jar
pm2 restart notelab-java && pm2 logs notelab-java --lines 25 --nostream
```
- 启动日志无错，`Started NoteLabApplication in 4.7s`；`Bootstrap` 输出 `perm_routes=104`（含 11 条 translate 相关路由）。
- `SHOW TABLES LIKE 'en_tr_%'` → `en_tr_groups` / `en_tr_sentences` / `en_tr_submissions` ✅；
  `SHOW CREATE TABLE en_tr_submissions` 确认 `uk_user_sentence_date` 与 `idx_user_date` 均建成 ✅。
- `perm_routes` 自动登记：`page:/translate` + `api:/api/translate/{today,submit,history}` +
  `api:/api/admin/translate/{groups,groups/{id},groups/{id}/sentences,groups/{id}/import,groups/{id}/generate,groups/{id}/queue,sentences/{sid},activate-today}` ✅。
- **定时激活实测**：0 点整真实触发并打日志
  `每日翻译练习激活任务：{date=2026-09-22, activated=false, group_id=null, title=null, reason=队列为空（无 queued 组），跳过}`；
  随后建组 → 导入句子 → `POST /queue`（status=queued, queued=1）→ `POST /activate-today`
  返回 `{activated:true, group_id:3, date:"2026-09-22"}`，组变 `used` + `activated_date=2026-09-22` ✅；
  **再次调用**返回 `{activated:false, reason:"当天已有激活组，跳过"}`（幂等）✅。
- **B 端接口 curl**（超管 Cookie）：组列表/新建/详情/改标题+强制发布日期/清空日期退草稿/入队/空组入队 400/删组（级联）/
  手动加句/改 sort_order/删句/404 组与句 — 全部符合预期；批量导入 `{"imported":5,"skipped":2}`
  （跳过项 = 文本内重复 1 + 过短「好。」1），切分保留句末标点 ✅；AI 生成（机场出行，t1=3/t2=3/t3=2）
  返回 `{"generated":8,"skipped":0,"requested":8}`，含分阶 zh_text + ref_en，耗时约 78s ✅。
- **C 端接口 curl**（`notelab_c_session`）：`GET /today` 返回 group/date/tiers/sentences（submission 初始 null）；
  `POST /submit` 准确译文 → `{"accurate":true,"score":100,"errors":[]}`；错误译文 `He buy a book yesterday.` →
  `{"accurate":false,"score":60,"corrected":"He bought a book yesterday.","errors":[{"type":"时态","original":"buy","suggestion":"bought","note":"…"}]}` ✅；
  `GET /today` 二次拉取正确回填 submission（含 errors 数组与 updated_at）✅；`GET /history?date=` 正常、非法日期 400 ✅。
- **去重覆盖实测**：同一句（sentence_id=13）连续提交 3 次，`SELECT * FROM en_tr_submissions` 仅 **1 行**（id=2），
  `en_text/accurate/score/corrected/explanation/errors_json` 全部为最后一次结果，`created_at` 不变、`updated_at` 刷新 ✅。
- **优雅降级**：无激活组时 submit → `400 {"error":"今日暂无练习内容"}`；句子不属于今日 → `400 {"error":"该句子不属于今日练习"}`；
  空译文 → 400；缺 sentence_id → 422；未登录 → 401；非超管 B 端账号 → `403 {"error":"无「翻译句子库」页面权限"}` ✅（无 500 冒泡）。
- **经 nginx（:80）全链路**：`/api/translate/today`、`/api/translate/submit`、`/api/translate/history`、
  `/api/admin/translate/groups`、`/admin/translate`（B 端页 200）、`/games/translate`（C 端页 200）全部正常 ✅。
- 前端构建：`cd /root/notelab-b && npm run build` ✅（路由表含 `/translate`）→ `pm2 restart notelab-b` →
  `curl -o /dev/null -w %{http_code} http://127.0.0.1:3020/admin/translate` = **200**（经 nginx `/admin/translate` 亦 200）；
  `cd /root/notelab-c && npm run build` ✅（路由表含 `/translate`）→ `pm2 restart notelab-c` →
  `curl -o /dev/null -w %{http_code} http://127.0.0.1:3010/games/translate` = **200**
  （C 端 `basePath=/games`，故对外为 `/games/translate`；裸 `/translate` 属 basePath 外，404 符合既有 `/trpg` 同款行为）。
- 收尾：验收用的 4 组 / 16 句 / 3 条提交已删除归零（先 `mysqldump` 备份到 `data/backup/`），生产库无残留测试数据。

### 下一步（可选，未做）
- 0 点激活依赖服务在线；若需停机维护后补跑，可在 B 端点「立即激活今日」（同一幂等逻辑）。
- C 端练习统计（连续打卡 / 平均分）与 B 端答题情况看板尚未做，需求未提出。

## 2026-09-25 · 补齐 `/c-users` 菜单节点与 3 条缺失的页面路由

### 问题
B 端 `/admin/c-users`（C 端用户管理）**不在左侧菜单里**，只能从 `/perm` 页的一张 Card 手工跳转。

根因：菜单显示的唯一起点是硬编码常量 `model/MenuTree.java` 的 `MENUS`（不来自页面目录、不来自数据库，`/ui` 界面配置只能覆盖**已有 key** 的 name/icon）；能否分配给角色则由另一份常量 `model/PageRoutes.java` 的 `PAGE_ROUTES` 决定（启动时 upsert 成 `page:*` 进 `perm_routes`）。`/c-users` **两处都没有登记**，所以菜单不显示（任何角色都看不到），且 `page:/c-users` 从未进 `perm_routes`——连「角色组管理」的「📦 未挂菜单的页面」分组都进不去，无法分配给任何角色。

顺带发现两处同类偏差：`/docs`（文档编辑）、`/user/invites`（邀请码）**只有菜单节点、缺页面路由**。超管因 `allowed==null` 不过滤而可见，普通角色却因 `page:/docs` 不在 `perm_routes` 里而永久看不到。

### 改动（提交 `1934629`）
- `model/MenuTree.java`：`g_users` 组追加 `menu("c-users", "C端用户管理", "🙋", "/c-users", true)`。
- `model/PageRoutes.java`：补齐 `/c-users`、`/docs`、`/user/invites` 三条页面路由。

### 验收（服务器实测）
- 构建：`cd /root/notelab-java && git pull --ff-only && /usr/bin/mvn -B -DskipTests -q package` ✅（jar 重新生成 11:51:29，46.5 MB）→ `pm2 restart notelab-java` → `online`。
- 探活：`curl http://127.0.0.1:8001/api/menu` = **401** ✅；经 nginx `http://127.0.0.1/api/menu` 亦 **401** ✅；`/api/c-admin/groups` = **401** ✅（未登录即正常）。
- 路由登记：重启后 `perm_routes` 新增三行 ✅
  | code | path | name |
  |---|---|---|
  | `page:/c-users` | `/c-users` | C端用户管理 |
  | `page:/docs` | `/docs` | 文档编辑 |
  | `page:/user/invites` | `/user/invites` | 邀请码 |
- 影响面：只增菜单节点与权限码，无接口行为、无数据变更。

### 待人工确认 / 未做
- **普通角色尚未授权**：`perm_role_routes` 里 `user` 角色当前只有 `page:/`、`page:/trpg/gen` 两条，**不会**自动获得新页面（`PermService` 的普通角色默认权限仅在 `roleRouteCodes(ROLE_USER)` 为空时写入一次）。需在 B 端 `/user/roles` 勾选，或执行
  `INSERT IGNORE INTO perm_role_routes (role_code,route_code) VALUES ('user','page:/c-users');`（回滚：`DELETE FROM perm_role_routes WHERE role_code='user' AND route_code='page:/c-users';`）。
- **超管可见性**待用户在 `/admin` 页面刷新后目视确认（超管 `allowed==null` 不过滤，按逻辑必然出现）。
- ⚠️ **文档纠错**：`AGENTS.md` 原「构建与发布」里的探活命令 `curl -i http://127.0.0.1:8001/api/health` **是错的**——本服务**没有 `/api/health`** 端点（`src/` 全量检索零命中），请求只会返回 `{"detail":"Not Found"}`，易被误判为服务未启动。已改为 `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8001/api/menu`（401 即正常），与 `ops/daily-iteration/daily-check.py` 的探法一致（该脚本本就用 `/api/menu` + 接受 200/401/403，未受影响）。

## 2026-09-25 · 新增批量设置账户用户组接口 `POST /api/perm/users/batch-role`

### 背景
B 端「用户组 → 路由菜单」的绑定此前已通（角色组分配路由 → 成员菜单即时生效 → 页面守卫按同一份授权拦直接敲 URL），但**把账户放进组只能一个一个改**：`POST /api/perm/users/{id}/role` 是单账户接口，账户管理页也只有行内单选下拉。批量授权是日常动作，缺入口就只能靠人重复点。

### 改动（提交 `4711aaf`）
- `controller/PermController.java`：新增 `POST /api/perm/users/batch-role`（仅超管），body `{ids:[], role}`，把一批账户的 `role` 直接设为目标角色组。
- `dao/UserDao.java`：新增 `setUsersRole(ids, role)`（一条 `UPDATE ... WHERE id IN`）与 `listUsersByIds(ids)`（一次查完核对存在性与当前角色，避免 N+1）。

### 语义与守护（都在服务端，不依赖前端）
- **语义**：`users.role` 是单值、一个账户只属于一个用户组，所以这是「**改属**」而非「追加」，与单人接口一致。
- 目标角色组必须已存在（防脏 `role` 值写进 `users` 表造成悬空账户）；
- 单批上限 200；不存在的 id 剔除后返回实际生效数量（前端已删账户时仍可提交）；
- **超管归零保护**：按「**本次降级了几个超管**」计算，若剩余超管为 0 则整批拒绝。这里不能照抄单人接口的 `countSuperAdmins() <= 1`——单人接口只降一个所以够用，批量场景下「一次把仅剩的 2 个超管都改走」会被漏判。

### 验收（服务器实测）
- 编译预检：先 scp 两个文件到服务器 `mvn -B -DskipTests -q package` → **MVN_EXIT=0**，随后 `git checkout --` 还原工作区（避免推编译不过的提交）。
- 部署：`git pull --ff-only` → `mvn package` → `pm2 restart notelab-java` → `online`。
- **挂载验证（401 vs 404 有区分度）**：`POST /api/perm/users/batch-role` = **401**（路由存在、需鉴权）；对照 `POST /api/perm/definitely-not-here` = **404**。
- **权限码自动登记**：重启后 `perm_routes` 出现 `api:/api/perm/users/batch-role`，`method=POST`，`kind=api` ✅（证明 PermService 启动时采集到了新端点）。

### 边界
`api:*` 权限码**只登记、不强制校验**——接口实际防护仍靠各 Controller 自己的登录/超管判断，勾掉某个 `api:*` 不会让接口拒绝访问。本次新接口自身做了 `isSuperAdmin` 校验，故不依赖该机制。

