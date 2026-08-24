# B/C 拆分 · 阶段 4：B 端前端 notelab-b（antd 管理后台）

日期：2026-08-25　服务器：117.72.32.87

> 开工前 /root/notelab-b 为阶段 0 占位壳（app/layout.tsx + page.tsx）。
> 占位壳已备份至 /root/.notelab-b-p0-backup（含其 node_modules/.next），可整体回滚。

## 本阶段做了什么

从 /root/myapp 派生 /root/notelab-b（src 全量），改造为 basePath=/admin 的 antd 管理后台：
- `next.config.ts` 仅 `basePath:"/admin"`，**无任何 /api rewrites**（/api 由 nginx 直达 :8001）。
- 外壳重写为 antd Layout（Sider Menu 数据源 /api/menu + 固定 Header + 移动端 Drawer），
  认证守卫语义不变（/api/me 401 → rememberPath → /login）；ThemePicker 保留（antd Modal 承载）。
- 全局 `@ant-design/nextjs-registry` SSR + antd `<App>` 包裹；toast 全部由 antd message 承载
  （`lib/toast.ts` 门面 + `ToastHost` 注册，页面调用点与 sonner 风格一致）。
- 自定义 ui/*（modal/select/confirm/form）改写为 antd 适配层，保持原 API，逐页生效。
- SSE（chat/arena/english）消费代码与 myapp 逐字节一致（脚本对比 IDENTICAL），
  myapp 的 /api/english/chat 直通 route handler 未迁移（nginx /api 已有 SSE 直通配置，无需）。
  英语发音保留 Next route handler：/admin/api/tts（Kokoro+edge-tts，同 myapp）。
- localStorage 全部加 b_ 前缀避免与 C 端同域冲突：b_spire-best / b_vs-meta / b_vs-best /
  b_notelab.lowcode.*；回跳 key 独立为 notelab-b.redirect（C 端为 notelab-c.redirect，已核对）。
- 依赖裁剪：去掉 myapp 未使用的 recharts/zod/radix/sonner/react-markdown/ioredis/mcp-sdk，
  新增显式 @ant-design/icons；其余版本与 myapp 对齐（next 16.2.12 / antd ^6.6.1）。

### 页面清单（21 路由 = myapp 19 页 1:1 + trpg 旧跳转 + 新增 1 页）

| 路由（/admin 下） | 迁移说明 |
|---|---|
| /（→/dashboard）、/dashboard | 重定向 + 概览卡片，原样 |
| /chat、/arena | SSE 逻辑零改动；arena 控件 antd 化（Tag.CheckableTag/Card） |
| /toolbox、/extract、/rag | antd Button/Input/Card/Upload.Dragger 重写，接口契约不变 |
| /english | SSE/TTS/语音输入零改动；场景选择改 antd Modal；tts 路径加 basePath |
| /lowcode | 原样（本地草稿，key 加 b_ 前缀） |
| /trpg/gen | antd Table；**新增发布/取消发布列**（阶段2接口）+ 发布状态 Tag |
| /trpg/play | 原样（跑团引擎零改动） |
| /spire-editor | 原已 antd；**新增「发布到 C 端 / 取消发布」按钮 + 已发布状态 Tag**（读 /api/ui-config 的 spire_published 判定） |
| /spire、/vs | 游戏引擎零改动 |
| /ui | 原交互保留；**新增「C 端背景」说明区 + 当前背景预览**（说明 C 端匿名拉取同一 background） |
| /perm | antd Table 重写；**新增 C 端用户管理直达入口卡片**（菜单树为 Java 常量，无法加菜单项，不改 Java） |
| /user/accounts、/user/roles | myapp 原已 antd，原样（toast 换 antd message） |
| /c-users | **新增**：C 端用户管理（用户 Tab：q 搜索/组筛选/分页/新建/编辑/重置密码/删除；用户组 Tab：增删改查，409 文案透出） |
| /login、/register | 登录 antd Form 重写（回跳逻辑不变）；注册说明页原样 |
| /trpg | 旧跳转页保留（→/trpg/play） |

## 验收结果（全部通过）

1. ✅ `npm run build` 零错误（26 路由全生成）；`pm2 restart notelab-b` 正常（:3020）。
2. ✅ 经 nginx：`/admin` → 307 → `/admin/dashboard`（basePath 重定向正确）；`/admin/login` 200；
   `/admin/_next/static/*` 200；HTML 含 antd SSR 样式（#antd-cssinjs）与 /admin 前缀资源。
   未登录访问页面为 200 + 客户端守卫（JS 块内含 /api/me 401→/login 逻辑，与 myapp 同语义）。
3. ✅ 超管 Cookie（阶段 0 签发法）逐页冒烟：21 个页面全 200；关键接口经 nginx 全 200：
   /api/me、/api/menu、/api/conversations、/api/models、/api/tools、/api/perm/overview、
   /api/c-admin/users、/api/english/scenarios、/api/ui-config、/api/rag/docs。
4. ✅ SSE 专项：经 nginx `POST /api/chat` 分块渐进到达（ttfb=3.37s，后续块持续增量至 6.35s EOF）；
   `POST /api/arena` 双模型 text/event-stream，含 started/keepalive/model/done 事件序列；
   chat/arena/english 三处 SSE 消费代码与 myapp 逐字节对比 IDENTICAL。
5. ✅ 新功能闭环（测试数据已清理）：
   - 超管建 C 用户 btest_p4（POST /api/c-admin/users → id=6）→ C 端 /api/c/auth/login+me 登录成功 → 删除后 C 会话即失效；
   - 发布剧本 3（/api/trpg/scenarios/3/publish）→ C 端登录态列表可见 [(3,'雾港惊魂',1)] → unpublish 复位，列表归空；
   - spire 工坊：存测试卡 → publish → /api/c/spire/content 返回该卡 → unpublish → 草稿恢复空三数组。
   - `/admin/api/tts` 经 nginx 200 audio/mpeg（11KB，Kokoro 本地合成 3.8s）。
6. ✅ 零影响回归：myapp:3000 `/` 307、`:3000/api/menu` 401；notelab-c:3010 `/` 200；nginx `/` 200；
   notelab-java:8001 `/api/menu` 401；Python :8000 `/docs` 200。pm2 六进程 online，
   其余五个进程重启次数与阶段前一致（未被触碰）。
7. ✅ /root/notelab-b 初始化 git 仓库并提交。

## 回滚方法

```bash
# 回到阶段 0 占位壳：
cd /root/notelab-b
mv /root/notelab-b /root/notelab-b.p4 && mkdir /root/notelab-b
mv /root/.notelab-b-p0-backup/* /root/.notelab-b-p0-backup/.[!.]* /root/notelab-b/ 2>/dev/null
pm2 restart notelab-b
# 或代码级回退：git log 首个提交即本阶段全量，之前无历史（占位壳无 git 记录，备份目录即占位壳）
# 快速止血（不动代码）：改 nginx ^~ /admin 的 proxy_pass 目标后 reload
```

## 遗留事项

- `/c-users` 无菜单项：菜单树为 Java 常量（MenuTree）+ RBAC，任务约束不改 Java，
  入口置于「权限管理」页直达卡片；如需进菜单，需后端在 MenuTree 增项并重启。
- `/api/c-admin/*` 后端仅校验 B 端登录、未限超管（以 Java 代码为准）；前端入口放在超管可见页面。
- 未做真实浏览器自动化测试；深色主题下 antd 组件采用半透明玻璃底保持可读，未逐像素核对。
- 库内暂无已发布剧本/Spire 内容（闭环验证后已复位）；是否长期发布由管理员在 B 端操作。
