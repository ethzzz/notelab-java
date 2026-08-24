# B/C 端拆分 · 一页纸总览（阶段 0-5 完成）

日期：2026-08-25　服务器：117.72.32.87　详细阶段记录：ops/BC-SPLIT-P0/P3/P4.md + PROGRESS.md 末章

## 入口与拓扑

- **唯一推荐入口：`http://117.72.32.87/`（nginx :80）**
  - `/` → C 端游戏中心（notelab-c，:3010）
  - `/admin` → B 端管理后台（notelab-b，:3020，basePath=/admin）
  - `/api/*` → API 主后端（notelab-java，:8001，SSE 直通配置）
- 旧入口（观察期保留，勿用于日常）：`:3000` 旧 myapp、`:3001` myapp-dev、`:8000` Python FastAPI

## 服务清单

| 服务 | 端口 | 进程 | 说明 |
|------|------|------|------|
| nginx | 80 | systemd | 统一入口（配置 /etc/nginx/sites-available/notelab，归档 ops/nginx-notelab.conf） |
| notelab-java | 8001 | pm2 `notelab-java` | Spring Boot，B/C 两端全部 API |
| notelab-c | 3010 | pm2 `notelab-c` | C 端前端（游戏中心，仓库 /root/notelab-c） |
| notelab-b | 3020 | pm2 `notelab-b` | B 端前端（antd 后台，仓库 /root/notelab-b） |
| myapp | 3000 | pm2 `myapp` | 旧站，观察期（回滚依赖） |
| myapp-dev | 3001 | pm2 `myapp-dev` | 旧站 dev，观察期（回滚依赖） |
| notelab | 8000 | pm2 `notelab` | Python FastAPI，观察期（退役决定权在用户） |

## 数据库变更（MySQL notelab 库，全部只增不改，存量零迁移）

| 对象 | 阶段 | 说明 |
|------|------|------|
| 表 `c_users` | 1 | C 端用户（username 唯一、昵称、组、状态、密码哈希） |
| 表 `c_user_groups` | 1 | C 端用户组（种子：default 默认组） |
| 列 `trpg_playthroughs.scope` CHAR(1) DEFAULT 'b' + 索引 (scope,user_id) | 2 | 存档 B/C 归属 |
| 列 `trpg_scenarios.published` TINYINT DEFAULT 0 | 2 | 剧本发布标记 |
| `ui_config` 顶层键 `spire_published` | 2 | 仅发布期间存在（unpublish 即删） |
| `perm_routes` 65 → 86 | 1/2 | 新路由启动自动注册 |

## 接口清单（本系列新增）

- **C 端认证** `/api/c/auth/*`：login / me / logout / register（register 受 `C_REGISTER_OPEN` 开关，默认关）
- **C 端游玩** `/api/c/trpg/*`：scenarios（仅已发布）、scenarios/{id}、scenarios/{id}/play、plays、plays/{id}、plays/{id}/choose、DELETE plays/{id}（一律 scope='c' + 本人守卫）
- **C 端匿名配置**：GET /api/c/config/background、GET /api/c/spire/content
- **B 端管理 C 用户** `/api/c-admin/*`：users（查/建/改/重置密码/删）+ groups（增删改查），B 端登录守卫
- **发布系列**：POST /api/trpg/scenarios/{id}/publish|unpublish、POST /api/spire-content/publish|unpublish
- Cookie 隔离：B `notelab_session`（3 段 token）/ C `notelab_c_session`（4 段 `c.` token），互不认（对抗实测）

## 会话与密钥

- 签名算法与密钥不变（HMAC-SHA256，SECRET_KEY 取 /root/notelab/.env），B 端登录态与 Python 版兼容。
- 无状态会话：logout 仅清 Cookie（置空过期），与 Python 版语义一致。

## 遗留事项

1. **Python 版（:8000 / pm2 notelab）退役**：保持运行未动。建议：确认 :8000 无访问（uvicorn 日志/nginx 无引用）
   一段时间后，由用户执行 `pm2 stop notelab && pm2 save`（或 delete）。决定权在用户。
2. **旧 myapp（:3000）/ myapp-dev（:3001）**：观察期，回滚依赖。确认无流量后由用户决定停用。
3. **公网安全组 :80**：内网已验证；公网可达性取决于云安全组是否放行 :80（需用户在控制台确认一次）。
4. **C 端注册**：`C_REGISTER_OPEN` 默认 false。开放方式：给 pm2 notelab-java 注入环境变量
   `C_REGISTER_OPEN=true`（ecosystem/启动脚本或 `pm2 start` 时 --env），重启即生效；关闭同理改回。
5. **`/c-users` 无菜单项**：菜单树为 Java 常量 + RBAC，本系列未改；入口在 /admin/perm 页直达卡片。
6. **/api/c-admin/* 仅校验 B 端登录**（未限超管），前端入口只放超管页；如需后端硬限制后续加守卫。
7. **浏览器自动化测试未做**：验收依赖 build 零报错 + 代码走查 + 接口层 curl 全矩阵。
8. **旧系列遗留数据**（未动，非本系列产生）：trpg_gen_tasks id=2（剧本 6 已删）、users 表 crosstest*/dup*/s5* 账号。
9. **本地镜像同步**：notelab-c / notelab-b 两个新仓库待同步到本地（见最终报告同步指引）。
10. **ctest1**（c_users）：C 端回归 fixture 账号，保留供后续验证；不再需要时可经 /admin/c-users 删除。

## 回滚速查

```bash
# 秒级止血（不动代码数据）：改 nginx 指向后 reload
systemctl reload nginx
# 整体撤掉 nginx 入口（回到拆分前的直连形态）
systemctl stop nginx && systemctl disable nginx
# Java 回退到不含 C 接口的构建：1cfb02b 之前（如 3acaec2），新表/新列保留无害
# 详见 PROGRESS.md「回滚方法汇总」
```
