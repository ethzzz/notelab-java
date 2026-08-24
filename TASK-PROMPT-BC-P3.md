# B/C 端拆分 · 阶段 3：C 端前端 notelab-c（游戏中心 + 新外壳）

你在服务器（117.72.32.87）。这是「B/C 端拆分」的**阶段 3**：把 C 端壳应用 /root/notelab-c（:3010，pm2 `notelab-c`）建设为真正的 C 端产品。
后端阶段 1/2 已完成：先读 /root/notelab-java git log 与相关 controller 代码确认可用接口，**接口契约以实际代码为准**。

## 重跑说明（必读）

这是本阶段的**第二次执行**：上一次因模型服务中断（500）退出，代码写了一部分但未构建未提交。开工前先检查 /root/notelab-c 现状：
- 若已有部分新文件（布局/页面），逐文件评估：与本文档要求一致则保留继续，不一致或不完整则重写。
- **目录结构事实**：壳应用用的是 `app/` 目录（非 `src/app/`），新代码直接放 `app/`、`lib/`、`components/` 下，与壳保持一致；下文提到的 myapp 路径都是 /root/myapp 的（它有 src/），复制到 notelab-c 时去掉 src/ 前缀并相应调整 `@/` 别名（核对 tsconfig.json 的 paths）。
- notelab-c 尚无 git 仓库；若上次残留了半成品且无法判断来源，直接覆盖重写（本阶段允许全量重建 /root/notelab-c 下除 node_modules/.next 外的内容）。

## 背景事实（已核实，直接采信）

- 现有前端 /root/myapp（Next App Router + Tailwind v4 + 自定义毛玻璃组件）。C 端**保留这套自定义样式**，**不引入 antd**。
- 三个游玩页及其依赖（从 /root/myapp 读取确认，注意 myapp 是 `src/` 前缀）：
  - `src/app/(admin)/trpg/play/page.tsx`（TRPG 游玩，实际依赖：api、sonner、ui/modal、TypingText、confirmDialog、lucide；**不依赖 ScenarioPreview**——但可用 GET /api/c/trpg/scenarios/{id} + ScenarioPreview 给剧本列表做「预览」弹窗）+ `src/lib/trpg.ts` + `src/components/TypingText.tsx` + `src/components/ScenarioPreview.tsx`
  - `src/app/(admin)/spire/page.tsx`（爬塔）+ `src/lib/spire-engine.ts` + `src/lib/spire-content.ts` + `src/components/SpireCardView.tsx`
  - `src/app/(admin)/vs/page.tsx`（吸血鬼幸存者）+ `src/lib/vs-engine.ts`（纯前端，进度存 localStorage）
  - 公共：`src/components/ui/*`（confirm/empty/form/modal/select）、`src/lib/utils.ts`、`src/lib/themes.ts`（THEMES + resolveBgStyle）、`src/lib/api.ts`；依赖包注意核对（如 sonner、lucide-react、react-markdown 等，以 myapp package.json 为准按需引入）
- C 端后端接口（阶段 1/2 已上线）：
  - 认证：`POST /api/c/auth/login` {username,password}、`GET /api/c/auth/me`、`POST /api/c/auth/logout`（Cookie `notelab_c_session`）
  - 游玩：`GET /api/c/trpg/scenarios`（仅已发布）、`GET /api/c/trpg/scenarios/{id}`、`POST /api/c/trpg/scenarios/{id}/play`、`GET /api/c/trpg/plays`、`GET /api/c/trpg/plays/{id}`、`POST /api/c/trpg/plays/{id}/choose`、`DELETE /api/c/trpg/plays/{id}`
  - 配置：`GET /api/c/config/background`（匿名）、`GET /api/c/spire/content`（匿名，已发布内容）
- nginx :80 已就绪（/ → :3010、/api → :8001）；**notelab-c 的 next.config 不要加任何 /api rewrites**。
- TRPG/SSE 说明：TRPG 游玩接口是普通 JSON 请求，无 SSE。

## 硬性纪律

