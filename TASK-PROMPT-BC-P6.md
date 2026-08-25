# 任务简报 BC-P6：B 端移除游玩功能 + 传统管理系统样式改造

> 背景：B/C 拆分已上线（nginx `/` → notelab-c:3010，`/admin` → notelab-b:3020 basePath=/admin，`/api/*` → notelab-java:8001）。
> 用户反馈两个问题：① B 端样式仍是旧风格（阶段4 只换了外壳 组件，页面是 myapp 原样复制，仍带主题背景+毛玻璃）；② B 端还残留已挪到 C 端的游玩功能菜单与页面。
> 本任务目标：**B 端看起来是传统 antd 管理后台，且不再包含任何游玩功能**。

## 仓库与纪律

- 改动仓库：`/root/notelab-java`（后端菜单树）、`/root/notelab-b`（B 端前端）。
- **不要动**：`/root/notelab-c`、`/root/myapp`、`/root/notelab`（Python）、C 端接口 `/api/c/*`、B 端登录/会话/cookie 机制（`notelab_session` token 格式与 Python 版互认，严禁改动）。
- 共享 MySQL = 生产：任何删行前必须先 `mysqldump notelab` 备份。
- git：每完成一部分就提交一次；若仓库无身份，用 `git -c user.name=notelab-b -c user.email=notelab-b@localhost commit`（notelab-java 同理），**不要修改任何 git config**。
- SSH 注意：本任务在服务器上直接执行，无需 ssh。

## Part A：移除 B 端游玩功能（先做，独立提交）

已挪到 C 端的是三个游戏的**游玩**：TRPG 玩剧本、爬塔、吸血鬼幸存者。B 端保留**配置/生成**能力。

1. **菜单树**（`notelab-java/src/main/java/com/notelab/model/MenuTree.java`）：
   - 删除叶子：`trpg-play`（/trpg/play）、`vs`（/vs）、`spire`（/spire）。
   - `g_games` 游戏中心组保留 `trpg` 子组，其中只剩 `trpg-gen`（生成剧本 /trpg/gen）。也可把 `trpg` 子组拍平为直接叶子，二选一，保持树合法即可。
   - `g_gamecfg` 游戏配置组（spire-editor /spire-editor）原样保留。
   - 空分组剪枝逻辑（UiConfigService.buildNodes）已存在，无需改。
2. **前端页面**（`/root/notelab-b/src/app/(admin)/`）：
   - 删除目录：`vs/`、`spire/`、`trpg/play/`。
   - `trpg/page.tsx`：重定向目标由 `/trpg/play` 改为 `/trpg/gen`，注释同步改。
   - 检查删除后无残留 import（`ScenarioPreview` 仍被 /trpg/gen 使用，保留；`SpireCardView` 若仅被已删页面引用则一并删除，被 spire-editor 用则保留——自行核实）。
   - 确认 `/api/trpg/play*` 等后端 API **保留不动**（仅去菜单与页面，避免回归风险）。
3. **权限路由表清理**（可选但建议）：查 `perm_routes` 中 path 为 `/trpg/play`、`/vs`、`/spire` 的行。先确认 Java 启动自动注册逻辑（PermService/DbSchema）是否会把它们重新插回：
   - 若不会自动重建 → 备份后删除这些行；
   - 若会自动重建 → 保留行不动，仅靠菜单树隐藏（可接受）。
4. 验证（Part A 提交前）：
   - `mvn -DskipTests package` 通过；`pm2 restart notelab-java`。
   - 用超管 Cookie 调 `/api/menu`，确认返回的菜单中无 玩剧本/吸血鬼/爬塔（/spire）叶子；生成剧本、爬塔尖塔（/spire-editor）仍在。
   - 超管 Cookie 签发：按 `Session.java` 算法（SECRET_KEY 在 /root/notelab/.env，uid=18 admin super_admin），此前各阶段已有现成做法可复用（见 ops/ 下归档）。
   - `cd /root/notelab-b && npm run build` 通过；`pm2 restart notelab-b`；curl `/admin/trpg` 应 307/308 跳 `/admin/trpg/gen`；`/admin/vs`、`/admin/spire` 返回 404。

