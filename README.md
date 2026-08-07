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
| 模型竞技场 | /api/arena (并行 SSE+心跳) | ✅ 已完成（10s 心跳实测生效） |
| 界面配置 | /api/ui-config | ✅ 已完成（30s 缓存+保存失效） |

## 项目简介

- 技术栈：Java 17 + Spring Boot 3.4 + **MySQL（直连 Python 版同一个库）+ HikariCP 连接池（最大 10）** + 原生 HttpClient（调模型网关）。
- 只实现前端（Next.js，/root/myapp）实际调用的 `/api/*` 接口；Python 版的服务端渲染 HTML 页面不在重写范围（前端不使用）。
- **数据层直连 Python 版正在用的 MySQL（127.0.0.1:3306 的 `notelab` 库），两个服务共享同一份数据**：
  用户、会话、消息、英语学习、ui_config 全部互通，一端写入另一端即时可见，无需数据迁移。
  建表语句与 Python 版 db.py 逐条一致（仅 CREATE TABLE IF NOT EXISTS，不改动现有表结构）。
  原 SQLite 方案已废弃：`data/notelab-java.db` 仅保留为历史产物，不再读写。

## 环境依赖

- JDK 17+（服务器已装 openjdk-17）
- Maven 3.8+（已配阿里云镜像 /root/.m2/settings.xml）
- 环境变量 `QWEN_API_KEY`（阿里云 token-plan 网关密钥，已写入 /etc/environment）；
  若进程环境没有，程序会按 `进程环境变量 → ./.env → /root/notelab/.env（只读）→ 默认值` 的顺序读取
  `QWEN_API_KEY / QWEN_BASE_URL / QWEN_MODEL / SECRET_KEY / REDIS_HOST / REDIS_PORT`，
  其中 SECRET_KEY 复用 Python 版的值是**会话 Cookie 兼容**的关键。
- **MySQL**（阶段5 起必需）：本机 systemd 服务 `mysql`（127.0.0.1:3306），库名 `notelab`，
  连接参数读取 `MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB`
  （与 Python 版同用 /root/notelab/.env 中的同一套账号；密钥不落代码、不入 git）。
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
2. **会话兼容**：HMAC 会话 Cookie（`notelab_session`，base64url(`uid.exp.hmac_sha256_hex`)，30 天有效期）与 Python 版算法完全一致，且两服务共享同一张 users 表，切流后用户登录态无缝保留（已实测双向互认）。
3. **限流兼容**：与 Python 版共用 Redis 限流键（`rl:register:<ip>` 5次/10分钟、`rl:login:<ip>` 10次/5分钟）。
4. **数据共享**（阶段5 起）：两服务直连同一个 MySQL 库 `notelab`，注册/会话/消息/英语/ui_config 全部共享，
   **切流无需任何数据迁移**。切换前的备份见 `data/backup-before-mysql-switch.sql`（mysqldump，不入库）。
5. **切流步骤**（人工）：
   - 确认 Java 版回归通过（见 PROGRESS.md）；
   - 修改 `/root/myapp/next.config.ts` 中 rewrites 目标 `8000 → 8001` 并重启 myapp；
   - 观察无异常后 `pm2 stop notelab`（数据在同一 MySQL，停 Python 服务不丢任何数据）。

## 已知差异（不影响前端）

- 非法 JSON 请求体的 422 `detail` 数组内部字段与 FastAPI 略有差异（外层结构一致）。
- Set-Cookie 头细节差异：Python 版对带 `=` 的 token 值加双引号、Java 版不加；`SameSite=lax/Lax` 大小写不同。均为合法 Cookie，浏览器/代理行为一致，互认已实测通过。
- /api/rag/upload 为 **JSON** 接口（`{name, content}`），与 main.py 实际实现一致（任务简报中写的 multipart 以源码为准）。

## 已知差异补充（阶段5）

- extract 请求缺 `fields` 字段时：Python 返回 422 `detail`（Pydantic 校验），Java 返回 400 `{"error":"文本和目标字段都不能为空"}`；正常请求两端一致。
- ui_config 现为两服务共享表：任何一端保存配置会即时影响另一端（30s 缓存过期后生效），操作即生产。

## 验证状态

全部 5 个阶段完成并通过与 Python 版（8000）的逐接口 curl 对比验收（阶段5 为共享 MySQL 数据互通 + 全量回归），详见 [PROGRESS.md](PROGRESS.md)。

## 切流状态（2026-08-07 22:45 更新）
- ✅ 切流已完成：前端生产 3000 与开发 3001 的 API 流量均已切到 Java 版 8001，原 next.config 备份为 /root/myapp/next.config.ts.bak-8000
- Python 版 8000 观察期内保持运行，确认无异常后退役：pm2 stop notelab（数据都在共享 MySQL，零丢失）
- 回滚方法：next.config.ts rewrites 改回 8000（或恢复备份文件），然后 npm run build 并 pm2 restart myapp
- 切流验证记录见 PROGRESS.md 末节
