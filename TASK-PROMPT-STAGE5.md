你是 notelab 的 Java 重写代理。阶段1~4 已完成（详见 AGENTS.md 与 PROGRESS.md），现在人工决策为：**阶段5——Java 版改用与 Python 版相同的 MySQL，两服务共享同一份数据**。先阅读 AGENTS.md 与 PROGRESS.md，然后执行：

## 目标
把 /root/notelab-java 的数据层从 SQLite（data/notelab-java.db）切换为 MySQL，直连 Python 版正在用的同一个库。切换后数据迁移不再需要——两个服务看到同一份数据。

## MySQL 连接信息
- 从 /root/notelab/.env 读取：MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_DB / MYSQL_PASSWORD（Java 版已有 .env 加载机制，复用之；密钥严禁硬编码或提交）
- MySQL 是本机 systemd 服务（127.0.0.1:3306），库名 notelab；表结构以 /root/notelab/db.py 为准（users、conversations、messages、英语相关表、ui_config 等）

## 安全红线（最高优先级）
1. **动手前先备份**：mysqldump notelab 库到 /root/notelab-java/data/backup-before-mysql-switch.sql（用 .env 里的账号密码）
2. 严禁 DROP TABLE、严禁 DELETE/TRUNCATE 现有数据、严禁修改现有表结构
3. DDL 仅限 CREATE TABLE IF NOT EXISTS（与 db.py 的建表语句保持一致）
4. Python 服务（pm2 进程 notelab，8000 端口）正在生产运行且与你共用此库：所有写操作必须兼容并发读写；任何可能破坏现有数据的操作立即停止并在 PROGRESS.md 说明

## 技术要求
1. 依赖：mysql-connector-j + HikariCP 连接池（spring-boot-starter-jdbc），连接池最大 10
2. DAO 层从 SQLite JDBC 改为 MySQL，注意方言差异（AUTO_INCREMENT、时间函数、占位符等），表结构与行为保持与 db.py 一致
3. 切换完成后移除 SQLite 相关依赖与代码（data/notelab-java.db 保留为历史产物，不再读写，README 说明即可）
4. 重新构建 jar 并 pm2 restart notelab-java

## 验收标准（逐条执行并记录到 PROGRESS.md）
1. Java 侧（8001）注册一个新用户 → 该用户能在 Python 侧（8000）登录成功
2. Java 侧新建一个对话 → Python 侧 /api/conversations 列表可见
3. Python 侧已有用户能在 Java 侧登录并读取其历史对话（数据真正共享）
4. 重跑全量接口对比回归（可改造 /tmp/cmp_stage*.sh；注意现在两侧共享数据，避免重复注册等误报）
5. Python 服务不受影响：pm2 status 中 notelab 仍 online，其接口行为不变

## 收尾
- 更新 README.md：环境依赖补充 MySQL 连接说明；数据层描述改为"直连 Python 版 MySQL（notelab 库），两服务数据共享"；说明 SQLite 已废弃
- PROGRESS.md 写清改动内容、验收过程与结果、剩余风险
- git commit
- 会话结束前输出总结：改了什么、验收结果、剩余风险与下一步建议