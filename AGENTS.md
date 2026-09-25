# notelab-java —— 后端（Spring Boot）

> 本文件描述**当前状态**。功能清单、接口契约、验收记录见本仓 `README.md` 与 `PROGRESS.md`；全局信息见根 `../AGENTS.md`。
> 历史任务简报（2026-08「把 Python 版重写成 Spring Boot」）已归档到 `ops/ARCHIVE-java-rewrite-brief-2026-08.md`——**那是历史，任务早已完成，不要照它去重写任何东西。**

## 定位
NoteLab 唯一在用的 API 后端。Python FastAPI 版（`/root/notelab`，原 :8000）已于 2026-08-07 完成切流、2026-08-25 停用。

| 项 | 值 |
|---|---|
| 服务器目录 | `/root/notelab-java`（含 git 历史） |
| pm2 进程 | `notelab-java` |
| 端口 | **127.0.0.1:8001**（只绑本地，写死在 `src/main/resources/application.properties`） |
| nginx | `location /api/` 与 `location = /api` 直达本服务；SSE 已配 `proxy_buffering off` |
| 线上入口 | http://117.72.32.87/api/* |
| GitHub | `git@github.com:ethzzz/notelab-java.git`（main） |
| 技术栈 | Spring Boot 3.4.5 / Java 17 / MyBatis-Plus 3.5.9 / poi-ooxml 5.2.5（jar 名 `target/notelab-java.jar`） |

## 构建与发布
```bash
ssh myapp
cd /root/notelab-java && /usr/bin/mvn -B -DskipTests package 2>&1 | tail -30
pm2 restart notelab-java
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8001/api/menu   # 探活
```
- Maven 镜像已配在 `/root/.m2/settings.xml`，**不要改镜像**。
- 本机（Windows）不要指望 `mvn` 能跑：shell 环境不完整，构建统一在服务器执行。
- 改完必须 curl 自验并把结果记进 `PROGRESS.md`。
- ⚠️ **本服务没有 `/api/health`**（`src/` 全量检索无此端点，请求它只会拿到 `{"detail":"Not Found"}`，别据此误判服务未启动）。探活用 `/api/menu`，**返回 401 即正常**（未带会话）；`ops/daily-iteration/daily-check.py` 也是这么探的（接受 200/401/403）。

## 代码结构与约定
```
src/main/java/com/notelab/
├── common/   10 个（含 RowUtil、AppConfig）
├── controller/ 26 个
├── dao/      15 个（静态门面 DAO：全部 public static 方法）
├── infra/     2 个（QwenClient、QwenKeys）
├── mapper/   21 个（MyBatis-Plus Mapper）
├── model/    23 个（含 model/entity）
├── scheduler/ 1 个（TranslateScheduler —— 每日翻译 0 点激活）
└── service/   6 个
```

- **持久层契约（改动时的硬约束）**：MyBatis-Plus + **静态门面 DAO + `RowUtil` 的 Map 出口**。上游（controller/service）拿到的始终是 `Map<String,Object>`，不是实体对象。当初从手写 JDBC 迁移时就是靠这条「上游零改动」完成的，**不要顺手把它重构成返回实体的风格**。
- `application.properties` 关了静态资源映射并开了 `throw-exception-if-no-handler-found`，为的是让未匹配路径返回与 FastAPI 一致的 `{"detail":"Not Found"}`——**别关掉**。
- 错误响应格式、字段命名一律对齐 FastAPI 原版（`@RestControllerAdvice` 统一处理）。

## 配置加载顺序（排查配置问题先看这里）
`AppConfig` 的优先序：**进程环境变量 > 本目录 `.env` > `/root/notelab/.env` > 代码默认值**。

⚠️ `.env` 是按**进程 cwd** 相对加载的（`Paths.get(".env")`）。所以用 jshell 之类的方式直连测试时，**必须先 `cd /root/notelab-java`**，否则只会加载到 Python 版那份 `.env`（典型症状：候选 key 数量不对）。

## LLM 调用
- `infra/QwenClient`（`complete` 非流式 / `streamChat` 流式）+ `infra/QwenKeys`（多 key 轮换）。
- 网关为 OpenAI 兼容协议，靠 `QWEN_BASE_URL` / `QWEN_MODEL` / `QWEN_API_KEYS`（逗号分隔）三个环境变量定位。
- key 失效判定在 `QwenKeys.unusableReason(status, body)`：**401 无效 / 429 配额耗尽 / 403 无该模型权限**，命中即 `markUnusable` 换下一把；标记 30 分钟后自动重试。新增判定请扩展这个方法，不要在调用方散写判断。
- 换 key **无需改代码**：改 `/root/notelab-java/.env` 的三个变量 → `pm2 restart notelab-java`（进程内的不可用标记随重启清空）。

## 双身份体系（别混淆）
| 端 | 用户表 | 会话机制 |
|---|---|---|
| B 端（管理台） | `users` | Cookie `notelab_session`（HMAC，与 Python 版兼容） |
| C 端（玩家端） | `c_users` / `c_user_groups` | 4 段 token `c.<uid>.<exp>.<sig>`，Cookie `notelab_c_session` |

