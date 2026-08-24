# B/C 拆分 · 阶段 0：nginx 前缀代理 + 双前端壳

日期：2026-08-24　服务器：117.72.32.87

## 本阶段做了什么

1. 安装 nginx 1.24.0（apt），`systemctl enable --now nginx`，监听 :80。
   - 注意：apt 安装即自动启动默认站点，写完配置后必须 `systemctl reload nginx` 才生效（本次已执行）。
   - 原默认站点软链已移至 `/var/backups/nginx/default-site-enabled.bak` 备份。
2. 站点配置 `/etc/nginx/sites-available/notelab`（软链至 sites-enabled/，归档副本见本目录 `nginx-notelab.conf`）：
   - `/api/*`（含精确 `/api`）→ `127.0.0.1:8001`（notelab-java），proxy_pass 不带 URI 保留原始路径；
     SSE 配置：`proxy_http_version 1.1; Connection ""; proxy_buffering off; proxy_cache off; read/send_timeout 300s`。
   - `^~ /admin` → `127.0.0.1:3020`（notelab-b，Next basePath="/admin"，前缀保留，含 `/admin/_next/*` 静态资源）。
   - `/` → `127.0.0.1:3010`（notelab-c）。
   - 三个 location 统一带 Host / X-Real-IP / X-Forwarded-For / X-Forwarded-Proto；顶层 `client_max_body_size 50m`。
3. 新建两个 Next 16.2.12 壳应用（版本与依赖对齐 /root/myapp）：
   - `/root/notelab-c`：app router 最小应用，首页「NoteLab C 端 · 建设中」，:3010。
   - `/root/notelab-b`：`next.config.ts` 设 `basePath:"/admin"`，/admin 首页「NoteLab B 端管理 · 建设中」，:3020。
   - 两个壳均无 /api rewrites（/api 由 nginx 直达 Java）。
4. pm2 新增 `notelab-c`、`notelab-b`（`npm run start`，env PORT=3010/3020、NODE_ENV=production），`pm2 save`。
   现有 4 个条目（notelab、myapp、myapp-dev、notelab-java）零改动、零重启。

## 端口与进程清单（阶段0结束后）

| 进程 (pm2)   | 端口 | 说明                          | 状态   |
|--------------|------|-------------------------------|--------|
| notelab      | 8000 | Python FastAPI（旧，未动）    | online |
| notelab-java | 8001 | Spring Boot（API 主后端）     | online |
| myapp        | 3000 | Next 生产（旧，未动）         | online |
| myapp-dev    | 3001 | Next dev（旧，未动）          | online |
| notelab-c    | 3010 | C 端壳（新）                  | online |
| notelab-b    | 3020 | B 端壳（basePath /admin，新） | online |
| nginx        | 80   | 统一入口（systemd，非 pm2）   | active |

## 验收结果（全部通过）

1. ✅ `curl http://127.0.0.1/` → 200「NoteLab C 端 · 建设中」；`curl http://127.0.0.1/admin` → 200「NoteLab B 端管理 · 建设中」；`/admin/_next/static/*` 静态资源经 nginx 200。
2. ✅ `curl http://127.0.0.1/api/menu` → 401 `{"error":"请先登录"}`（直达 Java）。
3. ✅ SSE 透传：按 Session.java 的 `base64url(uid.exp.hmac_sha256_hex)` 算法（SECRET_KEY 取自 /root/notelab/.env）
   为 super_admin（uid=18）一次性签发 notelab_session Cookie；对 POST /api/chat（长输出提示词）
   直连 :8001 与经 nginx :80 各测一次，二者均为**分块渐进**到达且时间线几乎一致：
   - 直连：ttfb=2.155s，+1.34s/512B、+2.52s/1024B、+3.66s/1490B（57 事件）后 EOF；
   - 经 nginx：ttfb=2.414s，+1.32s/512B、+2.63s/1024B、+3.84s/1490B（58 事件）后 EOF。
   （首次短回答测试经 nginx ttfb=16.6s 系模型首 token 延迟，对照长输出测试已排除 nginx 缓冲。）
   测试用临时会话（id=20）已通过 DELETE /api/conversations/20 清理；除该测试经 API 产生的少量消息行外，本阶段零数据库操作。
4. ✅ 旧链路零影响：`:3000/api/menu` → 401 `{"error":"请先登录"}`；`:8000/docs` → 200。
5. ✅ `pm2 jlist` 6 进程全部 online；原 4 进程重启次数与验证前一致（未被触碰）。

## 回滚方法

```bash
systemctl stop nginx && systemctl disable nginx   # 流量入口即回到原状（用户直连 :3000）
pm2 delete notelab-c notelab-b && pm2 save        # 移除两个壳
# 可选：恢复默认站点软链
ln -s /var/backups/nginx/default-site-enabled.bak /etc/nginx/sites-enabled/default
```

## 遗留事项

- 两个壳目前只是占位页，B/C 端实际页面在后续阶段迁入。
- 公网 :80 已开放（安全组/防火墙若有限制需另行确认）；本阶段未动任何现有服务配置。
- Cookie 签发脚本与 SSE 探测脚本为一次性脚本（已随验收完成删除），方法见上文第 3 条描述。
