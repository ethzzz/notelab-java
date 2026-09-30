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
| 线上入口 | https://haolo.cloud/api/* |
| GitHub | `git@github.com:ethzzz/notelab-java.git`（main） |
| 技术栈 | Spring Boot 3.4.5 / Java 17 / MyBatis-Plus 3.5.9 / poi-ooxml 5.2.5（jar 名 `target/notelab-java.jar`） |

## 构建与发布（本地改 → 服务器从 git 同步）
```bash
# 本地：改完提交推送
git push origin main
# 服务器：同步 + mvn package + 重启 + 探活一条命令搞定
ssh myapp "/root/notelab-java/ops/sync-deploy.sh notelab-java"
```
- **不要在 `/root/notelab-java` 里手改代码**——服务器是只读部署目标；脚本发现工作区脏会直接拒绝执行。完整行为与参数见根 `AGENTS.md`「开发流程」。
- **`ops/sync-deploy.sh` 这个脚本本身就在本仓**（`E:\code\NoteLab\notelab-java\ops\sync-deploy.sh`），服务器执行的是仓内副本；改它要走「本地改 → push → 对 `notelab-java` 同步」自己这套流程。
- 脚本只在**有变更**时构建；仅文档变更自动跳过（强制构建加 `--build`）；**构建失败不会重启服务**，老进程继续服务。
- Maven 镜像已配在 `/root/.m2/settings.xml`，**不要改镜像**。
- 本机（Windows）不要指望 `mvn` 能跑：shell 环境不完整，构建统一在服务器执行（由上述同步脚本代劳）。
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

## 接口级权限门禁：默认拒绝（`e8b6ad3`，2026-09-27）

页面守卫只挡「看不见 / 打不开页面」，**接口一直是裸的**：此前 `perm_routes` 里的 `api:*` **只登记、不校验**（全仓唯一的 `WebMvcConfigurer` 只配了 CORS，没有拦截器），能不能调完全取决于「那个 Controller 有没有手写 `isSuperAdmin`」。实测同一个 user 账号：`/api/perm/overview` → 403（手写了）、`/api/c-admin/users` → **200**（能拉到全部 C 端用户）。

现在由 `common/ApiPermInterceptor`（注册到 `/api/**`）+ 规则类 `common/PermGuard` 补齐。

**判定顺序**（见 `PermGuard.denyReason`）

| 序 | 条件 | 结果 | 为什么 |
|---|---|---|---|
| 1 | `OPTIONS` | 放行 | 拦下会让跨域调用全灭 |
| 2 | 未登录 / 非 B 端会话 | 放行 | 本次**只隔离已持有 B 端会话**的用户；C 端与匿名沿用各 Controller 自有判断 |
| 3 | `super_admin` | 放行 | 与菜单树 `allowedPagePaths` 同口径（超管在 `perm_role_routes` 里是 0 行，权限不靠数据） |
| 4 | `/api/c/**` | 放行 | C 端是另一套身份体系 |
| 5 | `login/logout/me/register/auth/verify/menu` | 放行 | 会话基础；自身安全由 Controller 的 `unauth()` 保证 |
| 6 | 其余 | **没有对应 `api:<pattern>` 就 403** | 路由表里查不到也算未登记 → 同样拒绝 |

路径匹配用 `AntPathMatcher`，**越具体的 pattern 优先**（`/api/perm/roles/{code}` 胜过 `/api/perm/roles`）。

**受限清单**（`PermGuard.RESTRICTED_PREFIXES`，只给 super_admin）：`/api/perm`、`/api/c-admin`、`/api/ui-config`。
⚠️ `/api/c-admin` 名字像 C 端，实际是 **B 端管理员管理 C 端用户**，别因为 `c` 开头就放进 C 端豁免。

**三个操作性事实**

- **新增 Controller 后必须重启**：路由只有启动时会采集进 `perm_routes`，在那之前门禁按「未登记」处理 → 403。这与原有机制一致，不是新约束。
- `PermService.registerAllRoutes` 第 5 步 `syncUserApiPerms` 每次启动**幂等重算** `user` 组的 `api:*`（非受限全给、受限全不给），所以**不需要上线前手工勾选** —— 默认拒绝下"恰好没勾"等于"全禁止"。⚠️ 代价：在「角色组管理」界面上手工勾的 `api:*` 会被覆盖，想长期调整请改 `RESTRICTED_PREFIXES` 或那一节的规则。该方法**只动 `api:*`**，管理员调过的 `page:*` 原样保留；也**只处理 `ROLE_USER`**，自建角色组仍需管理员自己勾。
- 各 Controller 里的 `isSuperAdmin` / 登录判断**全部保留**，不做替换 —— 刻意保留双保险，权限码配错时那层还拦得住。

