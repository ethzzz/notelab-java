# NoteLab 认证链路重构：服务端会话表设计

> 日期：2026-10-21
> 状态：自审完成，待用户评审（未实施）
> 范围：notelab-java（B/C 端共用）；不改 B/C 前端

## 1. 背景

当前 B/C 两端都是 **HMAC-SHA256 签名 Cookie + 每次请求回查用户表** 的无状态会话：

- B 端：`notelab_session`，token = `base64url("<uid>.<exp>.<sig>")`（3 段）
- C 端：`notelab_c_session`，token = `base64url("c.<uid>.<exp>.<sig>")`（4 段）
- 两端共用同一个 `SECRET_KEY`，通过段数与 Cookie 名隔离

这套结构能工作，但存在四个实际缺口：

1. **改密/重置密码后旧 token 仍有效**：`CMeController`、`CAdminController`、`PermController` 都只更新 `password_hash`，不失效会话。
2. **登出只清 Cookie**：服务端没有吊销记录，被复制的 token 在过期前一直可用。
3. **B 端没有账号状态**：`users` 表没有 `status`，无法禁用账号；C 端有 `status`，但只靠每次回查，且禁用后旧会话不会被显式清理。
4. **单版本 `SECRET_KEY`**：轮换即 B/C 全员掉线；且 2026-10-08 曾因默认密钥导致可伪造超管会话。

## 2. 目标

- 每个会话可单独吊销，登出真正生效。
- 改密、管理员重置密码、禁用账号时，该用户所有旧会话立即失效。
- B/C 都支持账号禁用状态；B 端补 `status`。
- 增加闲置超时与会话设备列表（后端接口）。
- 加一道 CSRF 防护，且不改 B/C 前端。
- 去掉 HMAC / `SECRET_KEY` 依赖，从结构上消除“默认密钥伪造会话”的风险。
- 保持 `/api/me`、`/api/menu`、`/api/auth/verify` 等外部契约不变，B/C 前端与 nginx SSO 零改动。

## 3. 非目标

- B/C 前端“登录设备”界面（本期只交付后端接口）。
- RBAC、C 端页面级权限调整。
- OAuth、2FA、Passkey、邮箱验证码找回密码。
- 密码哈希迭代升级（见 9.2，留作独立变更）。
- 多实例/Redis 会话共享（当前单实例 + MySQL 足够）。

## 4. 总体方案

改为 **不透明会话 ID + 服务端会话表**：

```
Cookie 值 = 32 字节 SecureRandom → base64url（43 字符，无填充）
DB 只存 SHA-256(token) 的 64 位十六进制
每次请求：token → hash → 按 (token_hash, side) 查 auth_sessions
          → 校验未吊销/未过期/未闲置 → 查 users / c_users → 返回用户
```

- 不再使用 HMAC，不再需要 `SECRET_KEY`。
- B/C 共用一张 `auth_sessions` 表，用 `side`（`b` / `c`）隔离；Cookie 名仍分开。
- 会话的真相源是 MySQL；重启 Java 不会掉登录态。

## 5. 数据模型

### 5.1 `auth_sessions`

