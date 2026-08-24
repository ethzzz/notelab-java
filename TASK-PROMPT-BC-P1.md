# B/C 端拆分 · 阶段 1：后端 B/C 双身份体系（notelab-java）

你在服务器 /root/notelab-java（Spring Boot 3.4.5 / Java 17 / MyBatis-Plus 3.5.9，pm2 `notelab-java` :8001）。
这是「B/C 端拆分」的**阶段 1**：建立 C 端独立身份体系。阶段 0（nginx :80 前缀代理 + :3010/:3020 壳应用）已完成并验收通过，归档在 /root/notelab-java/ops/BC-SPLIT-P0.md（含 nginx 配置与回滚方法），不要动它。本阶段验证一律走 127.0.0.1 原端口直连（:8001），不依赖 nginx。

## 背景事实（已核实，直接采信）

- 持久层是 MyBatis-Plus：`mapper/` 接口 + `model/entity/` 实体 + 静态门面 `dao/*Dao`（返回 `Map`，snake_case 键，`common/RowUtil` 转换）；`dao/DaoSupport` 静态持有全部 Mapper 与 TransactionTemplate。新表请沿用同一套模式。
- 建表层 `dao/DbSchema.java` 启动执行（只增不改），由 `Bootstrap.run()` 经 `Db.init(dataSource)` 触发——新表 DDL 加在这里。
- 会话：`common/Session.java` HMAC token = base64url(`<uid>.<exp>.<hmac_sha256_hex(uid.exp)>`)，Cookie 名 `notelab_session`（AppConfig.SESSION_COOKIE），与 Python 版互认——**B 端格式绝对不许动**。
- 认证工具：`controller/AuthUtil`；RBAC：`service/PermService` + `perm_routes` 启动自动注册（新增路由重启即登记）。
- 限流：`service/RateLimit`（Redis）。
- 密码：`common/Passwords`（hash/verify）。
- 构建部署：`mvn -f /root/notelab-java/pom.xml -DskipTests -q package` → `pm2 restart notelab-java` → curl 验证。

## 硬性纪律

1. **共享 MySQL**：开工前先 `mysqldump notelab > /root/notelab-java/data/bc-p1-dump.sql`；本阶段只允许**新建表**（CREATE TABLE IF NOT EXISTS），禁止任何对现有表的改动。
2. 不改 /root/notelab（Python 版）、/root/myapp；B 端 token/Cookie/users 表行为零变化（Python 版共用）。
3. 不动阶段 0 的 nginx 与两个壳应用。
4. 每步构建+重启+验证通过后再继续；失败必须回滚修复，不带病收尾。

## 实施内容

### 1. 新表（加入 DbSchema，风格对齐现有建表语句）

```
c_users(
  id INT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(50) NOT NULL DEFAULT '',
  group_code VARCHAR(50) NOT NULL DEFAULT 'default',
  status VARCHAR(20) NOT NULL DEFAULT 'active',   -- active / disabled
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4

c_user_groups(
  code VARCHAR(50) PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```
种子：`INSERT IGNORE INTO c_user_groups (code,name) VALUES ('default','默认组')`。

### 2. 实体与 Mapper
`model/entity/CUser`、`CUserGroup` + `mapper/CUserMapper`、`CUserGroupMapper`（BaseMapper；需要的话加注解 SQL），`DaoSupport` 注入。可新建 `dao/CUserDao` 静态门面（Map 出口，RowUtil 键序），供 controller 使用。

### 3. Token 隔离（Session 扩展，不改原方法）
- 新增 `makeCToken(long uid)`：payload = `c.<uid>.<exp>`，签名同法；整体 = base64url(`c.<uid>.<exp>.<sig>`)（**4 段**，与 B 的 3 段天然区分）。
- 新增 `parseCToken`：解出必须恰好 4 段且首段为 `c`，验签+过期校验，返回 uid；否则 null。B 端 `parseToken` 保持只认 3 段——两端即使 uid 数字相同也无法互认。
- 新增 Cookie 常量 `SESSION_COOKIE_C = "notelab_c_session"`（放 AppConfig 或 Session 均可），`setCookieC/deleteCookieC/currentUserC`（currentUserC 查 c_users 且要求 `status='active'`，否则视同未登录）。

### 4. C 端认证接口（新 `controller/CAuthController`，前缀 /api/c/auth）
- `CAuthUtil`（与 AuthUtil 并列）：解析 notelab_c_session → c_users 行。
- `POST /api/c/auth/login` {username,password}：查 c_users，Passwords.verify，status 校验，接 `RateLimit` 防爆破（参考 AuthController 现有限流用法），成功 setCookieC；**响应结构与 AuthController 的 login 保持一致**（把 user 行数据按 B 端 me 的同构字段风格输出，含 id/username/nickname/group_code）。
- `GET /api/c/auth/me`：CAuthUtil 未登录 401 `{"error":"请先登录"}`。
- `POST /api/c/auth/logout`：deleteCookieC。
- `POST /api/c/auth/register`：完整实现但受开关控制——`AppConfig.get("C_REGISTER_OPEN","false")`，默认关闭时返回 403 `{"error":"注册未开放"}`。

### 5. B 端管理 C 用户的接口（新 `controller/CAdminController`，前缀 /api/c-admin）
全部要求 B 端登录（AuthUtil），权限走现有体系（路由会自动登记进 perm_routes）：
- `GET /api/c-admin/users`：列表（支持 q 模糊搜 username/nickname、group_code 筛选、分页 limit/offset + total，风格对齐 /api/perm/users）。
- `POST /api/c-admin/users` {username,password,nickname,group_code}：Passwords.hash 入库；重复用户名 → 409（捕获 DuplicateKeyException，语义对齐 B 端账户管理）。
- `PUT /api/c-admin/users/{id}`：改 nickname/group_code/status。
- `POST /api/c-admin/users/{id}/reset-password` {password}。
- `DELETE /api/c-admin/users/{id}`。
- `GET/POST/PUT/DELETE /api/c-admin/groups`：用户组增删改查（删组前校验无成员，有成员返回 409）。
- 输出统一 Map 出口（RowUtil），日期格式 `yyyy-MM-dd HH:mm:ss`。

### 6. 验证（全部通过才算完成）
生成超管 Cookie 的方法：按 Session.java 签发逻辑写一次性脚本（users 表 role='super_admin' 账户；阶段 0 验收已验证过同样方法，SECRET_KEY 取自 /root/notelab/.env）。
1. 构建重启正常，启动日志无 ERROR，perm_routes 数增加（新路由已登记）。
2. B 超管经 /api/c-admin/users 建 C 用户 `ctest1`（组 default）→ 列表可见 → 重复建 409。
3. `POST /api/c/auth/login` ctest1 → 拿到 notelab_c_session；`GET /api/c/auth/me`（C Cookie）200；**同一 C Cookie 打 /api/menu（B）→ 401**；**B Cookie 打 /api/c/auth/me → 401**。
4. 错密码登录 401；连打 20 次触发限流（429/限流错误语义与现有一致）。
5. `POST /api/c/auth/register` → 403（开关关闭）。
6. 回归：B 端登录/菜单/权限接口行为不变；Python 版 `curl :8000` 登录链路不受影响（至少验证 :8000 仍可达且登录接口返回原有语义）。
7. 用户组：建组/改名/删组（空组可删、有成员 409）。

### 7. 提交
`git add -A && git commit -m "B/C拆分阶段1：C端身份体系（c_users/c_user_groups + c. token隔离 + /api/c/auth + /api/c-admin）"`。
最终输出简明报告：改动清单、验证结果逐条、遗留事项。
