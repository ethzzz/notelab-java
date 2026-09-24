# 杀戮尖塔 · 角色选择按 C 端用户组前置筛选（跨 java / notelab-b / notelab-c 三仓）

你在服务器（117.72.32.87，Ubuntu，`/root` 已预信任）。任务：给爬塔游戏（杀戮尖塔）的**角色选择**加"按 C 端用户组授权"的前置筛选，并在 B 端后台「尖塔内容工坊」新增「角色授权」配置能力。涉及三仓，全部在生产目录内改：

- `/root/notelab-java`（Spring Boot 后端，:8001，pm2 `notelab-java`）
- `/root/notelab-b`（B 端 antd 后台，basePath `/admin`，:3020，pm2 `notelab-b`）
- `/root/notelab-c`（C 端游戏前端，basePath `/games`，:3010，pm2 `notelab-c`）

## 需求（用户原话拆解）

1. 角色选择新增前置筛选：某些角色需要"当前用户拥有该角色"才能选。例如 **武诸葛** 需要授权。
2. B 端后台「尖塔配置」新增 **角色配置（角色授权）** 功能：给 **C 端用户组** 勾选该组可选择哪些角色。
3. C 端用户 **通过所属用户组** 获得可选择的角色集合；角色选择页据此筛选/锁定。
4. 暂定两个用户组：**普通用户（组码 `default`）** 与 **VIP 用户（组码 `vip`）**。
   - 普通用户：可选择 **除武诸葛（id `wuzhuge`）外** 的所有角色。
   - VIP 用户：可选择 **所有** 角色。

## 背景事实（已核实，直接采信，无需重新探查）

- **角色数据来源**：游戏引擎 `notelab-c/lib/spire-engine.ts` 有内置角色 `BASE_CHARACTERS`（含 `id:"wuzhuge", name:"武诸葛"` 等），运行时 `CHARACTERS = 基础 + 工坊自定义`（自定义经 `applyCustomContent(cards, characters)` 合并，同 id 覆盖）。**要筛选的是这个"全量生效角色池"**，不只是自定义角色。
- **内容存储**：工坊自定义内容存 `ui_config` 表 JSON 的 `spire` 键：`{cards:[], characters:[], skills:[]}`；「发布」把该对象整体快照进顶层 `spire_published`。相关：
  - 后端 `SpireContentController`（`/api/spire-content` GET/POST、`/publish`、`/unpublish`；私有 `spireOf(cfg)` 从 `spire` 取 cards/characters/skills 三键——**发布快照与读取都走它**）。
  - C 端匿名读取 `CConfigController.spireContent()`（`GET /api/c/spire/content` → `spire_published`）。
- **C 端登录态**：`/api/c/auth/me` 返回 `{id, username, nickname, group_code}`（`CAuthController`，需 C 会话）。C 端 `notelab-c/lib/auth.ts` 有 `fetchMe(): Promise<CUser|null>`（静默、不跳转）与 `CUser{group_code}`；`components/RequireAuth.tsx` 是登录墙（`/spire` 目前 **没有** 登录墙，匿名可玩——本任务 **不要** 给 `/spire` 加登录墙）。
- **C 用户组**：表 `c_users.group_code` → `c_user_groups(code,name)`，当前种子仅 `default`。B 端管理接口 `CAdminController`：`GET /api/c-admin/groups`（列组 + member_count，需 B 登录）、`POST /api/c-admin/groups`（建组，body `{code,name}`，code 校验 `[a-z0-9_]{2,30}`）、`/api/c-admin/users`（可改某用户 group_code）。DAO：`CUserDao`（`getGroup`/`createGroup` 等，MyBatis-Plus 静态门面风格）。
- **C 端角色选择页**：`notelab-c/app/spire/page.tsx`，`if (!s && pickOpen)` 分支渲染 `CHARACTERS.map(...)` 的角色卡按钮，点击 `pickCharacter(ch.id)`（`newRun(charId)`）。
- **B 端工坊页**：`notelab-b/src/app/(admin)/spire-editor/page.tsx`，antd Tabs（卡片/角色/技能 三 tab），`lib/spire-content.ts` 的 `loadSpireContent/saveSpireContent` 读写 `/api/spire-content`；页面已有「发布/取消发布」按钮（阶段 2 加）。
- codex 通用启动：`run-codex-task.sh <promptFile> <logFile>`（cwd `/root/notelab-java`）。

## 硬性纪律（务必遵守）

