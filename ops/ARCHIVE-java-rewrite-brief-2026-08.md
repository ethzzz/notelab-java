# 归档：notelab-java 重写任务简报（原 AGENTS.md，2026-08）

> **本文件是历史存档，不是当前指引。**
> 原 `notelab-java/AGENTS.md` 于 2026-08 作为「把 Python 版重写成 Spring Boot」的**任务简报**而写。该重写任务已全部完成（最终提交链见 `BC-SPLIT-SUMMARY.md`，Java 已于 2026-08-07 承接全部流量）。
> 2026-09-25 改写 AGENTS.md 为现状说明时，把原文完整保留在此，供追溯当时的接口范围与验收标准。
> **不要照本文件去「重写」任何东西。** 当前指引见 `../AGENTS.md`。

---

## 任务（2026-08 当时）
把 `/root/notelab` 的 Python FastAPI 后端用 Spring Boot（Java 17+）**一模一样地重写**到本目录（/root/notelab-java），API 契约完全兼容，端口 **8001** 并行运行，最终由人工把前端流量从 8000 切到 8001。

## 背景
- 前端是 Next.js 应用（/root/myapp，生产端口 3000），其 next.config 把 `/api/*` 同源代理到 `http://127.0.0.1:8000/api/*`。
- 现后端是 FastAPI（/root/notelab），uvicorn 端口 8000，pm2 进程名 `notelab`，已开 watch。
- **严禁修改 /root/notelab 与 /root/myapp 的任何文件**，只读参考。

## 接口范围（前端实际调用，请求/响应契约以 /root/notelab/main.py 源码为准）
- 认证：POST /api/register、POST /api/login、POST /api/logout、GET /api/me
- 菜单：GET /api/menu
- 模型：GET /api/models
- 智能对话：GET|POST /api/conversations、GET /api/conversations/{cid}/messages、DELETE /api/conversations/{cid}、POST /api/conversations/{cid}/model、POST /api/chat（SSE 流式）
- 文本工具箱：POST /api/toolbox
- 文档问答 RAG：POST /api/rag/upload（multipart）、GET /api/rag/docs、POST /api/rag/ask
- 英语学习：GET /api/english/scenarios、GET|POST /api/english/conversations、GET /api/english/conversations/{cid}/messages、DELETE /api/english/conversations/{cid}、POST /api/english/chat（SSE 流式）
- 结构化抽取：POST /api/extract
- 模型竞技场：POST /api/arena（并行多模型 SSE 流式，必须带心跳保活，参考 Python 实现的 10s 心跳）
- 界面配置：GET|POST /api/ui-config

## 关键技术约定
1. 模型调用走阿里云 token-plan 网关（OpenAI 兼容协议）：
   - base_url: `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`
   - api key 从环境变量 `QWEN_API_KEY` 读取（系统已注入 /etc/environment）；**严禁硬编码或提交 key**。
   - 注意 Python 源码里模型清单与参数（temperature 等）保持一致。
2. 存储：参考 /root/notelab/db.py 的表结构与行为。Java 侧使用**独立的新 SQLite 数据文件**（如 data/notelab-java.db），不得读写 Python 服务正在用的数据文件，避免并发写坏；数据迁移是后续单独步骤。
3. 认证：HMAC 会话 Cookie 机制与 Python 版保持一致（密钥来源、签名算法、有效期见 main.py），目标是切流后登录态兼容；若确实无法完全兼容，至少保证新登录可用，并在 README 说明差异。
4. 端口 8001；绑定地址参考 /root/notelab/ecosystem.config.cjs 里 uvicorn 的 host 参数。
5. 进程管理用 pm2：`pm2 start "java -jar target/<jar名>.jar" --name notelab-java`，不得影响现有 pm2 进程（notelab、myapp、myapp-dev）。
6. Maven 已配置阿里云镜像（/root/.m2/settings.xml），不要改镜像；构建用 `mvn -DskipTests package`。

## 阶段计划
- **阶段1**：Spring Boot 骨架（端口 8001、读 QWEN_API_KEY）+ GET /api/models + GET /api/menu + 认证四接口（register/login/logout/me）+ README 初版 + pm2 启动 notelab-java。验收：curl 逐接口对比 Python 版返回结构一致。
- **阶段2**：conversations 全套 + POST /api/chat（SSE 流式）+ /api/toolbox + /api/extract。验收：流式输出正常、多轮对话历史生效。
- **阶段3**：RAG（upload/docs/ask）+ 英语全套（scenarios/conversations/chat SSE，含语法纠错提示词逻辑，照抄 Python 版提示词）。
- **阶段4**：arena（并行流式+心跳）+ ui-config；全量回归所有接口。
- 每完成一个阶段：更新 README.md、git commit、在 PROGRESS.md 记录验证结果。

## README.md 要求（必须持续维护）
包含：项目简介、功能清单（逐模块标 ✅已完成 / 🚧进行中 / ⬜未开始）、环境依赖（JDK、Maven、环境变量）、构建命令、启动命令（含 pm2 方式）、端口说明、与 Python 版的关系及切流方法。每完成功能就同步更新，不要等全部做完。

## 纪律
- 只改本目录（/root/notelab-java）内的文件。
- 每阶段完成必须自验（构建 + pm2 重启 + curl 对比），把自验命令与结果写入 PROGRESS.md。
- 无法一次做完时，在阶段边界停止：保证当前代码可构建可启动，PROGRESS.md 写清已完成内容与下一步。
- 遇到不确定的契约细节，以 /root/notelab/main.py 的实际行为为准（可直接 curl 8000 端口对比真实响应）。

---

## 归档时的状态说明（2026-09-25 补注）
- 上表提到的「SQLite 独立数据文件」**最终没走这条路线**：持久层最终用了服务器上**共享的生产 MySQL（notelab 库）+ MyBatis-Plus 3.5.9**，与 Python 版共用同一库（见 `README.md` / `PROGRESS.md`）。
- 「Python 版仍在跑、可 curl 8000 对比」也已作废：Python 版（pm2 `notelab`、:8000）与旧前端 `myapp`（:3000/:3001）**均已 stop**（2026-08-25），回滚才需要 `pm2 restart`。
- 端口 8001 与 Maven 镜像约定至今未变。
