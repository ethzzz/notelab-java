# 任务简报 BC-P7：B 端 light/dark 双主题切换

> 背景：P6 已完成 B 端传统 antd 化（白底 Sider/Header + 浅灰内容区 #f0f2f5，15 业务页 antd 化）。
> 需求：B 端增加**深色主题**，提供 light / dark 切换，**就保持这两套主题**（不做更多主题）。
> 仓库：仅改 `/root/notelab-b`（Next 16 + React 19 + antd 6 + @ant-design/nextjs-registry + Tailwind v4）。
> 不动：`/root/notelab-java`、`/root/notelab-c`、`/root/myapp`、任何 API 契约、登录/会话机制。

## 设计要点

1. **主题模式持久化**：localStorage 键 `notelab_b_theme`，值 `light` | `dark`，默认 `light`。
2. **切换入口**：Header 右侧（用户信息左边）加一个 antd Button（type=text）或 Switch，图标用 @ant-design/icons 或 lucide 的 Sun/Moon（项目里两者都有，选一个即可），点击切换并持久化。
3. **antd 深色**：ConfigProvider `theme={{ algorithm: mode === "dark" ? theme.darkAlgorithm : theme.defaultAlgorithm }}`。注意现状：`components/AntdProvider.tsx` 包了 `AntdRegistry`——需要把 algorithm 变成动态的（可在 AntdProvider 内做 client 状态，或新建 ThemeProvider 包住 ConfigProvider，注意 nextjs-registry 的 SSR 样式收集不能丢）。
4. **防 SSR 闪烁**：在根 layout（`src/app/layout.tsx`）的 `<head>` 里内联一段小脚本，首帧前读 localStorage 并给 `<html>` 加/去 `dark` class（避免先亮后暗闪一下）。未设置时按 light。
5. **Tailwind v4 深色变体**：globals.css 加 `@custom-variant dark (&:where(.dark, .dark *));`，随后自定义样式处用 `dark:` 变体适配。切换时同步维护 `document.documentElement.classList`。
6. **非 antd 表面的深色适配**（重点检查，别漏）：
   - 外壳：内容区背景 #f0f2f5（暗色用 #141414 或 antd 深色惯例色）、Sider/Header 白底（暗色 #141414/#1f1f1f，含 border 色）、移动端 Drawer。
   - 登录/注册页：背景与卡片深色适配。
   - 页面内残留的自定义样式：chat 对话气泡、english、trpg/gen、lowcode 的自绘区域、spire-editor 画布/卡牌区（强交互区只调背景/文字/边框等外观，**不改交互逻辑**）。
   - 文字颜色：所有硬编码的 `text-zinc-800`/`text-zinc-500` 等在深色下不可读的地方加 `dark:` 对应色。
7. **scope 控制**：不要重写页面结构；只做主题适配。不要引入新的主题系统/第三方库。不要做第三套主题。

## 验证与提交

1. `cd /root/notelab-b && npm run build` 零错误。
2. `pm2 restart notelab-b`；curl `/admin/login` 200、`/admin/dashboard`（未登录 307 至登录）正常。
3. 自检清单写进执行记录：逐页过一遍残留浅色硬编码（可用 `grep -n "bg-white\|text-zinc-8\|#f0f2f5\|rgba(255"` 之类辅助排查）。
4. git 提交（若仓库无身份：`git -c user.name=notelab-b -c user.email=notelab-b@localhost commit`，不改 git config）。
5. 在 `/root/notelab-java/ops/BC-SPLIT-P7.md` 写执行记录（改动清单、实现方式、验证结果、提交号），PROGRESS.md 追加一节，归档提交。

## 重跑说明

- 若中途退出：看 `git status`/`git log` 从未提交处继续，不要推翻已提交部分。
- 事实备忘：notelab-b 目录为 `src/app/`；AntdProvider 位于 `src/components/AntdProvider.tsx`；globals.css 在 `src/app/globals.css`。
