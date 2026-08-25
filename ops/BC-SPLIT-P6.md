# BC-SPLIT-P6：B 端移除游玩功能 + 传统管理系统样式改造

> 日期：2026-08-25 · 执行仓库：/root/notelab-java（后端菜单树）、/root/notelab-b（B 端前端）
> 背景：B/C 拆分上线后，游玩（玩剧本/爬塔/吸血鬼幸存者）已挪到 C 端；B 端需移除残留游玩菜单/页面，
> 并从「主题背景+毛玻璃」游戏风改造为传统 antd 管理后台（白底 Sider/Header + 浅灰内容区）。

## 一、改动清单

### Part A：移除 B 端游玩功能

**notelab-java（commit b183951）**
- `model/MenuTree.java`：删除叶子 `trpg-play`(/trpg/play)、`vs`(/vs)、`spire`(/spire)；
  `g_games` 游戏中心组拍平为仅含 `trpg-gen`（生成剧本 /trpg/gen）。`g_gamecfg`（spire-editor）原样保留。
- `model/PageRoutes.java`：同步删除 `/trpg/play`、`/vs`、`/spire` 三条页面路由。
  由此 `PermService.registerAllRoutes` 启动 upsert 不再重建这三条 `perm_routes` 行（已实测验证）。
- 后端玩法 API（/api/trpg/plays 等）**保留未动**（C 端在用，且避免回归风险）。

**数据库（共享 MySQL notelab）**
- 删行前备份：`mysqldump notelab` → `/root/backups/notelab-before-p6-20260825-201209.sql`（79KB，Dump completed）。
- `DELETE FROM perm_routes WHERE code IN ('page:/trpg/play','page:/vs','page:/spire')`（3 行）；
  顺带清理悬空引用 `DELETE FROM perm_role_routes WHERE route_code IN (...)`（user 角色 3 行，备份已覆盖）。
- 重启 notelab-java 后复验：三行未被自动注册重建（page 路由 20 → 17）。

**notelab-b（commit 4cf3b38）**
- 删除页面：`src/app/(admin)/vs/`、`src/app/(admin)/spire/`、`src/app/(admin)/trpg/play/`；
  删除孤儿库 `src/lib/vs-engine.ts`（仅被 vs 页引用）。
- `/trpg` 重定向：Next 16 中页面级 `redirect()` 预渲染后返回 200+客户端跳转标记，
  为满足真实 307，改用 `next.config.ts` 服务端 redirects：`/trpg → /trpg/gen`（permanent:false）。
- `trpg/gen/page.tsx`：移除「开玩」「开始跑团」两个跳转 `/trpg/play` 的按钮（连同 useRouter/Play 图标引用）。
- 保留：`SpireCardView`（spire-editor 卡牌预览用）、`spire-engine`/`spire-content`（spire-editor 用）、`ScenarioPreview`（trpg/gen 预览用）。

### Part B：传统管理系统样式改造（功能与接口契约零变化）

**B1（commit f1151ad）**
- `(admin)/layout.tsx` 重写：antd `Layout` —— 白底固定 Sider（Logo+Menu，220px）+ 白底粘性 Header
  （移动端菜单按钮 / 用户头像+用户名+超级管理员 Tag+退出）+ `#f0f2f5` 浅灰内容区；移动端 Drawer 保留。
  移除：`resolveBgStyle`/`themes` 背景铺底、ThemePicker 入口、毛玻璃与深色主题适配；
  加载态/失效态改 antd Spin / Result。保留：/api/me 守卫 + rememberPath + /api/menu 数据源。
- `login/page.tsx`：浅灰底 + antd Card 居中表单（逻辑不变：查登录态/回跳被拦截路由）。
- `register/page.tsx`：静态说明页 antd 化。
- `dashboard/page.tsx`：渐变横幅 → antd Card 欢迎卡 + Row/Col 功能入口卡片。
- 已 antd 化页面去残留：perm/user-accounts/user-roles 的 403 态改 antd Result；
  各 Table 移除 `card overflow-hidden [&_.ant-table]:bg-transparent` 透明 hack（白底表格直接落在浅灰内容区）。

**B2（commit 23243a1）**
- `chat/page.tsx`：会话列表/消息区 Card 化，气泡去渐变改纯色（用户 indigo-500 白字 / AI zinc-50 描边），
  输入区 antd Input.TextArea + Button；SSE 消费与打字机逻辑逐行保留。
- `english/page.tsx`：同上外壳处理；场景选择改 antd Modal；麦克风改 antd Button（danger 态）；
  TTS/语音识别/打字机/贴底滚动逻辑保留（贴底滚动的滚动容器结构特意保持不变）。
