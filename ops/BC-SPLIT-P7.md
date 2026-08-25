# BC-SPLIT-P7：B 端 light/dark 双主题切换（执行记录）

- 日期：2026-08-25
- 仓库：仅改 `/root/notelab-b`（Next 16 + React 19 + antd 6 + @ant-design/nextjs-registry + Tailwind v4）
- 未动：`/root/notelab-java`、`/root/notelab-c`、`/root/myapp`、任何 API 契约、登录/会话机制
- 提交：notelab-b `f15a058`（主题功能本体，24 文件 +295/-202）、`d89402f`（gitignore 忽略 *.tsbuildinfo）

## 一、改动清单

### 主题基建（3 文件）
| 文件 | 改动 |
|---|---|
| `src/components/AntdProvider.tsx` | 重写：新增 `ThemeMode`/`THEME_STORAGE_KEY`（`notelab_b_theme`）/`ThemeContext` + `useTheme()`；ConfigProvider `algorithm` 随模式在 `theme.darkAlgorithm`/`theme.defaultAlgorithm` 间切换（token colorPrimary/borderRadius 不变）；水合后读 localStorage 并同步 `<html>.dark` class；切换仅两个方向，无第三套主题 |
| `src/app/layout.tsx` | `<body>` 顶部内联防闪烁脚本：首帧前读 `notelab_b_theme`，`dark` 则给 `<html>` 加 `.dark`（未设置按 light）；`<html>` 加 `suppressHydrationWarning`（脚本在水合前改 class 属预期）。说明：Next App Router 的 `<head>` 由框架托管，官方内联写法即 body 首个子节点，同步执行早于任何绘制，效果等同 head 内联 |
| `src/app/globals.css` | `@custom-variant dark (&:where(.dark, .dark *));`；`.dark` 覆盖 `--background:#141414`/`--foreground:#e4e4e7`（body 底色随变量自动深色）；自定义件深色覆盖：`.dark .speak-btn`、`.dark .speak-btn-playing`、`.dark .tts-shimmer`、`.dark .skeleton-line`（流光骨架改深灰） |

### 外壳与登录（3 文件）
- `(admin)/layout.tsx`：Sider（含移动端 Drawer 内）/Header/内容区深色适配——白底→`dark:!bg-[#1f1f1f]`（Header）、`#f0f2f5`→`dark:bg-[#141414]`（外壳与内容区，antd Layout 默认色用 `!` 覆盖）、border `#f0f0f0`/zinc-100→`dark:border-zinc-800`；**Header 右侧（用户信息左边）新增切换按钮**：antd `Button type="text"` + lucide `Sun`/`Moon`（图标为目标模式），点击 `toggleTheme()` 持久化；Sider 仍 `theme="light"`（深色下 antd 自动取 colorBgContainer=#141414），认证守卫逻辑零改动。
- `login/page.tsx`、`register/page.tsx`：页面底 `dark:bg-[#141414]`，标题/说明文字 dark 对应色；卡片本身随 antd darkAlgorithm。

### 业务页残留浅色适配（18 文件，机械映射 + 逐页人工核对）
统一映射（perl 守卫替换，避免误伤 `hover:`/`dark:` 前缀与子串）：
`text-zinc-800/700/600/500/400 → dark:text-zinc-100/200/300/400/500`；`bg-white → dark:bg-[#1f1f1f]`；`bg-zinc-50/100 → dark:bg-zinc-800/60 / dark:bg-zinc-800`；`border-zinc-100/200/300 → dark:border-zinc-800/700/600`；`bg-black/[0.02|0.04]、border-black/5 → dark:bg-white/[0.04|0.06]、dark:border-white/10`。