```sql
CREATE TABLE IF NOT EXISTS auth_sessions (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  token_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  side           CHAR(1)  CHARACTER SET ascii NOT NULL,
  user_id        BIGINT   NOT NULL,
  auth_method    VARCHAR(16) NOT NULL DEFAULT 'password',
  created_at     DATETIME NOT NULL,
  last_seen_at   DATETIME NOT NULL,
  expires_at     DATETIME NOT NULL,
  revoked_at     DATETIME NULL,
  revoked_reason VARCHAR(32) NULL,
  user_agent     VARCHAR(255) NULL,
  ip_hash        CHAR(16) CHARACTER SET ascii NULL,
  UNIQUE KEY uk_token_hash (token_hash),
  KEY idx_user (side, user_id, revoked_at),
  KEY idx_expires (expires_at),
  KEY idx_revoked (revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

字段语义：

| 字段 | 说明 |
|---|---|
| `token_hash` | `SHA-256(token)` 的小写十六进制；**不存明文 token** |
| `side` | `b` = B 端，`c` = C 端；查询必须带 side |
| `user_id` | 对应 `users.id` 或 `c_users.id`，不建外键（与现有表风格一致） |
| `auth_method` | `password` / `external` / `register` |
| `last_seen_at` | 最后活跃时间；最多每 60 秒更新一次 |
| `expires_at` | 绝对过期时间；Cookie 的 `Max-Age` 与它一致 |
| `revoked_at` / `revoked_reason` | 吊销时间与原因；保留 7 天后清理 |
| `user_agent` | 截断到 255 字符，供设备列表展示 |
| `ip_hash` | 复用 `EventRecorder.ipHash(request)`（HmacSHA256(ip + `ANALYTICS_IP_SALT`) 前 16 位），不存明文 IP |

`revoked_reason` 取值：`logout` / `logout_all` / `password_change` / `disabled` / `deleted` / `idle` / `admin` / `cap`。

### 5.2 `users.status`

```sql
ALTER TABLE users ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
```

- 新建库：`users` 的建表语句直接带 `status VARCHAR(20) NOT NULL DEFAULT 'active'`；存量库：`DbSchema.hasColumn("users", "status")` 探测后 ALTER，幂等。
- 取值只允许 `active` / `disabled`。
- `c_users.status` 已存在，不改。

### 5.3 时间语义

- 所有会话时间（`created_at` / `last_seen_at` / `expires_at` / `revoked_at`）由 **Java 侧** `LocalDateTime.now()` 生成，SQL 中不使用 `NOW()` / `CURRENT_TIMESTAMP`。
- 过期与闲置判定同样在 Java 侧比较；清理任务的 cutoff 由 Java 算好后作为参数传入，避免 JVM 与 MySQL 时区不一致导致会话提前失效或清理滞后。
- 时间列统一 `DATETIME`（秒精度），与现有表风格一致。

## 6. 组件与职责

| 组件 | 类型 | 职责 |
|---|---|---|
| `common/SessionToken.java` | 新增，纯函数 | 生成 32 字节 token、base64url 编解码、SHA-256 哈希、格式校验 |
| `common/SessionPolicy.java` | 新增，纯函数 | 按 side/auth_method 计算绝对 TTL、闲置阈值、是否过期/闲置 |
| `mapper/AuthSessionMapper.java` | 新增 | MyBatis-Plus `BaseMapper<AuthSession>`；无 XML（与现有 21 个 mapper 同目录） |
| `dao/SessionDao.java` | 新增，静态门面 | 会话插入、按 hash 查询、touch、吊销、列出、计数、清理；Map 出口 |
| `dao/DaoSupport.java` | 改造 | 构造器注入 `AuthSessionMapper` 并暴露 `authSession()`，与现有 mapper 同一模式 |
| `service/SessionService.java` | 新增，静态服务 | 创建会话、解析会话、吊销当前/全部、轮换、设备列表、容量控制（与 `PermService` 同风格，不用 Spring Bean） |
| `common/Session.java` | 改造 | 收窄为 Cookie/请求门面：`currentUser` / `currentUserC` 签名不变，内部调 `SessionService` |
| `common/CsrfInterceptor.java` | 新增 | 对 `/api/**` 非安全方法做 Origin / Sec-Fetch-Site 校验 |
| `common/WebConfig.java` | 改造 | 注册 `CsrfInterceptor`（先）与 `ApiPermInterceptor`（后）；CORS 复用统一来源列表 |
| `common/PermGuard.java` | 改造 | 新增会话端点**前缀豁免**，否则非超管 B 用户访问 `/api/auth/sessions/{id}` 会被 403 |
| `common/CPermGuard.java` | 改造 | 同上（C 端）；新接口对既有 C 用户组默认 403，必须加入豁免（详见 8.4） |
| `scheduler/SessionCleanupScheduler.java` | 新增 | 每天 4:50 清理过期/已吊销超过 7 天的行 |
| `common/AppConfig.java` | 改造 | 新增会话 TTL/闲置/容量/允许来源配置；移除 `secretKey` 相关方法 |
| `Bootstrap.java` | 改造 | 移除 `SECRET_KEY` 硬阻断；保留 analytics salt 告警；日志改为输出会话策略 |
| `dao/DbSchema.java` | 改造 | 建 `auth_sessions`；补 `users.status` |
| `controller/AuthController.java` | 改造 | B 登录/外部登录创建会话；登出吊销当前；新增会话列表/单端吊销/logout-all |
| `controller/CAuthController.java` | 改造 | C 登录/注册创建会话；登出吊销当前；新增会话列表/单端吊销/logout-all |
| `controller/CMeController.java` | 改造 | C 自助改密后吊销全部旧会话并轮换当前会话 |
| `controller/CAdminController.java` | 改造 | C 重置密码/禁用/删除时吊销目标会话 |
| `controller/PermController.java` | 改造 | B 重置密码/删除时吊销目标会话；新增 B 状态接口 |
| `service/PermService.java` / `dao/UserDao.java` / `model/entity/User.java` | 改造 | B 用户状态读写、活跃超管计数；`User` 加 `status` 字段，`UserDao` 投影列补 `status` |

## 7. 生命周期与流程

### 7.1 会话策略

| 场景 | `auth_method` | 绝对有效期 | 闲置超时 |
|---|---|---|---|
| B 端密码登录 | `password` | 7 天 | 12 小时 |
| B 端外部一键登录 | `external` | 1 天 | 1 小时 |
| C 端登录 | `password` | 30 天 | 7 天 |
| C 端邀请码注册 | `register` | 30 天 | 7 天 |

配置项（`AppConfig`，均可由环境变量覆盖）：

```
SESSION_TTL_B_SECONDS           默认 604800    (7d)
SESSION_IDLE_B_SECONDS          默认 43200     (12h)
SESSION_TTL_C_SECONDS           默认 2592000   (30d)
SESSION_IDLE_C_SECONDS          默认 604800    (7d)
SESSION_TTL_EXTERNAL_SECONDS    默认 86400     (1d)
SESSION_IDLE_EXTERNAL_SECONDS   默认 3600      (1h)
SESSION_TOUCH_SECONDS           默认 60
SESSION_MAX_PER_USER            默认 20
SESSION_MAX_EXTERNAL            默认 200
```

- 数值配置必须 > 0；非法值回落默认并 warn，不要把用户的登录路径配成永久失败。

### 7.2 创建会话

1. 登录校验通过（密码正确、账号 `active`）。
2. 生成 token：`SecureRandom` 32 字节 → base64url 无填充。
3. 计算 `token_hash`，插入 `auth_sessions`：
   - `created_at = last_seen_at = now`
   - `expires_at = now + 绝对 TTL`
   - `user_agent` 截断、`ip_hash` 脱敏
4. 若 `token_hash` 唯一键冲突，重试一次；仍失败则 500，不下发 Cookie。
5. 设置 Cookie：`Max-Age = 绝对 TTL`。
6. 执行容量控制：按 `(side, user_id)` 统计活跃会话，普通用户最多 `SESSION_MAX_PER_USER`（20）个，`external` 角色最多 `SESSION_MAX_EXTERNAL`（200）个；超出时吊销 `created_at` 最早的（`reason=cap`）。容量控制失败只记 warn，不阻断登录。

### 7.3 每次请求解析

```
读对应 Cookie：
  B -> notelab_session
  C -> notelab_c_session
  ↓
token 长度/字符集校验（≤128，[A-Za-z0-9_-]+）
  ↓
SHA-256(token) -> 按 (token_hash, side) 查 auth_sessions
  ↓
不存在 / revoked_at 非空 / expires_at <= now -> 401
  ↓
now - last_seen_at > 闲置阈值
  -> 吊销（reason=idle）-> 401
  ↓
按 user_id 查 users / c_users
  ↓
用户不存在或 status != 'active'
  -> 吊销该用户全部会话（reason=disabled/deleted）-> 401
  ↓
last_seen_at 超过 60 秒才更新（节流写）
  ↓
返回用户
```

- `Session.currentUser` / `currentUserC` 的签名保持不变。
- 返回的仍是原 DAO 的**整行 Map**（`CMeController.updatePassword` 依赖其中的 `password_hash` 校验原密码），不要借机收窄投影。
- 同一请求内缓存解析结果（`request.setAttribute`）：`ApiPermInterceptor` 与 Controller 重复调用只查一次库；缓存只存活于单次请求，不影响吊销的跨请求生效。
- 另提供 `Session.currentContext(request)` 供会话管理接口使用，返回 `{session, user}`，不把 session id 混进 `/api/me` 响应。
- 数据库查询异常向上抛（500），**不静默当匿名**；这是 fail-closed。
- touch 用条件更新（`UPDATE ... WHERE id=? AND last_seen_at < cutoff`），避免并发请求重复写同一行。

### 7.4 吊销触发点

| 事件 | 动作 | 失败语义 |
|---|---|---|
| 普通登出 B/C | 吊销当前会话（按 token hash），清 Cookie | 吊销失败 → 500，**不清 Cookie**，允许重试；无会话 → 200 幂等 |
| 退出所有设备 | 吊销该用户全部会话，清 Cookie | 失败 500，不清 Cookie |
| C 自助改密 | **吊销该用户全部会话 → 更新密码** → 新建当前会话 → 下发新 Cookie | 吊销失败 → 500，密码未变、会话仍有效；密码更新失败 → 500，用户已掉线但可用旧密码重登；新会话创建失败 → 清 Cookie，返回 500“密码已修改，请重新登录” |
| B/C 管理员重置密码 | **吊销目标全部会话 → 更新密码** | 吊销失败 → 500“会话吊销失败，密码未修改”；密码更新失败 → 500“密码重置失败，请重试”（目标用户已掉线、密码未变） |
| B/C 禁用账号 | 更新 `status=disabled` → 吊销目标全部会话 | 吊销失败只记 warn；每次请求的 status 检查仍会拦截 |
| 删除用户 | 删除用户 → 删除该用户全部会话行 | 删除会话失败只记 warn；会话解析时查不到用户 → 吊销该用户会话并 401 |
| 闲置超时 | 吊销当前会话（reason=idle） | 失败只记 warn，仍返回 401 |
| 管理员单端踢下线 | 按 session id 吊销，校验 `side + user_id` 归属 | 不存在 → 404；DB 失败 → 500 |
| 容量超限 | 吊销最旧会话（reason=cap） | 失败只记 warn |

> **改密必须先吊销、后写密码**：反序会产生「密码已更新但吊销失败 → 旧会话仍有效」的窗口，正是本次要消除的风险类别。先吊销的失败语义是「用户掉线、密码没变」，最坏只是多重登一次。
>
> 管理员重置**自己**的密码时，自己的当前会话也在吊销范围内——响应返回后即需重新登录。

### 7.5 会话管理接口

B 端：

```
GET    /api/auth/sessions
DELETE /api/auth/sessions/{id}
POST   /api/auth/logout-all
```

C 端：

```
GET    /api/c/auth/sessions
DELETE /api/c/auth/sessions/{id}
POST   /api/c/auth/logout-all
```

列表响应：

```json
{
  "items": [
    {
      "id": 12,
      "created_at": "2026-10-21 10:00:00",
      "last_seen_at": "2026-10-21 10:30:00",
      "expires_at": "2026-10-28 10:00:00",
      "auth_method": "password",
      "user_agent": "Mozilla/5.0 ...",
      "current": true
    }
  ]
}
```

- 只返回活跃会话：未吊销、未过期、未闲置；按 `last_seen_at` 倒序，普通用户最多 20 条，无需分页。
- 不返回 token / token_hash。
- 操作他人会话 id 返回 404，避免枚举。
- 吊销当前会话时同时清 Cookie。
- `external` 角色禁止列出/踢出会话、禁止 `logout-all`（403）；只允许登出自己。原因是多个访客共用同一外部账号，否则会互相踢下线。

### 7.6 B 端账号状态

新增仅超管可用的接口：

```
POST /api/perm/users/{id}/status
Body: { "status": "active" | "disabled" }
```

- `/api/perm` 属于 `PermGuard.RESTRICTED_PREFIXES`，启动登记时自动标记 `super_only`，非超管一律 403。
- `active` / `disabled` 之外的取值 → 400。
- 不允许禁用自己的账号 → 400。
- 不允许禁用最后一名 `active` 超级管理员 → 400。
- 登录时检查状态：禁用账号返回 403“账号已被禁用”，并写 `login_audit` 的 `disabled`。
- 外部一键登录的候选池只取 `status='active'` 的 `external` 账号；全部被禁用时仍返回 503“暂无可用外部账号”。
- 每次请求取用户时再次检查状态（双保险）。
- 本次只交付后端接口；B 端用户管理页的启用/禁用按钮作为后续前端项。

### 7.7 角色/用户组变更

- B `role`、C `group_code` 变更**不吊销会话**：每次请求都重新查用户，权限立即按新角色/用户组计算。
- 会话的 TTL/闲置阈值在创建时按 `auth_method` 固定，角色变更不重算；`external` 的会话管理限制按**每次请求的角色**实时判定。
- 若角色变为 `external`，该会话随后的会话管理接口会被 403 限制。

### 7.8 清理

- 每天 4:50 删除两类行：`expires_at <= now`，以及 `revoked_at IS NOT NULL AND revoked_at <= now - 7d`。
- cutoff 由 Java 计算后作为参数传入（见 5.3）。
- 清理失败只记 warn，不影响请求。

## 8. 接口契约变更

### 8.1 保持不变

- `POST /api/login`、`POST /api/logout`、`GET /api/me`
- `POST /api/c/auth/login`、`POST /api/c/auth/logout`、`GET /api/c/auth/me`、`POST /api/c/auth/register`
- `GET /api/menu`、`GET /api/auth/verify`
- Cookie 名、Cookie 属性、登录/登出响应体
- B/C 前端守卫、nginx SSO、B 端 Next route handler 的 `bSession`

### 8.2 新增

- 上述会话管理接口 6 个。
- `POST /api/perm/users/{id}/status`。
- 登录、登出、`/me`、会话列表响应新增 `Cache-Control: no-store`。

### 8.3 删除

- `Session.makeToken` / `parseToken` / `makeCToken` / `parseCToken`。
- `AppConfig.SESSION_TTL`、`AppConfig.secretKey`、`AppConfig.secretKeyIsInsecure`、`AppConfig.INSECURE_DEFAULT_SECRET`。
- `Bootstrap` 中 `SECRET_KEY` 未配置即拒绝启动的逻辑与 `ALLOW_INSECURE_SECRET` 分支；保留 analytics salt 告警。

### 8.4 权限门禁豁免（必须同轮修改）

`PermService.registerAllRoutes` 会扫描 SpringMVC 映射，把新端点自动登记为 `api:<METHOD>:<path>`；但 `PermGuard.SESSION_ENDPOINTS` 与 `CPermGuard.C_SESSION_ENDPOINTS` 都是**精确匹配**，动态路径 `/api/auth/sessions/{id}` 不会命中：

- B 端：`user` 角色启动时会被 `syncUserApiPerms` 自动补齐非受限接口码，但**自定义角色与 `external` 不会** → 他们访问 `/api/auth/sessions/12` 被 403。
- C 端：`PermService.initCGroupRoutes()` 只在「该组一条持有都没有」时补全量，**既有用户组不会自动获得新码** → 所有 C 用户访问新接口 403。

两端各加一组前缀豁免（在 `denyReason` 的会话判定处、路由表匹配之前返回 `null`）：

| 守卫 | 新增豁免 |
|---|---|
| `PermGuard` | `/api/auth/sessions`（含子路径）、`/api/auth/logout-all` |
| `CPermGuard` | `/api/c/auth/sessions`（含子路径）、`/api/c/auth/logout-all` |

豁免的安全性由 Controller 自己保证：每个方法先过 `AuthUtil.user` / `CAuthUtil.user` 判空 → 401，且只按**当前会话的 uid** 查询与吊销，不接受任意 `user_id` 入参。

## 9. 安全设计

### 9.1 CSRF

新增 `CsrfInterceptor`，注册在 `ApiPermInterceptor` 之前，只作用于 `/api/**` 的 **POST / PUT / PATCH / DELETE**：

1. `Origin` 存在 → 必须在允许源列表；不在则 403；`Origin: null` 同样拒绝。
2. `Origin` 不存在、`Sec-Fetch-Site` 存在 → `cross-site` 拒绝；`same-origin` / `same-site` / `none` 放行；未知值拒绝。
3. 两者都不存在 → 放行（curl / 服务端调用；浏览器 CSRF 无法伪造这一条件）。

允许源由 `AppConfig.allowedOrigins()` 统一提供，CORS 与 CSRF 共用同一份配置，默认包含：

```
https://haolo.cloud
https://www.haolo.cloud
http://117.72.32.87
https://117.72.32.87
http://localhost:3000 / 3010 / 3020 / 3011
https://localhost:3000 / 3010 / 3020 / 3011
http://127.0.0.1:3000 / 3010 / 3020 / 3011
https://127.0.0.1:3000 / 3010 / 3020 / 3011
```

- 支持 `ALLOWED_ORIGINS` 环境变量覆盖（逗号分隔、精确匹配）。
- **`*` 是配置错误**：启动时过滤并 warn，绝不传给 `allowCredentials(true)` 的 CORS；`*.example.com` 这类通配写法同样拒绝。
- 本地 3010/3020/3011 是本轮补齐项；现有 CORS 只列了 3000。
- 登录接口同样受保护，防“登录 CSRF”。
- 低代码公开表单当前同源提交；若以后需要跨站嵌入，把来源加入 `ALLOWED_ORIGINS`。

### 9.2 密码

- **本轮不改变密码哈希格式**：仍为 `pbkdf2$<salt>$<dk>`，PBKDF2-HMAC-SHA256，120000 轮。
  - 原因：改成 4 段格式或提高迭代数后，旧代码无法验证新哈希，回滚会导致所有账号无法登录。
  - 迭代升级（建议 60 万轮 + 登录时 rehash）留作会话表稳定后的独立变更。
- 对“用户不存在”执行一次假哈希校验，消除用户名枚举的时序差；失败文案保持统一。
- 密码长度上限 128 字符（登录/注册/重置都校验）；下限维持现状（6 位），不改存量用户。
- `Passwords.verify` 已使用 `MessageDigest.isEqual` 常量时间比较，保留。

### 9.3 Cookie / 响应

- 名称：`notelab_session` / `notelab_c_session`，不变。
- 属性：`HttpOnly`、`SameSite=Lax`、`Path=/`；生产 `COOKIE_SECURE=1` 时加 `Secure`。
- `Max-Age` 等于会话绝对 TTL（B 7d / 外部 1d / C 30d）。
- 登录、登出、`/me`、会话列表响应加 `Cache-Control: no-store`。
- 日志只记录 `side + user_id + session_id + reason`；**任何级别都不打印 token 或 token_hash**。

### 9.4 `SECRET_KEY` 退役

- 代码中不再有会话签名逻辑，`SECRET_KEY` 不再是启动必需项。
- `.env` 中的 `SECRET_KEY` 在回滚窗口期保留，确认稳定后再清理。
- 这一步从结构上消除了“默认密钥伪造任意用户会话”的漏洞类别。

## 10. 错误语义

| 场景 | 状态码 | 响应 |
|---|---|---|
| Cookie 缺失 / token 无效 / 过期 / 已吊销 / 闲置超时 | 401 | `{"error":"请先登录"}` |
| 登录时账号被禁用 | 403 | `{"error":"账号已被禁用"}` |
| CSRF 校验失败 | 403 | `{"error":"跨站请求被拒绝"}` |
| 操作他人或不存在的会话 id | 404 | `{"error":"会话不存在"}` |
| 会话管理接口被外部账号调用 | 403 | `{"error":"外部账号不支持会话管理"}` |
| 登出/退出全部时吊销失败 | 500 | `{"error":"退出失败，请重试"}`，不清 Cookie |
| C 自助改密时吊销会话失败 | 500 | `{"error":"修改失败，请重试"}`（密码未变，会话仍有效） |
| C 自助改密：吊销成功但密码更新失败 | 500 | `{"error":"修改失败，请重试"}`（当前会话已吊销，密码未变，可用旧密码重登） |
| 管理员重置密码时吊销会话失败 | 500 | `{"error":"会话吊销失败，密码未修改"}`（先吊销后改密，不留下“密码已改、旧会话仍有效”） |
| 管理员重置密码：吊销成功但密码更新失败 | 500 | `{"error":"密码重置失败，请重试"}`（目标用户已掉线，密码未变） |
| 数据库查询会话失败 | 500 | 向上抛，不静默当匿名 |
| `last_seen_at` 更新失败 | 不影响请求 | 仅记 warn |
| 清理/容量控制失败 | 不影响请求 | 仅记 warn |

## 11. 兼容、上线与回滚

### 11.1 上线步骤

0. **先备份数据库**：`mysqldump notelab > /root/backups/notelab-pre-auth-sessions-$(date +%F).sql`。`users` 表要加列，按仓库纪律「改表结构前必须备份」执行，不要依赖当天 3:00 的自动备份。
1. 本地修改 → commit/push。
2. 服务器执行 `ops/sync-deploy.sh notelab-java`。
3. 启动时 `DbSchema` 自动：
   - `CREATE TABLE IF NOT EXISTS auth_sessions`
   - `ALTER TABLE users ADD status ...`（`hasColumn` 守护）
4. 旧 HMAC token 查不到会话 → 401 → B/C 现有守卫跳登录。**所有用户重新登录一次**。
5. 执行 `ops/verify-auth-sessions.js` 验收。
6. 观察 `login_audit`、401 量、`auth_sessions` 行数与清理日志。
7. 稳定后从 `.env` 移除 `SECRET_KEY`（可选）。

### 11.2 回滚

- 回退 Java 代码并重新部署即可；`auth_sessions` 表与 `users.status` 列保留，旧代码忽略。
- 新 opaque token 旧代码无法解析 → 用户再登录一次。
- 密码哈希格式未变，回滚不影响账号密码。
- `.env` 中的 `SECRET_KEY` 在回滚窗口期必须保留，旧代码启动仍需要它。
- 已知临时回退：B 端 `status` 检查消失，被禁用的 B 账号在回滚期间可登录；恢复新版本后重新失效。

### 11.3 数据迁移

- 无存量会话迁移；旧 token 全部作废。
- 无密码迁移。
- 新增表与列由 `DbSchema` 幂等创建。

### 11.4 运维脚本影响

- 仓库内没有依赖 `SECRET_KEY` 的脚本；服务器上若存在历史的一次性 Cookie 签发脚本（如 `gen-cookie18.mjs` 一类），新版上线后**全部失效**——服务端不再验签 HMAC。
- 需要自动化登录时改用 `POST /api/login` 取 Cookie；不要新增「离线造会话」脚本，那等于绕过会话表。

## 12. 测试与验收

### 12.1 单元测试（JUnit，纯函数）

- `SessionTokenTest`
  - token 长度 43、字符集 `[A-Za-z0-9_-]`、1000 次生成无重复。
  - 同一 token 的 SHA-256 稳定；不同 token 哈希不同；哈希为 64 位小写十六进制。
- `SessionPolicyTest`
  - 各 side/auth_method 的绝对 TTL、闲置阈值。
  - 刚好到期、刚好闲置、明显过期/闲置的边界。

### 12.2 HTTP 验收脚本

新增 `ops/verify-auth-sessions.js`（Node 24，内置 fetch，无第三方依赖）：

- 默认只读：登录、`/me`、CSRF 拒绝、Cookie 属性检查。
- `--write` 才执行会改数据的吊销/禁用测试，并在 `finally` 恢复现场。
- 账号从环境变量读取：`B_USER` / `B_PASS` / `ADMIN_B_USER` / `ADMIN_B_PASS` / `C_USER` / `C_PASS`；不打印密码与 token。
- 覆盖：
  1. B 登录 → `/api/me` 200 → 登出 → 同 Cookie `/api/me` 401。
  2. B 管理员重置测试用户密码 → 旧 Cookie 401。
  3. C 登录 → 自助改密 → 旧 Cookie 401、新 Cookie 200。
  4. C 管理员禁用测试用户 → 旧 Cookie 401；恢复 `active` 后可登录。
  5. 会话列表包含当前会话；踢掉另一个会话后其 Cookie 401。
  6. `Origin: https://evil.example` 的 POST → 403；无 Origin 的请求正常。
  7. 响应中不出现 token / token_hash。
  8. 把 B 的 Cookie 值改名成 `notelab_c_session` 调 `/api/c/auth/me` → 401；反向同理。
- 闲置超时由 `SessionPolicyTest` 覆盖边界；**不要**为了验证在生产改 `SESSION_IDLE_*`（要重启且影响所有用户）。

### 12.3 构建与部署验证

- 服务器 `mvn -DskipTests package`（现有 sync-deploy）。
- 纯函数单测 `mvn test`，不阻塞发布。
- 部署后 `/api/menu` 未带会话返回 401，视为服务存活（现有探活口径）。

### 12.4 验收标准

- [ ] 登出后同一 Cookie 再访问受保护接口返回 401。
- [ ] 改密/管理员重置密码后，目标用户旧会话全部 401。
- [ ] C 自助改密后当前设备保持登录，其他设备 401。
- [ ] 禁用账号后旧会话 401，登录返回 403。
- [ ] 会话列表只显示活跃会话，不含 token/hash。
- [ ] 跨站 POST 被 403，同源/无 Origin 请求正常。
- [ ] Java 重启后已登录会话仍有效。
- [ ] `auth_sessions` 中不存在明文 token。
- [ ] `auth_sessions` 表与 `users.status` 列已创建，存量 B 用户均为 `active`。
- [ ] 旧 HMAC token 上线后一律 401。
- [ ] B token 与 C token 不能互相冒用。
- [ ] ai-lab 的 nginx SSO 与画布 WS 握手仍正常。

## 13. 风险与取舍

| 风险 | 影响 | 缓解 |
|---|---|---|
| 全量用户需重新登录一次 | 一次性体验中断 | 上线窗口选择低峰；前端已有 401 回跳 |
| 每请求多一次会话查询 | 延迟增加 | 唯一索引点查；流量低；`last_seen_at` 节流写 |
| MySQL 故障导致认证不可用 | 全站登录/鉴权失败 | 与登录本身一致，fail-closed；不静默放行 |
| CSRF 来源配置遗漏 | 合法请求被 403 | CORS/CSRF 共用一份配置；保留无 Origin 放行 |
| 外部共享账号会话互相可见 | 隐私/互相踢下线 | `external` 禁止会话列表与 logout-all |
| 会话表增长 | 磁盘/查询变慢 | 每日清理 + 每用户容量上限 |
| 吊销与并发请求竞争 | 已吊销后仍有一个在途请求成功 | 可接受的短窗口；敏感操作在 Controller 内复查用户状态 |
| 回滚后 B 端禁用失效 | 被禁用账号可重新登录 | 仅回滚窗口期；恢复新版本后重新拦截 |
| 历史 Cookie 签发脚本失效 | 无法再离线造会话 | 改用登录接口；见 11.4 |
| 密码哈希仍为 12 万轮 | 弱于当前建议 | 留作独立变更，避免回滚不兼容 |

## 14. 实施文件清单

**新增**

```
src/main/java/com/notelab/common/SessionToken.java
src/main/java/com/notelab/common/SessionPolicy.java
src/main/java/com/notelab/common/CsrfInterceptor.java
src/main/java/com/notelab/service/SessionService.java
src/main/java/com/notelab/dao/SessionDao.java
src/main/java/com/notelab/mapper/AuthSessionMapper.java
src/main/java/com/notelab/model/entity/AuthSession.java
src/main/java/com/notelab/scheduler/SessionCleanupScheduler.java
src/test/java/com/notelab/common/SessionTokenTest.java
src/test/java/com/notelab/common/SessionPolicyTest.java
ops/verify-auth-sessions.js
```

**修改**

```
src/main/java/com/notelab/common/Session.java
src/main/java/com/notelab/common/AppConfig.java
src/main/java/com/notelab/common/WebConfig.java
src/main/java/com/notelab/common/PermGuard.java
src/main/java/com/notelab/common/CPermGuard.java
src/main/java/com/notelab/Bootstrap.java
src/main/java/com/notelab/dao/DbSchema.java
src/main/java/com/notelab/dao/DaoSupport.java
src/main/java/com/notelab/dao/UserDao.java
src/main/java/com/notelab/model/entity/User.java
src/main/java/com/notelab/service/PermService.java
src/main/java/com/notelab/controller/AuthController.java
src/main/java/com/notelab/controller/CAuthController.java
src/main/java/com/notelab/controller/CMeController.java
src/main/java/com/notelab/controller/CAdminController.java
src/main/java/com/notelab/controller/PermController.java
```

**文档（同轮更新）**

```
README.md      # 会话机制（HMAC → 服务端会话表）、SECRET_KEY 不再必需
AGENTS.md      # 「双身份体系」表、配置项与运维影响
PROGRESS.md    # 部署后的 curl 自验记录（仓内既有要求）
```

**不改**

- `notelab-b`、`notelab-c` 前端源码。
- nginx 配置。
- RBAC 表结构与权限规则。
- 密码哈希格式。

## 15. 待实施时的确认点

以下不是设计缺口，而是实施时需要按现场核对的细节：

1. ~~删除用户是否有级联~~ 已确认无级联：`PermService.deleteUser` → `UserDao.deleteUser` → `deleteById`。会话清理挂在删除成功之后。
2. B 端 `status` 的 Map 出口：`User` 实体加字段后，`UserDao.ALL_COLS` 必须补 `"status"`（登录与每次请求解析要读）；`LIST_COLS` / `listUsersPaged` 的投影也补上（后台列表要显示禁用态）。列表输出是 Map，新增键不破坏既有消费方。
3. ~~IP 哈希工具~~ 已确认：`EventRecorder.ipHash(HttpServletRequest)`，返回 16 字符，与 `ip_hash CHAR(16)` 对齐；直接复用，不另写。
4. 现有全局异常处理对 DAO 异常的状态码，确保会话查询失败返回 500 而不是 401。
5. B/C 测试账号文件的实际路径与可用性，验收脚本优先从环境变量取，避免把明文写进仓库。