1. 不改 /root/myapp、/root/notelab、/root/notelab-java、/root/notelab-b、nginx 配置。
2. Next/React/Tailwind 版本对齐 /root/myapp/package.json（照抄依赖版本）。
3. 三个游戏页的**游玩行为与 myapp 逐行为一致**（直接复制页面与引擎，只改：接口路径 /api/* → /api/c/*、登录态来源、外壳布局）。
4. 每个里程碑 `npm run build` 必须零报错；完成后 `pm2 restart notelab-c` 生效。

## 实施内容

### 1. 站点骨架（替换阶段 0 的占位页）
- app 路由：`/`（首页）、`/login`、`/trpg`（剧本列表）、`/trpg/play`、`/spire`、`/vs`。
- **流行消费端布局**：
  - 顶部导航条：Logo「NoteLab」、游戏入口链接、右侧登录按钮 / 登录后头像+昵称下拉（查看信息、退出登录）。
  - 首页 = 落地页：Hero 区（标题+副标题）+ 三张游戏卡片网格（TRPG 文字冒险 / 爬塔 / 吸血鬼幸存者，带 emoji 图标、简介、「开始游玩」入口）。
  - 移动端（<768px）：底部 Tab 栏（首页 / TRPG / 爬塔 / 我的），顶栏收窄。
  - 风格：沿用 myapp 的毛玻璃卡片 + 圆角 + 阴影语言（读 (admin)/layout.tsx 与 components/ui 吸收风格），但布局是消费端产品样式而非后台样式。
- **背景**：全局布局组件启动时 `GET /api/c/config/background`（匿名），拿到后走 `resolveBgStyle` 应用；请求失败用默认色；**不要 ThemePicker**。
- 深色主题适配：background 命中 dark 主题时文字反色（沿用 themes.ts 的 dark 标记，参考 myapp 外壳的做法）。

### 2. 登录与路由守卫
- `/login`：username/password 表单 → `POST /api/c/auth/login`；错误显示后端 error 文案；成功后跳回来源页（沿用 myapp 的 rememberPath 思路：未登录访问受保护页 → 记录目标 → 登录后回跳）。
- 受保护页：`/trpg`、`/trpg/play`、`/spire`（进入前 `GET /api/c/auth/me`，401 → 去 /login）。`/vs` 是否要求登录：与 trpg/spire 一致，要求登录。
- 退出登录 → `POST /api/c/auth/logout` → 回首页。

### 3. 三个游戏页接入
- **TRPG**（`/trpg` + `/trpg/play`）：列表用 `GET /api/c/trpg/scenarios`；开局 `POST /api/c/trpg/scenarios/{id}/play`；游玩页的存档列表/读取/抉择/删除全部换成 `/api/c/trpg/plays*`。空列表文案友好（「还没有发布的剧本」）。
- **Spire**（`/spire`）：自定义内容来源从 `/api/spire-content` 换成 `GET /api/c/spire/content`（匿名，引擎净化注册逻辑原样保留）；游玩进度存储方式与 myapp 保持一致（照抄其 localStorage/接口用法，只换内容接口）。
- **VS**（`/vs`）：引擎与页面原样复制，进度 localStorage key 加 `c_` 前缀避免与 B 端同域冲突（经 nginx 后 B/C 不同域其实天然隔离，但仍加前缀求稳）。

### 4. 验证（全部通过才算完成）
用阶段 1 留下的测试 C 账户（若已清理则经 /api/c-admin 建一个）在浏览器语义下验证（curl 可覆盖接口层，页面渲染用 `curl http://127.0.0.1:3010/...` 检查 HTML 与状态码；交互层靠构建零报错+代码走查保证）：
1. `npm run build` 零错误零警告（警告需逐条说明）；`pm2 restart notelab-c` 后 / 首页、/login、/trpg、/spire、/vs 均 200。
2. 经 nginx：`curl http://127.0.0.1/` 返回新首页（非占位页）；背景接口返回内容已注入或首屏请求可达（检查 HTML/JS 逻辑）。
3. 接口链路：登录 → /api/c/auth/me 200 → TRPG 列表（仅已发布剧本，若库内无已发布剧本则为空态，报告里说明）→ 未登录访问受保护页被引导 /login。
4. myapp（:3000）、notelab-b（:3020）、notelab-java（:8001）、Python（:8000）均不受影响。
5. 若过程中建了临时测试数据（剧本等），清理并在报告说明。

### 5. 提交与报告
- /root/notelab-c 初始化 git 仓库（若尚无）：`git init && git add -A && git commit -m "B/C拆分阶段3：C端前端（游戏中心三游戏+消费端外壳+/api/c接入）"`。
- 在 /root/notelab-java/ops/BC-SPLIT-P3.md 写阶段记录（做了什么、路由清单、验收结果、回滚=回到占位壳）。
- 最终输出简明报告：改动清单、验证结果逐条、遗留事项。