- `trpg/gen/page.tsx`：ui/modal→antd Modal（ok/cancel/confirmLoading）、ui/form→antd Form+Input、
  ui/select→antd Select；删除/取消发布改 Modal.confirm；任务轮询与发布逻辑不变。
- `lowcode/page.tsx`：整体重写 —— antd Tabs 三页签；Card 面板；按钮/输入/开关/单选多选全 antd；
  导出弹窗改 antd Modal；localStorage 存取与 Schema/SQL 生成逻辑不变；节点/字段画布交互保留、配色中性化。
- `tools/page.tsx`：卡片网格 → antd Table（分类/类型 Tag、Switch 启停、编辑、Popconfirm 删除）+ 分类 Radio 筛选 + antd Modal 表单。
- `ui/page.tsx`：antd Card/Input/Radio/Button 化；主题网格保留（该页背景配置供 C 端使用，功能不变）；恢复默认改 Modal.confirm；toast 走 antd message。
- 组件清理：删除 `components/ui/{modal,confirm,select,form}.tsx`、`components/ThemePicker.tsx`（均无引用）。
- `ScenarioPreview.tsx` 配色中性化；`globals.css` 删除 .card/.btn-primary/.btn-ghost/.btn-danger/.input
  与 theme-dark 适配层（共 -51 行），保留 TTS 按钮/进度条/骨架/打字光标等功能样式。
- `lib/themes.ts` 保留（/ui 页仍需 THEMES/resolveBgStyle 为 C 端管理背景）。

## 二、验证结果

### /api/menu 前后对比（超管 Cookie，:8001 直连）

改动前 `g_games`：
```
[G] g_games 游戏中心
  [G] trpg 剧本跑团
    -  trpg-play 玩剧本 /trpg/play
    -  trpg-gen 生成剧本 /trpg/gen
  -  vs 吸血鬼幸存者 /vs
  -  spire 爬塔尖塔 /spire
```
改动后（重启后实测）：
```
[G] g_games 游戏中心
  -  trpg-gen 生成剧本 /trpg/gen
[G] g_gamecfg 游戏配置
  -  spire-editor 爬塔尖塔 /spire-editor
```
其余分组（AI对话/工具箱/用户管理/系统管理）无变化。

### 构建与进程
- `mvn -DskipTests package` ✅ 生成 target/notelab-java.jar；`pm2 restart notelab-java` ✅（Started in 5.192s，perm_routes=83）。
- `npm run build` ✅（22 路由，/trpg 已不在路由表，改为 config 级 redirect）；`pm2 restart notelab-b` ✅（Ready in 157ms）。

### curl 验证（:3020 直连与经 nginx :80 一致）
| 请求 | 结果 |
|---|---|
| `/admin/trpg` | **307 → /admin/trpg/gen**（nginx 同样 307） |
| `/admin/vs`、`/admin/spire`、`/admin/trpg/play` | **404** |
| `/admin/trpg/gen`、`/admin/spire-editor`、`/admin/login`、`/admin/register`、`/admin/dashboard` 等 16 页 | 200 |
| `/api/trpg/plays`（超管 Cookie） | 200，玩法 API 保留可用 |
| `/api/perm/overview`（超管 Cookie） | page 路由仅剩 `page:/trpg/gen`、`page:/spire-editor`（游戏相关） |
| `POST /admin/api/tts` | 200 audio/mpeg 11KB（英语页朗读依赖正常） |

### 数据库
- 备份：`/root/backups/notelab-before-p6-20260825-201209.sql`
- perm_routes：`page:/trpg/play`、`page:/vs`、`page:/spire` 已删，重启后未重建（COUNT=0）。

## 三、提交号
| 仓库 | commit | 内容 |
|---|---|---|
| notelab-java | `b183951` | 菜单树/页面路由表移除游玩 |
| notelab-b | `4cf3b38` | Part A 前端（删页/重定向/去入口） |
| notelab-b | `f1151ad` | Part B1 外壳+登录+仪表盘+表格 hack 清理 |
| notelab-b | `23243a1` | Part B2 业务页全量 antd 化 + 组件清理 |

## 四、未做/说明
- `perm_role_routes` 仅清理了三条悬空 code，其余角色授权未动（超管天然全量，无需处理）。
- TTS 错误日志中曾见 `Kokoro HTTP 401: Missing Authorization header`（P6 之前遗留记录，未复现；
  P6 期间实测 /admin/api/tts 200 正常）——与本次改动无关，留档备查。
- C 端（notelab-c）与 myapp、Python 版零触碰；/api/c/* 未动。
