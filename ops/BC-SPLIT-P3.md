# B/C 拆分 · 阶段 3：C 端前端 notelab-c（游戏中心 + 新外壳）

日期：2026-08-25　服务器：117.72.32.87

> 本阶段为第二次执行：上一次因模型服务中断退出，仅留下占位壳（无半成品代码）。
> 本次开工前确认 /root/notelab-c 只有阶段 0 占位页，故全量重建（保留 node_modules/.next 之外全部重写）。

## 本阶段做了什么

把 C 端壳 /root/notelab-c（:3010，pm2 `notelab-c`）建设为消费级「NoteLab 游戏中心」：
新版外壳（顶部导航 + 移动端底部 Tab + 全局主题背景 + 登录态下拉）+ 落地页 + 登录页
+ 三个游戏页（TRPG / 爬塔 / 吸血鬼幸存者，从 /root/myapp 原样复制，仅改接口路径与外壳）。

### 路由清单（app/ 目录，Next 16.2.12 App Router）

| 路由 | 页面 | 登录要求 |
|------|------|----------|
| `/` | 落地页：Hero + 三张游戏卡片（匿名可见） | 否 |
| `/login` | 登录（POST /api/c/auth/login，错误行内+toast 展示后端文案） | 否 |
| `/trpg` | 剧本列表（仅已发布；预览弹窗 + 进行中存档提示） | 是 |
| `/trpg/play` | TRPG 游玩（`?sid=X` 自动开局，与 myapp 一致） | 是 |
| `/spire` | 爬塔（自定义内容走 GET /api/c/spire/content 匿名接口） | 是 |
| `/vs` | 吸血鬼幸存者（纯前端，进度 localStorage `c_vs-meta`/`c_vs-best`） | 是 |

受保护路由经 `components/RequireAuth.tsx`（各段 layout.tsx 包裹）：进入前 `GET /api/c/auth/me`，
401 → `rememberPath(当前路径)` → `/login`；登录成功后 `takeRedirectPath()` 回跳（无记录回首页）。

### 外壳要点（components/Shell.tsx）

