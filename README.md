# notelab-java

NoteLab AI 试验后台的 **Java（Spring Boot）重写版**。目标：1:1 重写 `/root/notelab`（Python FastAPI）后端，API 契约完全兼容，在 **8001 端口**与 Python 版（8000）并行运行，验收通过后由人工将前端流量从 8000 切到 8001。

> 本文件随迭代持续更新，当前状态以「功能清单」为准。详细验证记录见 [PROGRESS.md](PROGRESS.md)。

## 功能清单

| 模块 | 接口 | 状态 |
|---|---|---|
| 认证 | /api/register /api/login /api/logout /api/me | ✅ 已完成（会话 Cookie 与 Python 版双向兼容） |
| 菜单/模型 | /api/menu /api/models | ✅ 已完成（models 600s 缓存，结构逐项对比一致） |
| 智能对话 | /api/conversations* /api/chat(SSE) | ✅ 已完成（流式+历史+自动命名验证通过） |
| 文本工具箱 | /api/toolbox | ✅ 已完成 |
| 文档问答 RAG | /api/rag/upload /api/rag/docs /api/rag/ask | ✅ 已完成（JSON 上传，检索打分与 Python 完全一致） |
| 英语学习 | /api/english/* | ✅ 已完成（场景/开场白/语法纠错全对齐） |
| 结构化抽取 | /api/extract | ✅ 已完成 |
| 模型竞技场 | /api/arena (并行 SSE+心跳) | ⬜ 未开始 |
| 界面配置 | /api/ui-config | ⬜ 未开始 |

## 项目简介

- 技术栈：Java 17 + Spring Boot 3.4 + SQLite（xerial JDBC）+ 原生 HttpClient（调模型网关）。
- 只实现前端（Next.js，/root/myapp）实际调用的 `/api/*` 接口；Python 版的服务端渲染 HTML 页面不在重写范围（前端不使用）。
- 数据层表结构与行为和 Python 版 db.py 一致，但使用**独立的 SQLite 文件** `data/notelab-java.db`，不触碰 Python 服务的数据（Python 版用 MySQL）。数据迁移是切流前的单独步骤。

## 环境依赖

- JDK 17+（服务器已装 openjdk-17）
- Maven 3.8+（已配阿里云镜像 /root/.m2/settings.xml）
- 环境变量 `QWEN_API_KEY`（阿里云 token-plan 网关密钥，已写入 /etc/environment）；
  若进程环境没有，程序会按 `进程环境变量 → ./.env → /root/notelab/.env（只读）→ 默认值` 的顺序读取
  `QWEN_API_KEY / QWEN_BASE_URL / QWEN_MODEL / SECRET_KEY / REDIS_HOST / REDIS_PORT`，
  其中 SECRET_KEY 复用 Python 版的值是**会话 Cookie 兼容**的关键。
- Node.js / pm2（仅用于进程管理）
- Redis（可选）：限流与 Python 版共用（键 `rl:*`）；不可用时自动退回进程内限流。

## 构建

```bash
cd /root/notelab-java
mvn -DskipTests package     # 产物 target/notelab-java.jar
```

## 启动

```bash
# pm2 方式（推荐）
cd /root/notelab-java
pm2 start "java -jar target/notelab-java.jar" --name notelab-java
pm2 logs notelab-java --lines 50

# 手动方式
java -jar target/notelab-java.jar
```

重启（重新构建后）：

```bash
mvn -DskipTests package && pm2 restart notelab-java
```

## 端口说明

| 服务 | 端口 | 绑定地址 | pm2 进程 |
|---|---|---|---|
| Python FastAPI（现网） | 8000 | 127.0.0.1 | notelab |
| Java Spring Boot（本版） | 8001 | 127.0.0.1 | notelab-java |
| Next.js 前端 | 3000 | — | myapp |

前端 next.config 把 `/api/*` 同源代理到 `http://127.0.0.1:8000/api/*`；切流即把该目标改为 8001（由人工执行）。

## 与 Python 版的关系及切流方法

1. **契约来源**：所有请求/响应契约以 `/root/notelab/main.py` 源码为准（错误文案、状态码、字段顺序均照抄）。
2. **会话兼容**：HMAC 会话 Cookie（`notelab_session`，base64url(`uid.exp.hmac_sha256_hex`)，30 天有效期）与 Python 版算法完全一致，已验证双向互认（Python 签发的 token 可直接登录 Java 版，反之亦然），切流后用户登录态不丢失（前提是用户数据已迁移到 Java 侧数据库）。
3. **限流兼容**：与 Python 版共用 Redis 限流键（`rl:register:<ip>` 5次/10分钟、`rl:login:<ip>` 10次/5分钟）。
4. **切流步骤**（人工）：
   - 确认 Java 版回归通过（见 PROGRESS.md）；
   - 将 Python 库中的用户/对话等数据迁移到 `data/notelab-java.db`（单独步骤）；
   - 修改 `/root/myapp/next.config.ts` 中 rewrites 目标 `8000 → 8001` 并重启 myapp；
   - 观察无异常后 `pm2 stop notelab`。

## 已知差异（不影响前端）

- 非法 JSON 请求体的 422 `detail` 数组内部字段与 FastAPI 略有差异（外层结构一致）。
- Set-Cookie 头细节差异：Python 版对带 `=` 的 token 值加双引号、Java 版不加；`SameSite=lax/Lax` 大小写不同。均为合法 Cookie，浏览器/代理行为一致，互认已实测通过。
- /api/rag/upload 为 **JSON** 接口（`{name, content}`），与 main.py 实际实现一致（任务简报中写的 multipart 以源码为准）。