## Part B：传统管理系统样式改造（可分多次提交）

目标视觉：**传统 B 端管理后台**——左侧白底 Sider + 顶部白底 Header + 浅灰内容区（如 #f0f2f5），内容以 antd Card/Table/Form 为主；**去掉主题背景铺底、毛玻璃、渐变游戏风**。功能与接口契约一律不变。

1. **外壳** `src/app/(admin)/layout.tsx`：
   - 用 antd `Layout`（Sider fixed 白底带 Logo + Menu，Header 白底：面包屑可选 + 用户信息/超级管理员 Tag/退出），Content 浅灰背景。
   - **移除**：`resolveBgStyle`/`themes.ts` 背景铺底、ThemePicker 入口（背景仍由 /ui 界面配置页管理，那是给 C 端用的）。
   - 保留：`/api/me` 守卫 + rememberPath + `/api/menu` 数据源 + 移动端 Drawer。
   - 加载态/失效态用 antd Spin/Result 即可，去掉自定义渐变。
2. **登录页** `src/app/login/page.tsx`：antd Form 居中卡片，朴素背景（浅灰/淡蓝渐变均可），去掉游戏风。
3. **15 个业务页全量 antd 化**（这是重点，阶段4 未做）：逐页把自定义样式组件换成 antd：
   - 列表/管理类（c-users、user/accounts、user/roles、perm、ui、tools、toolbox、dashboard、arena、extract、rag）：Table（分页/筛选/操作列）+ Modal/Form（Drawer 亦可）+ message 提示 + Popconfirm 删除。
   - 生成/流式类（chat、english、trpg/gen、lowcode）：SSE 流式逻辑与交互规范**保持不变**（打字机、等待态、错误提示），只把外壳样式换成中性 antd 风格（Card 包裹、Button 换 antd、输入区用 Input.TextArea + antd Button）。对话气泡可用朴素气泡样式，不必 antd 化气泡本身。
   - 编辑器类（spire-editor）：页面框架/表单/弹窗/表格 antd 化；画布/卡牌预览等强交互区可保留现有实现，只统一外观为中性配色。
   - 组件替换：`components/ui/*`（modal/confirm/select/form）→ antd Modal/Popconfirm/Select/Form；`ToastHost` → antd `message`/`notification`（AntdProvider 已接 nextjs-registry，确认 message 静态方法在 App Router 下可用，必要时用 App.useApp）。替换完成后删除不再引用的自定义组件文件。
4. **globals.css**：移除毛玻璃/主题背景相关工具类（确认无引用后）；保留必要的排版样式。
5. 验证（每次提交前）：`npm run build` 零错误；`pm2 restart notelab-b`；curl `/admin/login`、`/admin/dashboard`（未登录应跳登录）状态码正常。

## 收尾

1. 两仓库分别提交（notelab-java 可一次提交；notelab-b 建议 Part A 一次、Part B 可 1~2 次）。
2. 在 `/root/notelab-java/ops/BC-SPLIT-P6.md` 写执行记录：改动清单、验证结果（含 /api/menu 前后对比、构建输出、404/307 验证）、提交号。
3. 更新 `/root/notelab-java/PROGRESS.md` 追加一节。
4. 最终报告列出：提交号、删除的页面/菜单清单、未做的事项及原因。

## 重跑说明（如任务中断）

- 若因模型服务中断等原因中途退出：先看两仓库 `git status` 与 `git log` 判断进度，**从未提交处继续**，不要推翻已提交部分。
- 已验证可用的事实：notelab-b 用 `src/app/` 目录；菜单树硬编码在 MenuTree.java；ui_config 顶层键为 menus/spire/background（单行 config JSON）。
