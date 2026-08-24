# B/C 端拆分 · 阶段 5：切流、退役与收尾

你在服务器（117.72.32.87）。这是「B/C 端拆分」的**阶段 5（最后阶段）**：全量回归、文档更新、本地同步准备。
前 4 阶段已完成：先通读 /root/notelab-java/ops/BC-SPLIT-P*.md、/root/notelab-java git log、/root/notelab-c 与 /root/notelab-b 的 git 提交，确认实际状态后开工。

## 硬性纪律

1. **不删除任何东西**：本阶段不删代码、不删表、不停用 Python 版（:8000 与 pm2 `notelab` 保持运行——退役决定权在用户，只写观察建议）。
2. 旧 myapp（:3000）与 myapp-dev（:3001）**保留运行**（回滚依赖），只更新文档标注其状态为「观察期，待确认无流量后由用户决定停用」。
3. 不改 nginx 对外策略（:80 已是唯一推荐入口，无需变更）。
4. 共享 MySQL 只读 + 必要的测试数据清理（清理前逐项列出并取得逻辑确认：仅删本系列任务产生的 ctest/bctest 前缀测试数据）。

## 实施内容

### 1. 全量回归矩阵（逐项记录结果）
**B 端（经 /admin 与 :8001）**：登录（错密码 401）→ /api/me → /api/menu → dashboard/chat（SSE 流式经 nginx）/arena（SSE）/english（含 /api/tts）/rag/toolbox/tools/extract/lowcode/trpg-gen（轮询）/spire-editor（含发布）/accounts/roles/perm/ui 各接口冒烟。
**C 端（经 / 与 /api/c/*）**：登录 ctest1（若无则建）→ me → 已发布剧本列表 → 开局 → choose → 存档列表 → 删除存档 → spire 内容（匿名）→ background（匿名）→ 退出。
**隔离对抗**：B Cookie 打 /api/c/auth/me 401；C Cookie 打 /api/menu 401；B plays 列表无 C 存档。
**旧链路**：:3000（myapp 原站）首页与 /api/menu 正常；:8000 Python 版可达、登录语义正常；:8001 直连正常。
**进程**：`pm2 jlist` 6 进程 online；`pm2 save` 已固化（确认 notelab-c/notelab-b 在 dump 里）。

### 2. 测试数据清理
列出并删除本系列（阶段 1/2/4）创建的测试数据：c_users 里 ctest* 前缀账号、测试剧本/存档（若有标题标记）、临时会话（阶段 0 的若未清）。清理前先 SELECT 展示清单。正式数据（admin、tester1、用户真实内容）一概不动。

### 3. 文档更新（都在 /root/notelab-java 仓库）
- `PROGRESS.md` 追加「B/C 端拆分（阶段 0-5）」章节：架构总览（nginx 拓扑、端口、6 进程）、每阶段 commit 清单、验收结论、回滚方法汇总。
- `ops/BC-SPLIT-SUMMARY.md`：一页纸总览——入口地址（/ 与 /admin）、各服务端口与 pm2 名、数据库新表/新列清单、接口清单（/api/c/*、/api/c-admin/*、publish 系列）、遗留事项。
- `git add -A && git commit -m "B/C拆分阶段5：全量回归+测试数据清理+文档收尾"`。

### 4. 同步准备
- 确认 /root/myapp 与 /root/notelab-java 的 git 工作区干净（`git status`）。
- 输出「本地同步指引」：说明用户应运行 docs\sync-from-server.ps1（本地镜像需要同步 notelab-c 与 notelab-b 两个新目录——给出建议：脚本当前只同步 myapp 与 notelab-java，列出建议新增的两条 scp/robocopy 命令供代理执行）。

### 5. 最终报告
输出：回归矩阵逐项结果、清理清单、遗留事项（含 Python 版退役建议、公网安全组 :80 确认事项、C_REGISTER_OPEN 开放方式）、整体回滚路径。