逐页人工补充的重点表面：
- **chat / english**：会话侧栏选中项 `bg-indigo-50 text-indigo-600 → dark:bg-indigo-500/15 dark:text-indigo-300`；english 语法纠错卡 `bg-amber-50/border-amber-200/text-amber-700 → dark:bg-amber-400/10 dark:border-amber-400/25 dark:text-amber-300`；场景选择按钮 `hover:bg-indigo-50 → dark:hover:bg-indigo-500/10`；气泡（用户 indigo 实底 / AI `bg-zinc-50+border`）随映射适配。**SSE 流式、打字机、TTS、纠错逻辑一行未动。**
- **lowcode**：流程画布节点/连接线/＋按钮、KIND_COLOR 四色图标底（emerald/amber/indigo/zinc）、表单设计器画布块；导出 JSON 的 `bg-zinc-900` pre 为刻意深色保留。
- **spire-editor**：Tabs/Table/表单弹窗随 antd；被动画布块（`bg-black/[0.02]` 系）加深色对应；`SpireCardView` 卡面美术（attack 锌灰渐变、`#2b1b1b` 描述底、`#40100e` 角饰等）为固定卡面设计**不随主题反转**（与游戏内一致）。
- **ui 页**：主题卡选中 `ring-indigo-100 → dark:ring-indigo-500/30`、预览边框、Card 高亮底 `!bg-indigo-50/40 → dark:!bg-indigo-500/10`；`src/lib/themes.ts` 是 **C 端背景数据**（渐变里的 `rgba(255,255,255)` 白点星光）不属 B 端表面，未动。
- **其余**（dashboard/arena/rag/extract/toolbox/tools/perm/c-users/user/*）：indigo 标题/徽标、rag 文档行与引用块、arena 模型选择按钮边框等补 dark 对应色；所有 antd Card/Table/Modal/Form/Select/Drawer/Upload/message 由 darkAlgorithm 自动深色。

**交互逻辑零改动**：全部改动仅 className/内联背景迁移到类（外壳两处 `style={{background:"#f0f2f5"}}` → className）+ AntdProvider 状态。

## 二、验证结果

1. **构建**：`cd /root/notelab-b && npm run build` ✅ Compiled successfully in 19.3s，TypeScript 0 错，22 路由生成（另 `npx tsc --noEmit` 独立通过）。
2. **进程与路由**：`pm2 restart notelab-b` 后
   - `GET /admin/login` **200**；`GET /admin/register` 200；`/admin/trpg` **307 → /admin/trpg/gen**（P6 跳转未破坏）。
   - `GET /admin/dashboard` 未登录 **200**（与 P6 记录一致：守卫为客户端 `/api/me` 401 → rememberPath → `router.replace("/login")`，浏览器内等效 307 至登录；本任务未改守卫）。
   - 超管 Cookie（uid=18，SECRET_KEY 与 Python 版同源签发）下 16 个 `(admin)` 页全 **200**：arena/c-users/chat/dashboard/english/extract/lowcode/perm/rag/spire-editor/toolbox/tools/trpg-gen/ui/user-accounts/user-roles。
   - `GET :8001/api/me`（超管 Cookie）→ `{"id":18,"username":"admin","role":"super_admin"}` ✅（会话机制未受影响）。
3. **产物检查**：
   - 首帧脚本在 `/admin/login` SSR HTML body 顶部原样输出（`localStorage.getItem("notelab_b_theme")` → `<html>.dark`）✅
   - 编译后 CSS 含 `.dark{--background:#141414…}`、`.dark .speak-btn` 及 `dark\:bg-/text-/border-/hover/ring-` 全系选择器 ✅
4. **自检清单**（grep 排查残留，`(?<![:\w-])…(?! dark:)` 守卫防误报）：
   - 裸 `bg-white`：**0**；裸 `bg-[#f0f2f5]`：**0**
   - 裸 `text-zinc-[4-8]00`：仅 1 处——`(admin)/layout.tsx` 的 Moon 图标（**仅在亮色模式渲染**，预期）；`SpireCardView` 卡面 `text-zinc-900/100` 系固定美术（预期）
   - `rgba(255,…)`：仅 `src/lib/themes.ts`（C 端背景数据，非 B 端表面）
   - 全仓 `dark:` 变体共 **230** 处（24 文件）；无双写 `dark:…dark:…` 残留
5. **行为说明（预期差异，非缺陷）**：深色用户刷新时，首帧页面底/文字已由内联脚本压暗（无白闪），antd 组件样式在水合后首个 effect 切到 darkAlgorithm（约 1 帧），为 nextjs-registry SSR 收集约束下的标准方案；未设置主题默认 light。

## 三、重跑说明
- 断点恢复：看 `git status`/`git log`，本体提交为 `f15a058`；若缺失则按上文映射规则对未提交文件补 `dark:` 变体后重建。
- 主题状态全在浏览器侧（localStorage `notelab_b_theme`），服务端无状态，重启不丢。