**验证**：`node .sync/verify-perm-guard.js`（user 模式）/ `... admin`（超管模式，需先把账号临时提权、**测完务必降回**）。
⚠️ 断言写成「不是 200」而不是写死 403：路径存在但 method 不匹配时 Spring 会先回 **405**（handler 都没找到，拦截器根本没介入），405 同样是"没放行"，判成失败是在制造假警报。同理别拿 `POST /api/perm/roles` 这类真会创建/删除的操作去补测。

2026-09-26 的实例：爬塔工坊由**单页 4 个 Tab 拆成 6 个子页**（`spire-editor` → `spire-cards` / `spire-chars` / `spire-skills` /
`spire-assets` / `spire-map` / `spire-access`，菜单树里包成新分组 `gc_spire`）。两处常量都已同步补齐；
`notelab-b/next.config.ts` 把裸 `/spire-editor` 307 到 `/spire-editor/cards`（放在 config 层，**不经过页面守卫**）。
⚠️ 历史 role 里的 `page:/spire-editor` 行会**自然失效**（不在 `PAGE_ROUTES` → 不再开通任何路径），非超管角色需到
`/user/roles` 重新勾选这 6 条。

⚠️ **这层不是安全边界**（`(admin)` 组无 middleware）：它拦的是「直接敲 URL 误入页面」，
**不拦「绕过 UI 直调接口」** —— `/api/c-admin/*` 仍只要求 B 端登录、不要求超管。
需要真正的边界时得在服务端加校验。（2026-09-25 前这里写的是"B 端没有页面级守卫"，那个说法已过时。）

## 爬塔尖塔内容的存储与发布：一个 `spire` 键、两组快照

爬塔工坊的内容**全部塞在 `ui_config.config` 的单个 `spire` 键里**（`mediumtext`，16MB 上限），
`spire_published` 是发布快照。`SpireContentController.save` 是**整包覆盖写** —— 少传任何一个切片就会把它清空。

| 切片 | 内容 | 出处页面（B 端） |
|---|---|---|
| `cards` / `characters` / `skills` | 自定义卡 / 角色 / 技能模板 | `/admin/spire-editor/{cards,chars,skills}` |
| `charAccess` | `{C 端用户组码: [角色 id...]}` | `/admin/spire-editor/access` |
| `assets` | `{槽位 key: C 端素材路径}`（路径**带 `/games` 前缀**） | `/admin/spire-editor/assets` |
| `maps` | `{defaultId, packs:[{id,name,params,acts:[{act,layers,nodes}]}]}` | `/admin/spire-editor/map` |

- **`sanitizeAssets` 的「空值丢弃」不是洁癖**：B 端的脏标记是「整份文档指纹比对」，
  如果后端把空串存下来而前端丢弃，保存往返一次指纹就变，页面会永远显示「有未保存改动」。**两边必须同口径。**
- **`sanitizeMaps` / `sanitizeAct` 刻意只做形状与体积，不做地图语义**：不交叉 / 无死路 / 唯一 BOSS 这些裁决权
  在 B 端生成器（生成时）与 C 端加载器（读入时）。后端若也判一遍，就变成第三套规则真相源。
- `MAX_SPIRE_CHARS` 已从 200KB 提到 **1MB**（一套 3 幕的自包含节点表约 12KB，要能存几套方案）。
- `spireOf()` 是 GET 与 publish 快照的**共用出口**，加切片要在这里一起加，否则发布时会被漏掉。

### `GET /api/spire-assets/catalog`：后端扫盘下发素材候选

B 端浏览器**读不到 C 端仓库**（两个独立 Next 应用、两个 basePath），而素材的真相源就是
C 端 `public/spire` 下的真实文件。后端与 C 端同机部署，于是直接扫盘：
`AppConfig.spireAssetRoot()`（默认 `/root/notelab-c/public/spire`）→ 分组清单 + `manifest.json` 元数据。

- **不接受任何路径入参**（因此无穿越风险）；深度上限 3、条目上限 2000、跳过点开头的隐藏项。
- 目录不存在时返回 `available:false` + warning，**不抛错** —— 本地开发或 C 端未部署时不该把 B 端页面打挂。
- 加素材的方式是**把文件放进 C 端 `public/spire` 并重新部署 C 端**，然后刷新 B 端页面；这份接口是只读列举。

⚠️ **`Map.of(...)` 最多 10 组键值**（20 个参数）：`KIND_BY_EXT` 原来有 11 组，
报错是 `no suitable method found for of(...)` 而不是"参数太多"，很容易看错。条目一多就用构造器去填。

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
- `ops/LOCAL-SYNC-GUIDE.md` —— **已废弃**（旧的反向同步说明，仅历史参考；现行流程见根 `AGENTS.md`）
- `ops/daily-iteration/` —— 每日巡检 + 数据库备份脚本与说明
- `ops/nginx-notelab.conf` —— nginx 站点配置备份
