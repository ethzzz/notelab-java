# 杀戮尖塔 · 角色选择按 C 端用户组前置筛选（spire.charAccess）

> 2026-09-24 · 三仓联动：notelab-java（:8001）/ notelab-b（:3020，/admin）/ notelab-c（:3010，/games）
> 需求：某些角色（如武诸葛）需"当前用户所属用户组拥有该角色"才可选；B 端「尖塔内容工坊」新增「角色授权」配置能力。

## 1. 数据模型

### 1.1 `ui_config.spire` 新增第 4 键 `charAccess`

与既有 `cards / characters / skills` 并列，语义 = **某 C 端用户组可选择的角色 id 白名单**：

```jsonc
"spire": {
  "cards": [], "characters": [], "skills": [],
  "charAccess": {
    "default": ["blade", "guard", "mage"],          // 普通用户：除 wuzhuge 外全部
    "vip":     ["blade", "guard", "mage", "wuzhuge"] // VIP：全部
  }
}
```

- 键 = `c_user_groups.code`；值 = 该组可选择的角色 id 数组（覆盖"全量生效角色池"= 引擎内置 `BASE_CHARACTERS` + 工坊自定义角色）。
- 「发布」时 `spire_published` 整体快照含 `charAccess`（`SpireContentController.spireOf` 统一出口）。
- 保存入口 `POST /api/spire-content` 对 `charAccess` 做类型净化：非对象→`{}`；值非数组→`[]`；数组元素只保留非空字符串（trim + 去重）。

### 1.2 fail-open 规则（重要）

| 情形 | C 端行为 |
| --- | --- |
| `charAccess` 缺失（未配置/旧数据） | **不筛选**，全部角色可选 |
| 玩家所属组在 `charAccess` 中**没有对应键** | **不筛选**，全部角色可选 |
| 该组存在白名单键 | 白名单内可选；白名单外锁定（置灰 + 🔒 + 提示，点击不触发开局） |

目的：未配置时绝不把玩家全锁死。B 端授权 Tab 对"未配置的组"按推荐规则**预填展示**（default = 除武诸葛外全勾；vip 与其它组 = 全勾），但**不落库**，直到点「保存授权」。

### 1.3 用户组种子

`DbSchema` 启动幂等补种子：`INSERT IGNORE INTO c_user_groups (code,name) VALUES ('vip','VIP用户')`
（`default`/`默认组` 为既有种子）。已存在的组**不改名**（现网 default 组名保持「默认组」，B/C 端展示文案由前端映射：default→普通用户、vip→VIP用户）。

## 2. 接口变更

| 接口 | 变更 |
| --- | --- |
| `GET /api/spire-content`（B 登录） | 响应新增 `charAccess`（缺省 `{}`）与 **`baseCharacters`**（只读常量：`[{id,name,icon}]`，镜像 notelab-c 引擎 `BASE_CHARACTERS`：blade 刃影 / guard 铁壁守卫 / mage 秘法编织者 / wuzhuge 武诸葛）。`baseCharacters` 不落库、不进 publish 快照 |
| `POST /api/spire-content`（B 登录） | 请求体可带 `charAccess`（净化后与三键一起合并写 `ui_config`，保留 background/menus 等其它键，200KB 体积校验不变） |
| `POST /api/spire-content/publish` | 快照 `spire_published` 现含 `charAccess`（走 `spireOf` 统一出口） |
| `GET /api/c/spire/content`（匿名） | 响应新增 `charAccess`（取自 `spire_published.charAccess`，缺省 `{}`），供 C 端本地筛选 |

未变更：`/api/c/auth/me`（仍返回 `{id,username,nickname,group_code}`）、`/api/c-admin/groups`、`/api/c-admin/users/{id}`（改组仍走它）。

## 3. B 端（notelab-b）

`src/app/(admin)/spire-editor/page.tsx` 新增第 4 个 Tab「👥 角色授权（N 组）」：

- 进入并行加载 `loadSpireContent()`（含 `charAccess`/`baseCharacters`）与 `GET /api/c-admin/groups`。
- 全量角色池 = `baseCharacters` + 工坊 `characters`（按 id 去重，标注「内置/工坊」）。
- 组切换用 `Radio.Group`（展示 `名称（code · N人）`）；该组勾选列表为 `Checkbox.Group`，带「全选 / 全不选 / 按推荐预填」。
- 「保存授权」= `saveSpireContent({cards,characters,skills,charAccess})` **整包提交**（漏带三键会覆盖丢失，故与工坊保存同入口同写法）；toast 提示"点「发布到 C 端」后生效"。
- `src/lib/spire-content.ts`：`SpireCustomContent` 增 `charAccess?: Record<string,string[]>` 与 `baseCharacters?`；`cleanCharAccess()` 净化；`saveSpireContent` 提交时剔除只读的 `baseCharacters`。
- 工坊既有「保存并应用」也会回带 `charAccess`，避免日常编辑把授权清空。

## 4. C 端（notelab-c）

`app/spire/page.tsx` 角色选择分支：

