# notelab-java

NoteLab AI 试验后台的 **Java（Spring Boot）重写版**。目标：1:1 重写 `/root/notelab`（Python FastAPI）后端，API 契约完全兼容，在 **8001 端口**与 Python 版（8000）并行运行，验收通过后由人工将前端流量从 8000 切到 8001。

> 本文件随迭代持续更新，当前状态以「功能清单」为准。详细验证记录见 [PROGRESS.md](PROGRESS.md)。

## 功能清单

| 模块 | 接口 | 状态 |
|---|---|---|
| 认证 | /api/register /api/login /api/logout /api/me | ✅ 已完成（会话 Cookie 双向兼容；**register 已关闭，改由权限管理建号**） |
| 菜单/模型 | /api/menu /api/models | ✅ 已完成（models 600s 缓存，结构逐项对比一致） |
| 智能对话 | /api/conversations* /api/chat(SSE) | ✅ 已完成（流式+历史+自动命名验证通过） |
| 文本工具箱 | /api/toolbox | ✅ 已完成 |
| 文档问答 RAG | /api/rag/upload /api/rag/docs /api/rag/ask | ✅ 已完成（JSON 上传，检索打分与 Python 完全一致） |
| 英语学习 | /api/english/* | ✅ 已完成（场景/开场白/语法纠错全对齐） |
| 结构化抽取 | /api/extract | ✅ 已完成 |
| 模型竞技场 | /api/arena (并行 SSE+心跳) | ✅ 已完成（10s 心跳实测生效） |
| 界面配置 | /api/ui-config | ✅ 已完成（30s 缓存+保存失效） |
| 权限管理 RBAC | /api/perm/*（overview/角色路由组/账户角色/建号/重置密码） | ✅ 已完成（users.role + 权限路由表 + 启动自动注册路由 + 菜单按角色过滤） |
| C 端身份体系（B/C 拆分阶段1） | /api/c/auth/*（login/me/logout/register）+ /api/c-admin/*（用户/用户组管理） | ✅ 已完成（c_users/c_user_groups 新表 + 4 段 c. token 与 B 端隔离 + 注册开关默认关闭） |
| C 端游玩与内容发布（B/C 拆分阶段2） | /api/c/trpg/*（已发布剧本游玩）+ /api/c/config/background、/api/c/spire/content（匿名）+ B 端 publish/unpublish | ✅ 已完成（trpg_playthroughs.scope + trpg_scenarios.published 补列，存量默认 B 归属零迁移） |
| 每日英语翻译练习 | C 端 /api/translate/*（today/submit/history）+ B 端 /api/admin/translate/*（句子组 CRUD/手动加句/批量导入/AI 生成/入队）+ 0 点定时激活 | ✅ 已完成（en_tr_groups/en_tr_sentences/en_tr_submissions 三张新表 + @EnableScheduling + 大模型判分/出题，同人同日同句 upsert 覆盖） |

## 项目简介

- 技术栈：Java 17 + Spring Boot 3.4 + **MySQL（直连 Python 版同一个库）+ HikariCP 连接池（最大 10）** + 原生 HttpClient（调模型网关）。
- 只实现前端（Next.js，/root/myapp）实际调用的 `/api/*` 接口；Python 版的服务端渲染 HTML 页面不在重写范围（前端不使用）。
- **数据层直连 Python 版正在用的 MySQL（127.0.0.1:3306 的 `notelab` 库），两个服务共享同一份数据**：
  用户、会话、消息、英语学习、ui_config 全部互通，一端写入另一端即时可见，无需数据迁移。
  建表语句与 Python 版 db.py 逐条一致（仅 CREATE TABLE IF NOT EXISTS，不改动现有表结构）。
  原 SQLite 方案已废弃：`data/notelab-java.db` 仅保留为历史产物，不再读写。

## 环境依赖

- JDK 17+（服务器已装 openjdk-17）
- Maven 3.8+（已配阿里云镜像 /root/.m2/settings.xml）
- 环境变量 `QWEN_API_KEY`（阿里云 token-plan 网关密钥，已写入 /etc/environment）；
  若进程环境没有，程序会按 `进程环境变量 → ./.env → /root/notelab/.env（只读）→ 默认值` 的顺序读取
  `QWEN_API_KEY / QWEN_BASE_URL / QWEN_MODEL / SECRET_KEY / REDIS_HOST / REDIS_PORT`，
  其中 SECRET_KEY 复用 Python 版的值是**会话 Cookie 兼容**的关键。
- **MySQL**（阶段5 起必需）：本机 systemd 服务 `mysql`（127.0.0.1:3306），库名 `notelab`，
  连接参数读取 `MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB`
  （与 Python 版同用 /root/notelab/.env 中的同一套账号；密钥不落代码、不入 git）。
- Node.js / pm2（仅用于进程管理）
- Redis（可选）：限流与 Python 版共用（键 `rl:*`）；不可用时自动退回进程内限流。

## 构建

```bash
cd /root/notelab-java
mvn -DskipTests package     # 产物 target/notelab-java.jar
```

## 启动

```bash
# pm2 方式（推荐）
cd /root/notelab-java
pm2 start "java -jar target/notelab-java.jar" --name notelab-java
pm2 logs notelab-java --lines 50

# 手动方式
java -jar target/notelab-java.jar
```

重启（重新构建后）：

```bash
mvn -DskipTests package && pm2 restart notelab-java
```

## 端口说明

| 服务 | 端口 | 绑定地址 | pm2 进程 |
|---|---|---|---|
| Python FastAPI（现网） | 8000 | 127.0.0.1 | notelab |
| Java Spring Boot（本版） | 8001 | 127.0.0.1 | notelab-java |
| Next.js 前端 | 3000 | — | myapp |

前端 next.config 把 `/api/*` 同源代理到 `http://127.0.0.1:8000/api/*`；切流即把该目标改为 8001（由人工执行）。

## 与 Python 版的关系及切流方法

1. **契约来源**：所有请求/响应契约以 `/root/notelab/main.py` 源码为准（错误文案、状态码、字段顺序均照抄）。
2. **会话兼容**：HMAC 会话 Cookie（`notelab_session`，base64url(`uid.exp.hmac_sha256_hex`)，30 天有效期）与 Python 版算法完全一致，且两服务共享同一张 users 表，切流后用户登录态无缝保留（已实测双向互认）。
3. **限流兼容**：与 Python 版共用 Redis 限流键（`rl:register:<ip>` 5次/10分钟、`rl:login:<ip>` 10次/5分钟）。
4. **数据共享**（阶段5 起）：两服务直连同一个 MySQL 库 `notelab`，注册/会话/消息/英语/ui_config 全部共享，
   **切流无需任何数据迁移**。切换前的备份见 `data/backup-before-mysql-switch.sql`（mysqldump，不入库）。
5. **切流步骤**（人工）：
   - 确认 Java 版回归通过（见 PROGRESS.md）；
   - 修改 `/root/myapp/next.config.ts` 中 rewrites 目标 `8000 → 8001` 并重启 myapp；
   - 观察无异常后 `pm2 stop notelab`（数据在同一 MySQL，停 Python 服务不丢任何数据）。

## 已知差异（不影响前端）

- 非法 JSON 请求体的 422 `detail` 数组内部字段与 FastAPI 略有差异（外层结构一致）。
- Set-Cookie 头细节差异：Python 版对带 `=` 的 token 值加双引号、Java 版不加；`SameSite=lax/Lax` 大小写不同。均为合法 Cookie，浏览器/代理行为一致，互认已实测通过。
- /api/rag/upload 为 **JSON** 接口（`{name, content}`），与 main.py 实际实现一致（任务简报中写的 multipart 以源码为准）。

## 已知差异补充（阶段5）

- extract 请求缺 `fields` 字段时：Python 返回 422 `detail`（Pydantic 校验），Java 返回 400 `{"error":"文本和目标字段都不能为空"}`；正常请求两端一致。
- ui_config 现为两服务共享表：任何一端保存配置会即时影响另一端（30s 缓存过期后生效），操作即生产。

## 验证状态

全部 5 个阶段完成并通过与 Python 版（8000）的逐接口 curl 对比验收（阶段5 为共享 MySQL 数据互通 + 全量回归），详见 [PROGRESS.md](PROGRESS.md)。

## 切流状态（2026-08-07 22:45 更新）
- ✅ 切流已完成：前端生产 3000 与开发 3001 的 API 流量均已切到 Java 版 8001，原 next.config 备份为 /root/myapp/next.config.ts.bak-8000
- Python 版 8000 观察期内保持运行，确认无异常后退役：pm2 stop notelab（数据都在共享 MySQL，零丢失）
- 回滚方法：next.config.ts rewrites 改回 8000（或恢复备份文件），然后 npm run build 并 pm2 restart myapp
- 切流验证记录见 PROGRESS.md 末节

## RBAC 权限体系（2026-08-08 上线）

- **注册入口已关闭**：POST /api/register 返回 403；新账号由超级管理员在「权限管理」页创建（POST /api/perm/users）。
- **users 表新增 role 列**（`super_admin` / `user`，默认 `user`，ADD COLUMN 只增不改，Python 服务不受影响）。首次启动若无超级管理员，自动把最早注册的用户提升为超级管理员（本次为 tester1）。
- **权限路由表 perm_routes**：每条路由带权限码。页面路由 `page:<path>`（驱动菜单可见性），API 路由 `api:<path>`。
  **自动注册**：每次启动时从 SpringMVC 请求映射自动采集全部 Controller 路由 + 前端页面路由 upsert 入库，以后新增路由重启即自动登记，无需手工维护。当前共 40 条（11 页面 + 29 API）。
- **角色-路由组（perm_role_routes）**：超级管理员天然拥有全部路由（含未来新增，不存行、无需分配）；普通用户按分配的路由组过滤 `/api/menu` 返回的菜单，改授权即时生效（无需重登）。
- **管理接口（仅超级管理员，403 守卫）**：GET /api/perm/overview、POST /api/perm/roles/{code}/routes、POST /api/perm/users、POST /api/perm/users/{id}/role、POST /api/perm/users/{id}/password。
- **前端**：新增 /perm 权限管理页（路由表/角色授权/账户管理/建号）；登录页移除注册入口，/register 显示关闭提示；侧边栏菜单由后端按角色过滤。
- 改库前备份：data/backup-before-rbac.sql。
## B/C 拆分（✅ 阶段 0-5 全部完成，2026-08-25）

阶段0（nginx :80 前缀代理 + :3010/:3020 壳应用）已完成并归档于 [ops/BC-SPLIT-P0.md](ops/BC-SPLIT-P0.md)。

**阶段1：C 端独立身份体系（✅ 已完成，2026-08-24）**

- 新表 `c_users` / `c_user_groups`（仅 CREATE TABLE IF NOT EXISTS + 默认组种子，现有表零改动；改前备份 `data/bc-p1-dump.sql`）。
- **Token 隔离**：C 端会话 Cookie `notelab_c_session`，token = base64url(`c.<uid>.<exp>.<hmac>`)，**4 段**；
  B 端 `notelab_session` 保持 3 段格式不变，`parseToken` 只认 3 段、`parseCToken` 只认 4 段且首段为 `c`，
  两端即使 uid 数字相同也互不认（已实测）。签名算法与密钥同 B 端（HMAC-SHA256，SECRET_KEY 复用）。
- **C 端认证**：`/api/c/auth/login|me|logout|register`。register 受开关 `C_REGISTER_OPEN` 控制，默认关闭（403「注册未开放」）；
  登录限流与 B 端同语义（10 次/300s，独立桶 `c-login:<ip>`）；`status='disabled'` 的账号登录 403、已有会话即时失效。
- **B 端管理 C 用户**：`/api/c-admin/users*`（列表 q/group_code/limit/offset、建号 409 去重、改资料/状态、重置密码、删除）、
  `/api/c-admin/groups*`（增删改查，删组校验无成员，默认组 `default` 不可删）。全部要求 B 端登录，路由自动登记进 `perm_routes`（74 条）。
- 验证记录见 PROGRESS.md 末节。

**阶段2：C 端游玩数据域与内容发布（✅ 已完成，2026-08-24）**

- **数据归属（只增不改）**：`trpg_playthroughs` 补 `scope CHAR(1) NOT NULL DEFAULT 'b'` + 索引 `(scope,user_id)`，
  `trpg_scenarios` 补 `published TINYINT NOT NULL DEFAULT 0`；DbSchema 先查 information_schema 再 ALTER，重启幂等；存量数据默认 B 归属，零迁移。改前备份 `data/bc-p2-dump.sql`。
- **B 端发布接口**（登录态即可，路由自动进 perm_routes）：`POST /api/trpg/scenarios/{id}/publish|unpublish`（剧本不存在 404）、
  `POST /api/spire-content/publish|unpublish`（spire 工坊内容快照 ↔ ui_config 顶层键 `spire_published`，合并写保留其它键）。
  B 端剧本列表/详情响应新增 `published` 字段（原有字段与键序不变）。
- **C 端游玩接口** `/api/c/trpg/*`（CAuthUtil 守卫，复用 TrpgService 引擎）：仅 `published=1` 的剧本可列表/详情/开局；
  存档一律 `scope='c' AND user_id=当前C用户`，他人存档 404；剧本下架后存量存档可继续游玩，仅新开局受限。
  B 端 `/api/trpg/plays` 系列同步补 `scope='b'` 过滤（存量默认 'b'，行为等价）。
- **C 端配置接口（匿名）**：`GET /api/c/config/background`（ui_config 的 background 节点，空时回退默认）、
  `GET /api/c/spire/content`（已发布内容，未发布返回空三数组）——C 端外壳首屏与登录页免登录可用。
- **隔离性实测**：构造 B 用户 uid 恰等于 C 用户 uid 的对抗用例，两端列表/详情/choose/删除互不串（详见 PROGRESS.md）。
- 验证记录见 PROGRESS.md 末节；/vs（吸血鬼幸存者）为纯前端，本阶段零改动。

**阶段3：C 端前端 notelab-c（✅ 已完成，2026-08-25）**

- /root/notelab-c（:3010，pm2 `notelab-c`）：消费级「NoteLab 游戏中心」——新外壳（顶部导航 + 移动端底部 Tab +
  登录态下拉）+ 落地页 + 登录页 + TRPG/爬塔/吸血鬼幸存者三游戏（引擎与 myapp 零改动，接口改 /api/c/*）。
- 登录守卫 RequireAuth：401 → 记路径 → /login → 登录回跳；C 端背景匿名拉取 /api/c/config/background。
- 详见 [ops/BC-SPLIT-P3.md](ops/BC-SPLIT-P3.md)。

**阶段4：B 端前端 notelab-b（✅ 已完成，2026-08-25）**

- /root/notelab-b（:3020，pm2 `notelab-b`，basePath=/admin）：antd 管理后台，21 路由（myapp 19 页 1:1 + 旧跳转 +
  新增 /c-users C 端用户管理）；SSE 消费代码与 myapp 逐字节一致；英语发音保留 /admin/api/tts（Kokoro+edge-tts）。
- trpg-gen 列表新增发布/取消发布列；spire-editor 新增「发布到 C 端」；/ui 页新增 C 端背景说明。
- 详见 [ops/BC-SPLIT-P4.md](ops/BC-SPLIT-P4.md)。

**阶段5：全量回归与收尾（✅ 已完成，2026-08-25）**

- 全量回归矩阵（B 端 21 项 / C 端 18 项 / 隔离对抗 / 旧链路 / 进程）全部通过，见 PROGRESS.md 末章。
- 测试数据清理归零（本系列 ctest/bctest 前缀；ctest1 作为回归 fixture 保留）。
- 一页纸总览：[ops/BC-SPLIT-SUMMARY.md](ops/BC-SPLIT-SUMMARY.md)（入口、端口、DB 变更、接口清单、遗留事项、回滚速查）。
- **入口**：`http://117.72.32.87/`（C 端）与 `http://117.72.32.87/admin`（B 端），:80 为唯一推荐入口。
- Python 版（:8000）与旧 myapp（:3000/:3001）观察期保留，退役/停用由用户决定（见 SUMMARY 遗留事项）。

## 每日英语翻译练习（✅ 2026-09-22 上线）

三仓联动：本仓提供接口与调度，B 端 `/admin/translate` 管理句子库，C 端 `/games/translate` 供用户练习。

### 玩法
- 管理员在 B 端预建「句子组」并入队（`status=queued`）；**每天 0 点**定时任务取队首一组激活为当天内容（`status=used` + `activated_date=当天`）。
- 句子分 **3 阶梯**（1 简单 / 2 中等 / 3 困难，建议每阶 3-5 句，中文原句 10-50 字）。
- C 端用户逐句提交英文译文 → 大模型判分：**是否准确 + 0-100 分 + 修正译文 + 中文讲解 + 逐点错误标注**。
- **去重覆盖**：同一 (C 端用户 + 日期 + 句子) 只保留一条记录，重复提交覆盖旧判分（`uk_user_sentence_date` 唯一键 + `INSERT ... ON DUPLICATE KEY UPDATE`）。
- 句子入库三通道：手动加句 / 批量导入（按中文句末标点 `。！？；…!?;` 与换行切分，trim、去空、去重、过滤过短，超长仅提示仍入库）/ AI 批量生成（场景 + 提示词 + 每阶数量）。

### 数据模型（三张新表，纯只增）
`DbSchema.translateSchema()` 在启动时 `CREATE TABLE IF NOT EXISTS`，重启幂等，不改/删任何现有表：

| 表 | 用途 | 关键约束 |
|---|---|---|
| `en_tr_groups` | 句子组（标题/状态/激活日期/来源/场景/备注） | `idx_status`、`idx_actdate` |
| `en_tr_sentences` | 句子（group_id/tier/sort_order/zh_text/ref_en） | `idx_group(group_id,tier,sort_order)`、外键 `fk_entr_s_group ON DELETE CASCADE` |
| `en_tr_submissions` | C 端提交 + 判分结果 | **`uk_user_sentence_date(c_user_id,sentence_id,submit_date)`**、`idx_user_date`；不对 c_users 建外键（避免耦合） |

全部 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。持久层沿用既有范式：`model/entity/EnTr*` + `mapper/EnTr*Mapper` + `dao/TranslateDao`（静态门面、Map 出口）。

### 接口清单
**C 端**（`TranslateController`，前缀 `/api/translate`，`CAuthUtil` 认 `notelab_c_session`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/translate/today` | 当天激活组 + 分阶句子 + 当前用户既有提交（回填）；当天无激活组时 `group:null`、`sentences:[]`（不报错） |
| POST | `/api/translate/submit` | body `{sentence_id, en_text}`；校验登录/非空/≤2000 字/属于当天激活组 → 判分 → upsert → 返回判分 JSON；判分失败 502 `{error:"判分失败，请重试"}` |
| GET | `/api/translate/history?date=YYYY-MM-DD` | 当前用户某天提交列表（date 缺省为今天，非法日期 400） |

**B 端**（`TranslateAdminController`，前缀 `/api/admin/translate`，`AuthUtil` + RBAC 页面权限码 `page:/translate`，无权 403）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/groups` | 组列表（title/status/sentence_count/activated_date/source/created_at）+ tiers + queued + today |
| POST | `/groups` | 新建组 `{title, scenario?, note?}` → `status=draft` |
| GET | `/groups/{id}` | 组详情 + 分阶句子 + tier_counts |
| PUT | `/groups/{id}` | 改元信息 / 改状态（draft↔queued）/ 手动设 `activated_date` 强制发布（传空串清空并退回草稿） |
| DELETE | `/groups/{id}` | 删组（句子级联删；提交记录保留，JOIN 后 zh_text 为 null） |
| POST | `/groups/{id}/sentences` | 手动加句 `{zh_text, tier, ref_en?, sort_order?}`（sort_order 缺省自动排到该阶梯末尾） |
| PUT | `/sentences/{sid}` | 改单句（缺省字段不改） |
| DELETE | `/sentences/{sid}` | 删单句 |
| POST | `/groups/{id}/import` | 批量导入 `{text, tier}` → 返回 `{imported, skipped, long_count, sentences}` |
| POST | `/groups/{id}/generate` | AI 生成 `{scenario, prompt, counts:{t1,t2,t3}}` → 返回 `{generated, skipped, sentences}`（限流 6 次/300s，同 IP） |
| POST | `/groups/{id}/queue` | 置 `queued`（空组 400、已激活 400） |
| POST | `/activate-today` | 手动补跑当日激活（与定时任务同一逻辑，幂等） |

### 定时任务（本仓首次启用 Spring Scheduling）
- `common/SchedulingConfig`：`@EnableScheduling`（此前全仓无 `@Scheduled`）。
- `scheduler/TranslateScheduler`：`@Scheduled(cron = "0 0 0 * * ?")` 每天 0 点 → 取 `status='queued'` 中 `created_at` 最早的组置 `used` + `activated_date=CURDATE()`，打日志；无 queued 组则打日志跳过。
- **幂等**：当天已有 `used` 组则跳过；激活走 CAS（`UPDATE ... WHERE id=? AND status='queued'`，受影响行数=1 才算成功），重复触发/手动补跑不会激活第二个组。
- pm2 为 fork 单实例，`@Scheduled` 不会多实例重复触发。

### 大模型（`QwenClient.complete`，非流式，只输出 JSON）
- **判分**提示词要求只输出 `{"accurate","score","corrected","explanation","errors":[{"type","original","suggestion","note"}]}`；
  明确「允许合理多样译法，`ref_en` 仅供参考不是唯一标准」；译文准确时 `accurate=true`、`errors=[]`、给高分。
- **出题**提示词要求只输出 `[{"tier","zh_text","ref_en"}]`，按阶梯分难度、中文 10-50 字、贴近场景。
- 解析走 `JsonUtil.parse`（容忍三反引号代码块围栏与前后赘述）；上游异常/解析失败一律捕获 → `{error:"判分失败，请重试"}` / `{error:"生成失败，请重试"}`（HTTP 502），不冒泡 500。
- 密钥仅由 `AppConfig.qwenKey()/qwenApiKeys()` 从环境变量或 `.env` 读取，不硬编码、不打印。

### 前端入口
- B 端：`/admin/translate`（菜单「工具箱 → 翻译句子库」，`PageRoutes` 已登记 `page:/translate`，超管默认可见，可在 `/perm` 分配角色）。
- C 端：`/games/translate`（`notelab-c` 的 `basePath=/games`；桌面导航「每日翻译」入口，未登录跳 `/login`）。

### 验证（2026-09-22）
`SHOW TABLES LIKE 'en_tr_%'` 三表已建；0 点定时任务实测触发（日志 `每日翻译练习激活任务：{... reason=队列为空（无 queued 组），跳过}`）；
入队 → 激活变 `used` + `activated_date=当天` → 重复调用返回「当天已有激活组，跳过」；
判分实测（准确 100 分 / 时态错误 60 分带逐点标注）；同句提交 3 次仅 1 行且被覆盖（`created_at` 不变、`updated_at` 刷新）；
非超管 B 端账号 403；`mvn -DskipTests package` ✅、`notelab-b`/`notelab-c` `npm run build` ✅、页面 curl 200 ✅。
验收测试数据已清理归零（备份 `data/backup/en_tr_acceptance_data_*.sql`）。详见 PROGRESS.md 末节。