1. **只改** 上述三仓当前功能相关文件；**不动** `/root/myapp`、`/root/notelab`（Python 版）、nginx 配置、其它无关进程。
2. **共享 MySQL = 生产数据**：对 `c_user_groups` / `ui_config` 的任何写操作前，先 `mysqldump` 备份（如 `/root/backups/pre-spire-gating-$(date +%F).sql`）。DDL 仅限 `CREATE TABLE IF NOT EXISTS`；**禁止 DROP/DELETE/改表结构**。建组用 INSERT（幂等：先 `getGroup` 判存在）。
3. **密钥零硬编码**：不把任何密钥写进代码/提交。
4. **契约与风格对齐现状**：后端沿用 MyBatis-Plus 静态门面 DAO + Map 出口；`ui_config` 读写用合并写法（保留 background/menus/spire 等其它键，见 `SpireContentController.save`）。
5. 每仓改动后构建+重启并自检：
   - java：`cd /root/notelab-java && mvn -DskipTests package` → `pm2 restart notelab-java` → curl 验证接口。
   - notelab-b：`cd /root/notelab-b && npm run build` → `pm2 restart notelab-b`。
   - notelab-c：`cd /root/notelab-c && npm run build` → `pm2 restart notelab-c`。
6. 每仓完成后按仓库规范 **commit**（三段式：type 标题 + 【产生原因】/【解决方案】/【影响范围】，提交身份 `ethzzz`）；**先不要 push**（推送由人工确认后另行执行），在报告里列出各仓 commit hash。

## 数据模型设计（按此实现）

在 `ui_config` 的 `spire` 对象内 **新增第 4 个键** `charAccess`（与 cards/characters/skills 并列）：

```jsonc
"charAccess": {
  "default": ["blade", "...其余除 wuzhuge 外的全部角色 id"],
  "vip":     ["全部角色 id，含 wuzhuge"]
}
```

- 语义：**某组可选择的角色 id 白名单**。C 端某用户 `group_code = g`，则 `g` 对应数组内的角色可选，数组外的 **锁定**。
- **向后兼容（fail-open）**：若 `charAccess` 缺失、或用户所属组在 `charAccess` 中 **没有对应键** → 该情况下 **不筛选**（全部角色可选），避免未配置时把玩家全锁死。只有当"该组存在白名单键"时，白名单外的角色才锁定。
- 白名单要能覆盖"全量生效角色池"= 内置基础角色 + 工坊自定义角色。

## 实施内容

### A. 后端 `notelab-java`

1. **`SpireContentController`**：
   - `save`：解析并持久化 `charAccess`（对象，键为组码、值为字符串数组；做类型净化：非对象→`{}`，值非数组→`[]`，元素过滤为字符串）。并入 `spire` 对象一起写 `ui_config`（保留合并写法与 200KB 体积校验）。
   - `spireOf(cfg)`：**增加返回 `charAccess`**（缺省 `Map.of()`/`{}`）。这样 `GET /api/spire-content`、`/publish` 快照都会带上它。
2. **基础角色清单来源**（供 B 端授权界面展示"基础角色"，B 端无引擎代码）：在 `GET /api/spire-content` 响应里 **新增 `baseCharacters`** 字段：一个常量数组 `[{id,name,icon}, ...]`，内容镜像 `spire-engine.ts` 的 `BASE_CHARACTERS`（至少含 `blade`、`wuzhuge` 等全部内置角色；把 id/名称/图标写全）。用一处常量维护并加注释：新增内置角色时此处需同步。（C 端不需要该字段，但带上无害。）
3. **`CConfigController.spireContent()`**：响应 **增加 `charAccess`**（从 `spire_published.charAccess` 取，缺省 `{}`），让 C 端匿名也能拿到授权表用于本地筛选。
4. **组种子**：确保 `c_user_groups` 存在两行——`default`（name「普通用户」，一般已存在）与 `vip`（name「VIP用户」）。用幂等方式补建（先 `getGroup("vip")` 判空，再 INSERT；**写库前已 mysqldump 备份**）。机制二选一（择稳妥者）：应用启动时 idempotent seed，或在本任务部署步骤里经 SQL/接口建一次。

### B. B 端 `notelab-b`（尖塔内容工坊 · 新增「角色授权」Tab）

在 `spire-editor/page.tsx` 的 Tabs 内新增第 4 个 tab「👥 角色授权」：

- 进入时并行加载：`loadSpireContent()`（含 `charAccess`、`characters`、`baseCharacters`）与 `GET /api/c-admin/groups`（现有组列表，取 code/name）。
- 全量可选角色 = `baseCharacters` + 工坊 `characters`（按 id 去重，展示 `icon 名称`）。
- 界面：左侧（或 Select）选用户组；右侧该组的角色勾选列表（antd `Checkbox.Group` 或 `Transfer`）。**默认勾选规则**（首次无配置时预填，便于运维直接发布）：
  - `default`：勾中除 `wuzhuge` 外的全部角色；
  - `vip`：勾中全部角色；
  - 其它组：默认全勾（fail-open 一致）。