1. 挂载时 `loadSpireContent()` 取 `charAccess`；`fetchMe()` 静默取登录态 → `userGroup = me?.group_code || "default"`（**未登录/无 group_code 按 default**）。
2. `allowList = charAccess?.[userGroup]`；`charAccess` 缺失或无该组键 → `null`（不筛选）。
3. 渲染 `CHARACTERS.map`：`locked = allowList ? !allowList.includes(ch.id) : false`；
   锁定态 = `opacity-50 grayscale` + 去 hover 高亮 + 右上 🔒 角标 + `aria-disabled` + CTA 改「未解锁」+ 底部小字「该角色未解锁 · VIP 专属/联系管理员」；点击不触发 `pickCharacter`。
4. 顶部副标题补「当前身份：<普通用户|VIP用户>」+ 是否需授权解锁说明。
5. `pickCharacter` 内二次防御：白名单存在且不含该 id → 直接 return。
6. **未新增/未移除登录墙**：`app/spire/layout.tsx` 的 `RequireAuth` 为阶段3既有守卫（匿名访问 /games/spire 会被回跳 /games/login，此行为本次未改动）；页面内 `fetchMe` 仍为静默调用。

> 授权为"前置筛选/展示锁定"级别：整局状态在客户端，非服务端强校验边界。

## 5. 验收结果（2026-09-24，无头 Chrome 经 nginx :80 实测）

- java：`mvn -DskipTests package` 通过；`pm2 restart notelab-java` online。
  - `GET /api/spire-content`（B 会话）含 `charAccess` + `baseCharacters`（4 内置角色）✅
  - 净化：`{"vip":"notarray","default":[..,5,null," blade "]}` → `{"vip":[],"default":["blade","guard"]}` ✅
  - 保存+发布后匿名 `GET /api/c/spire/content` 含 `charAccess` ✅
  - `SELECT code,name FROM c_user_groups` = `default/默认组`、`vip/VIP用户` ✅
- notelab-b：`npm run build` 零错、restart online；E2E：Tab 存在、两组列出、default 预填除武诸葛外全勾、vip 全勾、勾选/取消即时生效、保存授权+发布 toast 正常、重进回显正确、卡片/角色/技能三 Tab 回归正常 ✅
- notelab-c：`npm run build` 零错、restart online；E2E（测试号 spiregate1，见下）：
  - default 组：武诸葛置灰+🔒+提示+aria-disabled，点击无反应；刃影可正常开局 ✅
  - 经 `PUT /api/c-admin/users/13` 改 vip 后：身份行「VIP用户」、武诸葛解锁、可开局进入地图 ✅
  - 临时组 tester（charAccess 无键）：fail-open 全可选 ✅（验收后已删组）
  - 无 group_code 回落：请求拦截伪造 me 无 group_code → 按 default 处理（武诸葛锁定）✅
  - 匿名：/games/spire 被既有 RequireAuth 回跳 /games/login（未改动守卫）；匿名接口透出 charAccess ✅
  - 各场景浏览器控制台无 error（仅守卫探测 me 的 401 资源日志，属既有行为）✅
- 回归：/games、/games/vs、/games/thunder、/games/trpg、/games/utils/translate、/admin/c-users、/admin/dashboard、/admin/perm 均 200；`/api/menu`、`/api/ui-config`、`/api/c/config/background` 均 200 ✅
- 截图（未入库，存服务器）：`/root/backups/spire-gating-shots-2026-09-24/`（c-default-pick / c-vip-pick / c-vip-run / c-failopen / c-fallback-default / c-anon-guard / b-access-default / b-access-vip / b-access-saved 等 10 张）
- MySQL 备份：`/root/backups/pre-spire-gating-2026-09-24.sql`（c_user_groups / ui_config / c_users）

## 6. 回滚方式

1. **配置回滚**（最快，不动代码）：B 端「尖塔内容工坊 → 角色授权」清空保存并发布；或直接还原 `ui_config`：
   `mysql notelab < /root/backups/pre-spire-gating-2026-09-24.sql`（该 dump 含 c_user_groups/ui_config/c_users 三表全量，会覆盖这三张表到备份时点——仅在确需整体回退时使用）。
   更温和：`UPDATE ui_config SET config = JSON_REMOVE(config, '$.spire.charAccess', '$.spire_published.charAccess')`（删两键即回 fail-open 全可选）。
2. **删 vip 组**（若无人使用）：B 端「C 端用户管理 → 用户组」删除 `vip`（成员为 0 才可删），或 `DELETE FROM c_user_groups WHERE code='vip'`（禁用 DROP/改表结构，仅删种子行）。
3. **代码回滚**：三仓各自 `git revert <commit>`（见各仓 git log；java=SpireContentController/CConfigController/DbSchema，b=spire-editor+lib，c=spire page+lib），构建重启即可；C 端旧版本读不到 `charAccess` 字段会自动忽略（向后兼容）。

## 7. 遗留事项

- **新增内置角色需同步 `SpireContentController.BASE_CHARACTERS` 常量**（否则 B 端授权界面看不到该角色，无法勾进白名单；C 端引擎侧照常可选，除非白名单显式排除）。
- 现网 default 组名为「默认组」，C 端展示文案固定映射为「普通用户」；如需改名走 B 端用户组重命名（仅影响管理端展示）。
- 授权仅前端展示级锁定（需求确认非强安全边界）；如需服务端 run 级校验需另立任务。
- 验收测试号 `spiregate1`（id=13，default 组）为本次新建，密码见 `/root/notelab-c/account.json`（已 gitignore，勿提交）；不用时可经 B 端删除。
