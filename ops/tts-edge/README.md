# tts-edge：用微软 Edge TTS 顶替本机 Kokoro 容器

## 为什么要这个东西

博客「朗读全文」和 B 端英语学习都调 `POST /v1/audio/speech`，原先由本机 Kokoro 容器（Docker `kokoro`，:8880）提供。实测下来它的成本与形态完全不匹配：

| | Kokoro 容器 | Edge TTS（本服务） |
|---|---|---|
| 常驻内存 | **999.9 MiB**（整机 26%） | 0（进程级 ~80MB，跑完就退） |
| 磁盘 | 镜像 2.8 GB + volume 359 MB | ~60 MB（venv） |
| 同一段 108 字中文合成耗时 | **24.6 s**（比实时慢 2.4×） | **2.5 s** |
| 冷启动首块 | 74 s（所以原先有每分钟一次的预热 cron） | 不需要预热 |
| 月请求量 | **130 次**（`/blog/tts`）+ 2 次（`/admin/api/tts`） | 同 |

⚠️ 另外：代码里原来写着「Kokoro 挂了就回退 edge-tts」，但那个兜底**是坏的**。
微软 2026-10 把 edge-tts 用的老语音端点整批 403（[edge-tts #490]），
服务器装的 Node 移植版 `node-edge-tts@1.2.10` 没跟上；Python 官方版 7.3.x 已修复。
所以本服务的依赖必须保持**可升级**（`install.sh` 每次跑都会 `--upgrade edge-tts`）。

[edge-tts #490]: https://github.com/rany2/edge-tts/issues/490

## 它做什么

**协议适配层**，不是新能力：调用方认的是 Kokoro 的音色名（`zf_xiaoxiao` / `af_heart`）和 OpenAI 的请求体，这里翻译成 Edge 的音色与 `rate` 百分比，返回 `audio/mpeg`。所以切换只需换端口，前端一行不改，随时可切回容器。

- `POST /v1/audio/speech` `{model, input, voice, response_format, speed}` → `audio/mpeg`
- `GET /health` / `GET /health?deep=1`（deep 会真合成两个字符，用来探测微软端点）

## 安全

- **必须配 `TTS_API_KEY`**。这个端口经 nginx 反代后公网可达；不设 key 等于让别人拿你的服务器 IP 去刷微软接口，会把 IP 刷进限流名单。
- 限流：单 IP 30 次/分钟、全局 600 次/分钟（内存滑窗，重启清零）。
- 文本上限 2000 字；只绑 `127.0.0.1`，外网经 nginx 进。

## 部署 / 回滚

```bash
# 首次安装或升级（幂等；TTS_API_KEY 走环境变量，别写进命令历史里）
TTS_API_KEY=kk-xxxx bash /root/ops/tts-edge/install.sh

# nginx：把 /blog/tts/ 的 proxy_pass 从 8880 改成 8881，先备份再 nginx -t
cp /etc/nginx/sites-enabled/notelab /root/backups/notelab-$(date +%Y%m%d-%H%M)

# B 端 route handler 用的是 KOKORO_TTS_URL，切过去后要重启（不需要重新构建）
#   .env.production: KOKORO_TTS_URL=http://127.0.0.1:8881  +  KOKORO_API_KEY=$TTS_API_KEY
pm2 restart notelab-b

# 回滚：nginx 改回 8880、KOKORO_TTS_URL 改回 8880、pm2 restart notelab-b，
# 容器一直没删，docker start kokoro 十秒就能回来。
```

## 已知风险（写在这里，免得以后当成 bug 查）

1. **微软再改一次端点就会 403**。判据：`/health?deep=1` 返回 503 且日志里 `err=Unexpected server response: 403`。
   处置：`pip install --upgrade edge-tts` 后重启；升完还不行就是出口 IP 被限，只能切回容器或换本地引擎。
2. **这不是「开源模型」**，是把微软朗读端点当免费接口用，ToS 灰色。要彻底干净只能自建离线引擎
   （sherpa-onnx + Kokoro ONNX，约 200–300MB，快于实时），接口契约与这里相同，换实现不影响调用方。
3. 文本会出网到微软。博客正文本来就是公开的；B 端英语学习句子的内容也不敏感。
