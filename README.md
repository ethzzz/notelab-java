# notelab-java

NoteLab AI 试验后台的 **Java（Spring Boot）重写版**。目标：1:1 重写 `/root/notelab`（Python FastAPI）后端，API 契约完全兼容，在 **8001 端口**与 Python 版（8000）并行运行，验收通过后由人工将前端流量从 8000 切到 8001。

> 本文件随迭代持续更新，当前状态以「功能清单」为准。

## 功能清单

| 模块 | 接口 | 状态 |
|---|---|---|
| 认证 | /api/register /api/login /api/logout /api/me | 🚧 |
| 菜单/模型 | /api/menu /api/models | 🚧 |
| 智能对话 | /api/conversations* /api/chat(SSE) | ⬜ |
| 文本工具箱 | /api/toolbox | ⬜ |
| 文档问答 RAG | /api/rag/upload /api/rag/docs /api/rag/ask | ⬜ |
| 英语学习 | /api/english/* (含 SSE 对话) | ⬜ |
| 结构化抽取 | /api/extract | ⬜ |
| 模型竞技场 | /api/arena (并行 SSE+心跳) | ⬜ |
| 界面配置 | /api/ui-config | ⬜ |

## 环境依赖
- JDK 17+（服务器已装 openjdk-17）
- Maven 3.8+（已配阿里云镜像 /root/.m2/settings.xml）
- 环境变量 `QWEN_API_KEY`（阿里云 token-plan 网关密钥，已写入 /etc/environment）
- Node.js / pm2（仅用于进程管理）

## 构建
```bash
cd /root/notelab-java
mvn -DskipTests package
```

## 启动
```bash
# 直接启动（端口 8001）
java -jar target/notelab-java-0.0.1-SNAPSHOT.jar

# pm2 管理（推荐）
pm2 start "java -jar /root/notelab-java/target/notelab-java-0.0.1-SNAPSHOT.jar" --name notelab-java
pm2 logs notelab-java
pm2 status
```

## 与 Python 版的关系
- Python 版：/root/notelab，端口 8000，pm2 进程 `notelab`（重写期间保持运行，勿动）。
- Java 版：本目录，端口 8001，pm2 进程 `notelab-java`。
- 切流方法（验收后人工执行）：把 /root/myapp 的 next.config 中 `/api/:path*` 的代理目标从 `127.0.0.1:8000` 改为 `127.0.0.1:8001`，重新构建并重启前端。
- 数据说明：Java 版使用独立 SQLite 数据文件，不读写 Python 版数据；数据迁移为后续独立步骤。

## 迭代进度
见 PROGRESS.md（由重写代理逐阶段记录验证结果）。