两端**互不互认**（已对抗验证）。同名数字 uid 在两端的数据必须隔离（`trpg_playthroughs.scope` 区分 `b`/`c`）。C 端用户由 B 端通过 `/api/c-admin/*` 管理。另有一个旁路端点 `GET /api/auth/verify`（供 nginx `auth_request` 给 ai-lab 做 SSO 门禁，200 时透传 `X-Auth-User`）。

## 新增后台页面：必须同时改两处常量（否则菜单不出现）
菜单**不来自前端页面目录、不来自数据库**，B 端侧边栏的唯一来源是 Java 硬编码常量树 `model/MenuTree.java` 的 `MENUS`；`/ui` 界面配置只能覆盖**已有 key** 的 name/icon，**没有新增菜单节点的能力**。而"能否分配给角色"由另一份常量决定：`model/PageRoutes.java` 的 `PAGE_ROUTES` → `PermService.registerAllRoutes` 启动时 upsert 成 `page:<path>` 进 `perm_routes` → `/api/menu` 的 `buildNodes` 按 `allowed` 过滤叶子、剪掉空分组。

| 只改哪一处 | 结果 |
|---|---|
| 只加 `MenuTree` | 超管可见（`allowed==null` 不过滤），**普通角色仍看不到**（叶子被 RBAC 过滤掉） |
| 只加 `PageRoutes` | **谁都看不到**（`perm_routes` 里有权限码，但没有菜单节点可渲染） |
| 两处都加 | 正确。但**现有 `user` 角色不会自动获得**——`PermService` 的普通角色默认权限只在 `roleRouteCodes(ROLE_USER)` 为空时写入一次，需去 `/user/roles` 手动勾选 |

`GET /api/menu` 除了 `menu`（菜单树）还下发 **`pages`**（= `PermService.allowedPagePaths(user)`，该账户可进入的页面路径清单，超管为全部），供 notelab-b 的 `(admin)/layout.tsx` 做**页面级守卫**——菜单只负责「看不见」，拦住「直接敲 URL」靠的就是这份清单。`allowedPagePaths` **以 `PageRoutes.PAGE_ROUTES` 为准遍历**再比对权限码，所以数据库里的历史/脏 `page:*` 行不会凭空开通任何路径。改权限逻辑时，两个消费方（菜单可见性、页面守卫）要一起想。

2026-09-25 的实例：`/c-users` 两处都缺（只能靠 `/perm` 页一张 Card 手工跳转）、`/docs` 与 `/user/invites` 只有菜单节点缺页面路由（普通角色永久不可见）；三者已在同轮补齐（`1934629`）。

⚠️ B 端**没有页面级守卫**（`src/` 下无 middleware，`(admin)/layout.tsx` 只守登录）——页面权限是**展示级**的，直接敲 URL 可绕过；`/api/c-admin/*` 也只要求 B 端登录、不要求超管。需要真正的边界时得在服务端加校验。

## 一个容易「误修」的约定
`QwenClient.ModelHttpException` **故意不覆写 `getMessage()`**，只提供 `messageFull()`（完整响应体）与 `messageShort()`（精简原因）。所有捕获点都必须显式选一个并注意语义差异：

| 捕获点 | 用法 |
|---|---|
| `ArenaController` | `messageShort()` |
| `ExtractController` / `RagController` / `ToolboxController` / `TrpgController` | `messageFull()` |

若改用它继承来的 `getMessage()`，前端会拿到 `null`。（2026-09-25 已逐处核对：现有 5 个捕获点全部正确，此处记录是为了避免后人把它当缺陷去"修"。）

## 纪律与禁区
- **共享 MySQL = 生产数据**：任何 DROP / DELETE / 改表结构前先 `mysqldump` 备份。对共享表（尤其 `ui_config`）的写操作就是生产操作。
- 密钥只在服务器 `/etc/environment` 与 `/root/notelab-java/.env`，**严禁硬编码或提交**。`.env` 已在 `.gitignore`；含密钥的备份文件要放到仓库外（如 `/root/env-backups/`，chmod 600）。
- **不动** `/root/notelab`（Python 版源码，只作历史参考）、`/root/myapp`（旧前端）。
- `ops/daily-iteration/` 下的巡检与备份脚本是每日机制的**权威副本**，cron 直接执行仓内脚本，改脚本即改生产行为。

## 相关文档
- `README.md` —— 项目说明与接口清单
- `PROGRESS.md` —— 各阶段验收记录（含 RBAC、切流）
- `ops/BC-SPLIT-SUMMARY.md`、`ops/BC-SPLIT-P6.md`、`ops/BC-SPLIT-P7.md` —— B/C 拆分与后续改造
- `ops/LOCAL-SYNC-GUIDE.md` —— 本地镜像同步说明
- `ops/daily-iteration/` —— 每日巡检 + 数据库备份脚本与说明
- `ops/nginx-notelab.conf` —— nginx 站点配置备份