- 顶部按钮「保存授权」：把当前 `charAccess`（所有组）合并进 `loadSpireContent` 的完整内容对象后 `saveSpireContent(...)`（**注意**：保存要连同 cards/characters/skills 一起 PUT，避免覆盖丢失；`saveSpireContent` 的入参即后端 `save` 期望的整包 `{cards,characters,skills,charAccess}`）。
- 复用页面既有「发布/取消发布」按钮：授权配置随内容发布才在 C 端生效——文案提示"调整后需点发布"。
- `notelab-b/src/lib/spire-content.ts`：`SpireCustomContent` 增加 `charAccess?: Record<string,string[]>` 与 `baseCharacters?: {id,name,icon}[]`，`loadSpireContent` 透传、`saveSpireContent` 提交时带上 `charAccess`（`baseCharacters` 只读，提交时可省）。

### C. C 端 `notelab-c`（角色选择按组筛选/锁定）

在 `app/spire/page.tsx` 角色选择分支实现：

1. 组件挂载时（与现有 `loadSpireContent` 的 useEffect 并行或之后）：
   - `const me = await fetchMe()` → `userGroup = me?.group_code || "default"`（未登录按 default）。
   - 从内容里取 `charAccess`（`loadSpireContent` 需一并返回；扩展 `notelab-c/lib/spire-content.ts` 的 `SpireCustomContent` 加 `charAccess`）。
   - 计算 `allowList = charAccess?.[userGroup]`：
     - `charAccess` 为空或无该组键 → `allowList = null`（**不筛选，全可选**）。
     - 否则 `allowList` 为可选 id 数组。
2. 渲染 `CHARACTERS.map` 时：`const locked = allowList ? !allowList.includes(ch.id) : false`。
   - 未锁：与现状一致可点。
   - 锁：卡片置灰（`opacity-50 grayscale`、去 hover 高亮）、右上角 🔒 角标、`onClick` **不触发** `pickCharacter`、加 `aria-disabled`、底部小字提示「该角色未解锁 · VIP 专属/联系管理员」。
   - 顶部副标题补一行：`当前身份：<普通用户|VIP用户>`（按 userGroup 映射文案），说明可选范围由用户组决定。
3. `pickCharacter` 内再做一次防御：若 `allowList` 且不含该 id → 直接 return（双保险）。
4. 不改 `/spire` 的匿名可访问性（不加 RequireAuth）。

> 说明：本游戏整局状态在客户端，授权为"前置筛选/展示锁定"级别（非强安全边界），客户端锁定即可满足需求；无需服务端 run 级校验。

## 验证（全部通过才算完成）

1. **java**：`mvn package` 无错、`pm2 restart notelab-java` online。curl：
   - `GET /api/spire-content`（带 B 会话或按现状鉴权）响应含 `charAccess` 与 `baseCharacters`。
   - 保存+发布一份带 `charAccess` 的内容后，`GET /api/c/spire/content`（匿名）响应含 `charAccess`。
   - `SELECT code,name FROM c_user_groups` 有 `default`、`vip` 两行。
2. **notelab-b**：`npm run build` 零错、`pm2 restart notelab-b`；经 `/admin` 登录超管进入「尖塔内容工坊 → 角色授权」，能看到 default/vip 两组、能勾选、保存+发布成功（toast 正常，重进数据回显正确）。
3. **notelab-c**：`npm run build` 零错、`pm2 restart notelab-c`。浏览器实测（用 `/root/notelab-c/account.json` 里的 C 端测试账号，读 `account`/`password`）：
   - 该测试账号所属组为 `default` → 角色选择页 **武诸葛锁定**、其余可选；点锁定的武诸葛无反应。
   - 把该账号 `group_code` 经 `/api/c-admin/users` 改为 `vip`（或新建一个 vip 组测试号）→ 刷新后 **武诸葛可选**，可正常开局进入游戏。
   - 未登录直接访问 `/games/spire` 开始 → 按 default 处理（武诸葛锁定）。
   - 记录关键截图与浏览器控制台无 error。
4. 回归：不影响既有卡/角色/技能编辑与发布、其它 C 端游戏与 B 端页面正常。

## 交付与报告

- 三仓分别 commit（不 push），报告列出各仓 commit 短 hash 与改动文件。
- 在 `/root/notelab-java/ops/` 追加一篇 `SPIRE-CHAR-GATING.md`：数据模型（`charAccess` 结构、fail-open 规则）、新增/变更接口字段（`/api/spire-content`、`/api/c/spire/content`）、B 端新 Tab、C 端筛选逻辑、验收结果、回滚方式（还原 ui_config 备份 + 删除 vip 组 + 各仓回退 commit）。
- 最终输出简明报告：改动清单（按仓）、验收结果（逐条）、遗留事项（如"新增内置角色需同步 `baseCharacters` 常量"）。
