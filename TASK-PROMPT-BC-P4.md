# B/C 端拆分 · 阶段 4：B 端前端 notelab-b（basePath + 15 页全量 antd 化）

你在服务器（117.72.32.87）。这是「B/C 端拆分」的**阶段 4**：把 B 端壳应用 /root/notelab-b（:3020，pm2 `notelab-b`，nginx 经 /admin 暴露）建设为完整的 antd 管理后台，承接原 /root/myapp 的全部功能。
后端阶段 1/2 已完成（/api/c-admin/*、剧本发布、spire 发布均已上线）。**接口契约以 /root/notelab-java 实际代码为准**。

## 背景事实（已核实，直接采信）

- 原站点 /root/myapp：Next App Router + Tailwind v4 + 自定义毛玻璃组件（`components/ui/*`）。全部功能页在 `src/app/(admin)/` 下共 19 个 page.tsx；外壳在 `(admin)/layout.tsx`（认证守卫 /api/me + 菜单 /api/menu + 背景 background + ThemePicker）；登录页在 `app/login/`；`lib/api.ts`（api/apiJson/postJson/rememberPath/takeRedirectPath）。
- `components/AntdProvider.tsx` 已存在于 myapp（说明 antd 依赖可能已在 package.json——先核实；没有则按最新 antd 5 安装，用 @ant-design/nextjs-registry 处理 SSR 样式）。
- 菜单数据来自 `/api/menu`（含 menu 树 + background），菜单过滤与权限由后端完成，前端照单渲染。
- nginx：/admin → :3020（保留前缀），/api → :8001 直达。**next.config.ts 设 `basePath: "/admin"`，不要加任何 /api rewrites**。
- SSE 接口：/api/chat、/api/arena（对抗流式）；TRPG 生成 /api/trpg/scenarios（POST 后轮询任务，非 SSE，但生成页面保留原交互）。
- 阶段 2 新增的 B 端接口：`POST /api/trpg/scenarios/{id}/publish|unpublish`、`POST /api/spire-content/publish|unpublish`；C 用户管理接口 `/api/c-admin/users*`、`/api/c-admin/groups*`（契约读 CAdminController）。

## 硬性纪律

1. 不改 /root/myapp、/root/notelab、/root/notelab-java、/root/notelab-c、nginx 配置。
2. **功能零缺失**：myapp 现有全部页面能力必须 1:1 迁移（含聊天、对抗、英语、RAG、提取、工具箱、低代码、TRPG 生成、Spire 工坊、账户/角色/权限管理、UI 配置）。
3. **行为契约不变**：所有接口调用路径、请求体、SSE 消费方式与 myapp 一致（仅 baseURL 语义变化由 nginx 承担）。
4. 每个里程碑 `npm run build` 零报错；最终 `pm2 restart notelab-b`。

## 实施内容

### 1. 工程骨架
- 从 /root/myapp 复制派生 /root/notelab-b（package.json/package-lock.json/tsconfig/postcss/next.config/public/src 全量），然后：
  - `next.config.ts`：`basePath: "/admin"`；删除任何 /api rewrites。
  - 保留 Tailwind 工程配置（antd 与 Tailwind 共存，注意 antd preflight 叠加问题，逐页目检）。
  - `lib/api.ts` 原样（/api 同域直达）；**审查所有 `window.location`、`localStorage` key、绝对路径跳转**：站内跳转一律 `next/link`/`router.push`（自动带 /admin 前缀）；localStorage key 加 `b_` 前缀或与 C 端确认不冲突（读 notelab-c 代码核对）。
- 登录页（/admin/login）：保留现有逻辑与 rememberPath 回跳，样式可顺手 antd 化（Form/Input/Button）。

### 2. antd 外壳（替代 (admin)/layout.tsx 的自定义毛玻璃外壳）
- `Layout`：Sider（antd `Menu`，数据源仍为 `/api/menu`，多级分组用 SubMenu，icon 沿用 emoji，`ready=false` 项打 Tag「敬请期待」并禁用）+ Header（用户信息、超级管理员标记、退出）+ Content。
- 认证守卫逻辑原样（/api/me 401 → rememberPath → /admin/login）。
- **ThemePicker 保留在 B 端**：Header 放主题切换入口（沿用 /api/ui-config 写 background，antd Modal 承载主题列表）。深色主题适配维持原语义。
- 全局：`App` 组件包裹（message/modal/notification 上下文）、`@ant-design/nextjs-registry` SSR 样式注入。

### 3. 15 个功能页全量 antd 化（逐页迁移、逐页构建）
替换自定义 `ui/*` 为 antd（Table/Form/Modal/Drawer/message/Select/Input/Tabs/Tag/Popconfirm）：
1. dashboard（概览卡片）
2. **chat（SSE，优先验证）**：消息流式渲染、会话管理、模型选择、markdown 渲染
3. **arena（SSE，优先验证）**：双路对抗流式
4. toolbox / tools（工具库与工具管理）
5. rag（上传/问答）
6. english（英语对话，含发音播放）
7. extract（提取）
8. lowcode（低代码）
9. trpg/gen（剧本生成，保留轮询任务交互）
10. spire-editor（尖塔工坊：卡/角色/技能编辑，**新增发布/取消发布按钮** → /api/spire-content/publish|unpublish）
11. user/accounts、user/roles（账户与角色管理）
12. ui（UI 配置页，**新增「C 端背景」说明区**：此处保存的 background 即 C 端匿名拉取的配置，加文案与当前预览）
13. perm（权限管理：路由组→角色→账户）
另外 trpg/page.tsx（6 行，若是跳转页则按新结构处理或删除并说明）。

### 4. 新增页面：C 用户管理
- 路由 `/c-users`（挂入菜单树——读 MenuTree/perm_routes 机制：若是前端静态菜单则加项，若纯后端菜单树则说明处理方式；**不得改 Java 代码**，若菜单必须后端出则在页面做直达入口并报告遗留）。
- 功能：Table（q 搜索/组筛选/分页）+ 新建/编辑（nickname/group/status）/重置密码/删除（Popconfirm）+ 用户组 Tab（增删改查，删组有成员 409 提示）。接口全走 /api/c-admin/*。

### 5. TRPG 生成页补发布操作
- `trpg/gen`（或剧本列表所在处）：每条剧本增加「发布/取消发布」操作（调阶段 2 接口），列表显示发布状态。

### 6. 验证（全部通过才算完成）
1. `npm run build` 零错误；`pm2 restart notelab-b`。
2. 经 nginx：`curl http://127.0.0.1/admin` → 未登录跳登录语义；`/admin/login` 200；`/admin/_next/static/*` 200。
3. 登录超管（用阶段 0/1 已验证的 Cookie 签发法）后逐页 curl 冒烟（页面 200）+ 关键接口走查：/api/me、/api/menu、会话列表、工具列表、权限数据。
4. **SSE 专项**：经 /admin 页面代码走查 + 直接 curl /api/chat 经 nginx 流式验证（复用阶段 0 的验证脚本思路），确认 chat/arena 消费逻辑与 myapp 一致。
5. 新功能闭环：超管建 C 用户（/admin/c-users）→ C 用户可登录 C 端；发布一个剧本 → C 端列表可见；发布 spire → /api/c/spire/content 返回内容。
6. myapp（:3000）、notelab-c（:3010）、notelab-java、Python 均不受影响。

### 7. 提交与报告
- /root/notelab-b 初始化 git（若尚无）：`git init && git add -A && git commit -m "B/C拆分阶段4：B端前端（antd管理后台，15页全量迁移+新增C用户管理/发布能力）"`。
- /root/notelab-java/ops/BC-SPLIT-P4.md 写阶段记录（页面清单、验收结果、回滚=占位壳）。
- 最终输出简明报告：页面迁移清单（逐页）、验证结果、遗留事项。
