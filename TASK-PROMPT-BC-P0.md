# B/C 端拆分 · 阶段 0：nginx 前缀代理 + 双前端壳

你在服务器（117.72.32.87）。这是 NoteLab「B/C 端拆分」总体改造的**第 0 阶段**，目标是搭好统一入口与两个前端服务壳，**不改动任何现有服务的代码与行为**。

## 目标架构（本阶段结束时）

```
用户 → nginx :80
  ├─ /api/*  → 127.0.0.1:8001（notelab-java，SSE 透传）
  ├─ /admin  → 127.0.0.1:3020（notelab-b，Next basePath="/admin"，本阶段只是壳）
  └─ /       → 127.0.0.1:3010（notelab-c，Next，本阶段只是壳）
```

现有服务：/root/myapp（Next 生产 :3000，pm2 myapp）、/root/myapp 的 dev :3001（pm2 myapp-dev）、/root/notelab（Python :8000）、/root/notelab-java（Spring Boot :8001）。**全部不许改动**。

## 实施步骤

### 1. 安装 nginx
- 先 `which nginx` 探测；未安装则 `apt-get update && apt-get install -y nginx`，`systemctl enable --now nginx`。
- 若 80 端口已被占用：停止并如实报告，不要杀任何现有进程。

### 2. 站点配置
写 `/etc/nginx/sites-available/notelab`，软链到 `sites-enabled/`，并移除 `sites-enabled/default`：

- `listen 80 default_server;`
- `location /api/ { proxy_pass http://127.0.0.1:8001; ... }` —— **不带 URI 部分**（保留原始路径）；SSE 必配：
  `proxy_http_version 1.1; proxy_set_header Connection ""; proxy_buffering off; proxy_cache off; proxy_read_timeout 300s; proxy_send_timeout 300s;`
- `location /admin { proxy_pass http://127.0.0.1:3020; }` —— 不带 URI，保留 `/admin` 前缀给 Next basePath；注意同时覆盖 `/admin/...`（必要时 `location ^~ /admin`）。
- `location / { proxy_pass http://127.0.0.1:3010; }`
- 三个 location 统一带：`proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; proxy_set_header X-Forwarded-Proto $scheme;`
- 顶层 `client_max_body_size 50m;`（RAG 上传走 /api）
- `nginx -t` 通过后 `systemctl reload nginx`。

### 3. 两个壳应用
Next 版本与依赖**对齐 /root/myapp/package.json**（先读它）。

- `/root/notelab-c`：最小 Next 应用（app router），首页渲染「NoteLab C 端 · 建设中」；`PORT=3010` 启动。
- `/root/notelab-b`：最小 Next 应用，`next.config.ts` 设置 `basePath: "/admin"`；app/page.tsx 对应 `/admin`，渲染「NoteLab B 端管理 · 建设中」；`PORT=3020` 启动。
- 各自 `npm install && npm run build`，生产模式启动。
- 注意 Next 生产压缩会缓冲 SSE 的教训只与 /api 代理有关——本阶段 /api 已由 nginx 直达 Java，两个壳应用**不需要任何 /api rewrites**。

### 4. pm2
- 新增 `notelab-c`（:3010）、`notelab-b`（:3020）两个条目（cwd 分别为 /root/notelab-c、/root/notelab-b，`npm run start`，env PORT）。
- `pm2 save`。**绝不改动/重启** myapp、myapp-dev、notelab、notelab-java 任何现有条目。

### 5. 验收（全部通过才算完成）
1. `curl -s http://127.0.0.1/` → C 壳页面 200；`curl -s http://127.0.0.1/admin` → B 壳页面 200。
2. `curl -s http://127.0.0.1/api/menu` → `{"error":"请先登录"}`（说明直达 Java）。
3. **SSE 透传验证**：阅读 /root/notelab-java/src/main/java/com/notelab/common/Session.java 的签发逻辑，写一次性脚本为超级管理员（users 表 role='super_admin'）生成合法 Cookie（名为 notelab_session）；然后对 `POST /api/chat`（最小合法 body，先读 ChatController 确认格式）分别 `curl -N` 直连 :8001 与经 nginx :80，确认经 nginx 时响应是**分块渐进**到达而非一次性吐出（可用 `curl -N -w '%{time_starttransfer}'` 与观察流式输出对比）。验证完可中断请求，不必等模型回复完整。
4. 现有链路零影响：`curl -s http://127.0.0.1:3000/api/menu`（旧 myapp）与 `curl -s http://127.0.0.1:8000/docs`（Python 版）仍正常。
5. `pm2 jlist` 确认 6 个进程全部 online（原 4 个 + 新 2 个）。

### 6. 归档与提交
- 备份配置到 `/root/notelab-java/ops/nginx-notelab.conf`；新建 `/root/notelab-java/ops/BC-SPLIT-P0.md` 记录：本阶段做了什么、各端口与进程清单、验收结果、回滚方法（停用 nginx 即回到原状）。
- 在 /root/notelab-java 仓库 `git add ops && git commit -m "B/C拆分阶段0：nginx前缀代理与双前端壳（运维归档）"`。

## 硬性纪律
1. 不改 /root/myapp、/root/notelab-java/src、/root/notelab 任何文件；本阶段**零数据库操作**。
2. 不新建第二个 Java/Python 进程；不动任何现有 pm2 条目。
3. 若任一验收失败：修复后重验；无法修复则回滚本阶段新增物（停 nginx、删两个壳进程）并如实报告，不允许带病收尾。
4. 最终输出一份简明报告：做了什么、验收结果（逐条）、遗留事项。