- 顶部导航（h-14 毛玻璃）：Logo「NoteLab」+ 桌面端四入口 + 右侧登录按钮 / 头像昵称下拉（查看信息弹窗、退出登录）。
- 移动端（<768px）：底部 Tab（首页/TRPG/爬塔/我的），顶栏收窄；主内容 `p-3 md:p-7` 保证游戏页高度预算（≤140px chrome）与 myapp 一致。
- 背景：挂载时匿名 `GET /api/c/config/background` → `resolveBgStyle`；失败回退默认色；深色主题（themes.ts dark 标记）挂 `.theme-dark` 反色，**不含 ThemePicker**。
- 样式语言沿用 myapp：globals.css（card/btn-primary/btn-ghost/btn-danger/input + theme-dark 适配层，剔除未用的 TTS/骨架样式）、Tailwind v4、ui/* 毛玻璃组件。
- next.config.ts 保持空配置（**无任何 /api rewrites**，/api 全由 nginx 代理）。

### 与 myapp 的差异（仅任务允许的范围）

1. 接口路径：`/api/trpg/*` → `/api/c/trpg/*`（游玩页 8 处）；spire 内容 `/api/spire-content` → `GET /api/c/spire/content`（只读，去掉 B 端 save 函数）。
2. VS 进度 localStorage key 加 `c_` 前缀（`vs-meta`/`vs-best` → `c_vs-meta`/`c_vs-best`）。
3. 游玩页剧本选择器空态文案：「剧本库是空的，先去「生成剧本」菜单创作一个吧」→「还没有发布的剧本，敬请期待」（C 端无生成入口）。
4. 登录/回跳：`/api/c/auth/*`，localStorage key `notelab-c.redirect`，回跳 fallback `/`（myapp 为 `/dashboard`）。
5. 三个游戏页的引擎与交互逻辑零改动（spire-engine / vs-engine / trpg 类型、TypingText、ScenarioPreview、SpireCardView、ui/* 均原样复制）。

### 依赖（对齐 myapp 版本）

next 16.2.12 / react 19.2.4 / react-dom 19.2.4 / tailwindcss ^4 / @tailwindcss/postcss ^4 /
lucide-react ^1.28.0 / sonner ^2.0.7 / clsx ^2.1.1 / tailwind-merge ^3.6.0（未引入 antd、radix、react-markdown 等未用包）。

## 验收结果（全部通过）

1. ✅ `npm run build` 零错误零警告；`pm2 restart notelab-c` 后 `/`、`/login`、`/trpg`、`/trpg/play`、`/spire`、`/vs` 均 200（直连 :3010）。
2. ✅ 经 nginx：`curl http://127.0.0.1/` 200 返回新首页（HTML 含「NoteLab 游戏中心」、三张游戏卡片、「开始游玩」）；
   `/api/c/config/background` 注入逻辑在 Shell 客户端 JS 中（已验证产物 chunk 内含该 URL 与 `/api/c/auth/me`）。
3. ✅ 接口链路（经 nginx :80）：`POST /api/c/auth/login`（ctest1/newpass888，阶段 1 保留账号）200 + Cookie →
   `GET /api/c/auth/me` 200 → `GET /api/c/trpg/scenarios` 200。
   **当前库内无已发布剧本，列表为空态**（页面文案「还没有发布的剧本」）。
   为验证全链路，临时将剧本 3「雾港惊魂」置 published=1 实测：列表可见 → 详情/预览（26 节点）→
   `POST .../play` 开局（play_id=12，start 节点 3 选项含骰子）→ `choose c1` 推进（deck_search，history=1）→
   plays 列表/详情 200 → `DELETE` 200 → plays 归空；随后 published 复位为 0，列表恢复空态。**测试数据已全部清理**。
4. ✅ 未登录访问受保护接口 401「请先登录」；页面层由 RequireAuth 客户端守卫记录路径并引导 /login
   （/trpg SSR HTML 含守卫占位，逻辑经代码走查 + 构建零报错保证）；错误密码 401「用户名或密码错误」行内展示；
   `POST /api/c/auth/logout` 200 后会话即失效（me → 401）。
5. ✅ 零影响回归：myapp:3000 `/` 307（原有重定向行为）且 `/api/menu` 401 正常；notelab-b:3020/admin 200；
   notelab-java:8001 `/api/menu` 401；Python:8000 `/docs` 200；nginx `/admin` 200。
   pm2 六进程 online，其余五个进程重启次数与阶段前完全一致（未被触碰）。
6. ✅ /root/notelab-c 初始化 git 仓库并提交；`/api/c/spire/content` 匿名 200（当前无已发布内容 → 空三数组，爬塔回落内置内容）。

## 回滚方法

回到阶段 0 占位壳：
```bash
cd /root/notelab-c
git log --oneline                 # 本次提交为「B/C拆分阶段3：…」
# 占位壳未单独留提交，如需恢复占位页：用 app/layout.tsx + app/page.tsx 两个占位文件替换现路由，
# 删除 lib/ components/ 与新增路由目录，npm run build && pm2 restart notelab-c
# 快速止血（不动代码）：nginx 已可把 / 指回任意端口（改 /etc/nginx/sites-available/notelab 的 :3010 目标后 reload）
```

## 遗留事项

- 库内暂无已发布 TRPG 剧本与 Spire 发布内容：需 B 端（notelab-b 后续阶段）提供发布操作后，C 端列表/自定义内容才有数据。
- 注册未开放（`C_REGISTER_OPEN=false`）：C 端账号仍需经 /api/c-admin 创建，登录页已有对应文案。
- 页面交互层（登录表单、下拉、游戏操作）未做真实浏览器自动化测试，依赖构建零报错 + 代码走查 + 接口层 curl 验证。
