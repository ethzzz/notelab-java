# 日志与排障（2026-10-21 起）

> 一句话：**每条请求有一个 `rid`，nginx 和应用都带着它；查问题先拿 rid，再 `logs.sh req <rid>`。**

## 1. 链路

```
浏览器
  │  X-Request-Id（客户端可自带，但会被 nginx 覆盖）
  ▼
nginx ── $request_id ──────────────────────────────┐
  │  proxy_set_header X-Request-Id $request_id     │
  │  access_log /var/log/nginx/notelab.json.log    │  JSON 一行一条
  ▼                                                │
notelab-java                                        │
  │  RequestLoggingFilter：读头 → MDC(reqId) → 回显响应头
  │  http.access：method path status ms ip ua
  ▼                                                │
/root/.pm2/logs/notelab-java-out.log ◄─────────────┘
  （所有日志行都带 [rid]，由 logback-spring.xml 统一格式）
```

**同一个 rid 在两侧都能搜到**：nginx 的 `"rid":"..."` 与 Java 的 `[rid]`。

## 2. 日志位置

| 来源 | 路径 | 轮转 |
|---|---|---|
| 应用（全部 pm2 进程） | `/root/.pm2/logs/<name>-{out,error}.log` | pm2-logrotate：50M / 保留 14 份 / 压缩 / 每日 |
| nginx JSON 访问日志 | `/var/log/nginx/notelab.json.log` | logrotate：每日，14 份 |
| nginx 传统日志 | `/var/log/nginx/access.log`、`error.log` | 同上（保留兼容） |
| MySQL | `/var/log/mysql/error.log` | 每日 7 份 |
| Redis | `/var/log/redis/redis-server.log` | 每周 10 份 |
| Kokoro 容器 | `docker logs kokoro` | json-file（84K，未设上限） |
| 部署 | `/root/ops/sync-deploy-<仓>.log` | 每次覆盖 |
| 每日巡检 | `/root/ops/reports/YYYY-MM-DD.md` | 30 天 |

Java 本身**不写文件**（没有 file appender），统一输出 stdout 交给 pm2 —— 这样轮转、清理、按进程过滤都只有一处。

## 3. 查询工具 `ops/logs.sh`

```bash
cd /root/Notelab/notelab-java

bash ops/logs.sh req 679efbf61b539ed4e4717d6e31866c09   # 按 rid 串 nginx + 应用
bash ops/logs.sh err --since 30m --lines 100             # 最近的 ERROR（带 rid）
bash ops/logs.sh slow --ms 1000 --since 2h               # 慢请求（rt 来自 nginx）
bash ops/logs.sh 5xx                                     # 服务端错误
bash ops/logs.sh stats --since 1h                        # 状态码分布 / TOP 路径 / 平均耗时
bash ops/logs.sh tail notelab-java 200                   # 跟日志
bash ops/logs.sh list                                    # 有哪些日志文件
```

依赖：`bash`、`grep`、`gawk`、`date`（不需要 jq、不需要装 ELK）。

## 4. 三个常见场景

### 用户报「点了没反应」

1. 让他打开控制台，看失败请求的响应头 `X-Request-Id`（或 Network → Response Headers）。
2. `logs.sh req <rid>`：能看到 nginx 的 status/rt，以及应用里同一 rid 的所有日志。
3. 如果 nginx 有、应用没有 → 请求没到后端（查 nginx error.log / upstream 是否挂）。
4. 如果应用里有 ERROR → 堆栈与 rid 在同一行上下文里，直接定位。

### 页面变慢

```bash
bash ops/logs.sh slow --ms 800 --since 30m
```

输出是 nginx 的原始 JSON 行，`rt` 是总耗时、`ut` 是上游耗时：

- `rt` 大、`ut` 小 → 慢在 nginx 之外（客户端/网络/静态资源）；
- `rt` 与 `ut` 接近且都大 → 慢在后端，转 `logs.sh req` 看该请求的应用耗时。

### 错误突然变多

```bash
bash ops/logs.sh 5xx | head -50
bash ops/logs.sh err --since 10m
```

`5xx` 同时输出 nginx 5xx 与应用 ERROR；若 nginx 没有 5xx 而应用在刷 ERROR，说明是**被捕获但没返回 5xx** 的错误（例如 Server Action 解析失败），按 rid 对照即可。

## 5. 已知噪音（别误判成故障）

| 现象 | 来源 | 处理 |
|---|---|---|
| `Server Reference ID did not match the expected format` | 爬虫乱 POST `/admin`，触发 Next Server Action 解析失败 | 非业务错误；量大时在 nginx 按 UA/路径限流 |
| `[tts] Kokoro HTTP 401` | 2026-10-21 前 B 端进程缺 `KOKORO_API_KEY` | 已注入 pm2 env（`pm2 save` 已持久化），新日志不应再出现 |
| `ClassNotFoundException`（关机期） | fat jar 关闭钩子 | 无害 |
| `Failed to load static file for page: /500` | App Router 缺 `pages/500.html` 的框架噪音 | 可选：补 `app/global-error.tsx` 或忽略 |
| `Could not find a production build in '.next'` | 部署窗口内 `.next` 正被重建 | 部署结束后仍持续出现 → 重建产物 |

## 6. 每日巡检第 7 节

`ops/daily-iteration/daily-check.py` 每天 7:30 跑，第 7 节就是这套日志体系的消费方：

- 应用 ERROR 按 `rid` 聚合（TOP 5 + 样本），直接 `logs.sh req <rid>` 深挖；
- Next/Python 的 error.log 按类型聚合（已标注爬虫噪音）；
- nginx JSON 统计 24h 请求量 / 5xx 数 / 5xx 占比 / 5xx 路径 TOP（≥1% 黄、≥5% 红）。

报告在 `/root/ops/reports/YYYY-MM-DD.md`。

## 7. 加日志的约定

- 用 `log.info("key={} key2={}", a, b)` 的 key=value 形式，**不要拼字符串**；
- 需要跨行关联的字段放 MDC（目前只有 `reqId`），不要塞进 message；
- 新加的服务：只要输出 stdout 并注册到 pm2，就自动获得轮转与 `logs.sh` 查询；
- 如果加新的 nginx 反代 location，记得 `proxy_set_header X-Request-Id $request_id;`（否则该链路没有 rid）。
