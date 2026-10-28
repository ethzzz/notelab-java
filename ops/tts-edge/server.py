#!/usr/bin/env python3
"""
Kokoro 的替身：把「OpenAI 兼容 TTS 接口」转到微软 Edge TTS。

为什么要它
----------
博客「朗读全文」与 B 端英语学习都调 `POST /v1/audio/speech`（原先是本机 Kokoro 容器）。
Kokoro 常驻 1GB 内存、合成一段 108 字要 24 秒（比实时还慢 2.4 倍），还得每分钟预热一次
才不至于首块 74 秒。实测同一段文本 Edge TTS 只要 2.5 秒、零常驻内存。

但它不能直接换：调用方认的是 Kokoro 的音色名（`zf_xiaoxiao` / `af_heart`）和 OpenAI 的请求体。
所以这里做一个**协议适配层** —— 只要把端口换掉，前端一行都不用改，随时可以切回容器。

接口
----
POST /v1/audio/speech   {model, input, voice, response_format, speed} -> audio/mpeg
GET  /health            存活 + 引擎信息
GET  /health?deep=1     真合成一个字符（用来验证微软端点有没有又 403）

约定
----
- 鉴权：`Authorization: Bearer <TTS_API_KEY>`。**必须配 key** —— 这个端口经 nginx 反代后是公网可达的，
  不设 key 就等于让人拿你的服务器 IP 去刷微软接口，把 IP 刷进限流名单。
- 限流：单 IP 30 次/分钟、全局 600 次/分钟，内存滑窗（重启即清零，够用）。
- 文本上限 2000 字（博客按 110 字分块，留足余量）。
"""
from __future__ import annotations

import asyncio
import hmac
import logging
import os
import time
from collections import defaultdict, deque

import edge_tts
from aiohttp import web

LOG = logging.getLogger("tts-edge")

# ---------------------------------------------------------------- 配置

PORT = int(os.getenv("TTS_PORT", "8881"))
API_KEY = os.getenv("TTS_API_KEY", "")
MAX_CHARS = int(os.getenv("TTS_MAX_CHARS", "2000"))
RATE_PER_IP = int(os.getenv("TTS_RATE_PER_IP", "30"))    # 次/分钟
RATE_GLOBAL = int(os.getenv("TTS_RATE_GLOBAL", "600"))   # 次/分钟
TIMEOUT_S = float(os.getenv("TTS_TIMEOUT_S", "20"))
OUTPUT_FORMAT = os.getenv("TTS_OUTPUT_FORMAT", "audio-24khz-48kbitrate-mono-mp3")

# Kokoro 音色 -> Edge 音色。调用方传的是前者（历史契约），这里翻译成后者。
VOICE_MAP = {
    # 中文（博客朗读用 zf_xiaoxiao）
    "zf_xiaoxiao": "zh-CN-XiaoxiaoNeural",
    "zf_xiaoyi": "zh-CN-XiaoyiNeural",
    "zm_yunxi": "zh-CN-YunxiNeural",
    "zm_yunjian": "zh-CN-YunjianNeural",
    # 英文（B 端英语学习用 Aria/Jenny/Guy/Sonia）
    "af_heart": "en-US-AriaNeural",
    "af_nova": "en-US-JennyNeural",
    "am_michael": "en-US-GuyNeural",
    "bf_emma": "en-GB-SoniaNeural",
    # 兼容：直接传 Edge 音色名也认
    "zh-CN-XiaoxiaoNeural": "zh-CN-XiaoxiaoNeural",
    "zh-CN-XiaoyiNeural": "zh-CN-XiaoyiNeural",
    "en-US-AriaNeural": "en-US-AriaNeural",
    "en-US-JennyNeural": "en-US-JennyNeural",
    "en-US-GuyNeural": "en-US-GuyNeural",
    "en-GB-SoniaNeural": "en-GB-SoniaNeural",
}
DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"


def resolve_voice(raw: str | None) -> str:
    v = (raw or "").strip()
    return VOICE_MAP.get(v, DEFAULT_VOICE)


def resolve_rate(speed: float | None) -> str:
    """OpenAI 的 speed(倍率) -> Edge 的 rate(百分比字符串)。"""
    try:
        s = float(speed) if speed is not None else 1.0
    except (TypeError, ValueError):
        s = 1.0
    s = max(0.5, min(2.0, s))
    pct = round((s - 1) * 100)
    return f"{pct:+d}%"


# ---------------------------------------------------------------- 限流

_ip_hits: dict[str, deque[float]] = defaultdict(deque)
_all_hits: deque[float] = deque()
_rl_lock = asyncio.Lock()


async def rate_limited(ip: str) -> str | None:
    """返回 None 表示放行，否则返回拒绝原因。"""
    now = time.monotonic()
    async with _rl_lock:
        ips = _ip_hits[ip]
        while ips and now - ips[0] > 60:
            ips.popleft()
        while _all_hits and now - _all_hits[0] > 60:
            _all_hits.popleft()
        if len(ips) >= RATE_PER_IP:
            return f"单个 IP 每分钟最多 {RATE_PER_IP} 次"
        if len(_all_hits) >= RATE_GLOBAL:
            return f"服务每分钟最多 {RATE_GLOBAL} 次"
        ips.append(now)
        _all_hits.append(now)
    return None


# ---------------------------------------------------------------- 合成

async def synthesize(text: str, voice: str, rate: str) -> bytes:
    comm = edge_tts.Communicate(text, voice, rate=rate)
    buf = bytearray()
    async for chunk in comm.stream():
        if chunk.get("type") == "audio" and chunk.get("data"):
            buf.extend(chunk["data"])
    return bytes(buf)


def client_ip(req: web.Request) -> str:
    # nginx 反代时带 X-Real-IP；直连（本机 curl）退回 peer 地址
    return req.headers.get("X-Real-IP") or req.headers.get("X-Forwarded-For", "").split(",")[0].strip() or (
        req.remote or "unknown"
    )


# ---------------------------------------------------------------- 路由

async def handle_speech(req: web.Request) -> web.StreamResponse:
    started = time.monotonic()
    ip = client_ip(req)

    # 1) 鉴权（放在解析 body 之前：匿名请求不该触发任何后续处理）
    if API_KEY:
        auth = req.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        if not hmac.compare_digest(token, API_KEY):
            LOG.warning("401 ip=%s auth=%s", ip, auth[:16] or "<empty>")
            return web.json_response({"error": "unauthorized"}, status=401)

    # 2) 限流
    blocked = await rate_limited(ip)
    if blocked:
        LOG.warning("429 ip=%s %s", ip, blocked)
        return web.json_response({"error": blocked}, status=429)

    # 3) 解析
    try:
        body = await req.json()
    except Exception:
        return web.json_response({"error": "invalid json"}, status=400)

    text = str(body.get("input") or body.get("text") or "").strip()
    if not text:
        return web.json_response({"error": "input 不能为空"}, status=400)
    if len(text) > MAX_CHARS:
        return web.json_response({"error": f"文本过长（最大 {MAX_CHARS} 字）"}, status=400)

    voice = resolve_voice(body.get("voice"))
    rate = resolve_rate(body.get("speed"))

    # 4) 合成
    try:
        audio = await asyncio.wait_for(synthesize(text, voice, rate), timeout=TIMEOUT_S)
    except asyncio.TimeoutError:
        LOG.error("502 ip=%s 合成超时 %.0fs voice=%s chars=%d", ip, TIMEOUT_S, voice, len(text))
        return web.json_response({"error": "语音合成超时"}, status=504)
    except Exception as e:  # 微软端点 403/429 会走到这里
        LOG.error("502 ip=%s voice=%s chars=%d err=%s", ip, voice, len(text), str(e)[:160])
        return web.json_response({"error": f"合成失败：{str(e)[:120]}"}, status=502)

    if not audio:
        LOG.error("502 ip=%s 空音频 voice=%s chars=%d", ip, voice, len(text))
        return web.json_response({"error": "合成结果为空"}, status=502)

    LOG.info(
        "200 ip=%s voice=%s->%s rate=%s chars=%d bytes=%d cost=%dms",
        ip, body.get("voice"), voice, rate, len(text), len(audio), int((time.monotonic() - started) * 1000),
    )
    return web.Response(
        body=audio,
        content_type="audio/mpeg",
        headers={"Cache-Control": "no-store", "X-TTS-Engine": "edge"},
    )


async def handle_health(req: web.Request) -> web.Response:
    info = {
        "ok": True,
        "engine": "edge-tts",
        "version": getattr(edge_tts, "__version__", "unknown"),
        "voices": len(VOICE_MAP),
        "chars_limit": MAX_CHARS,
        "auth_required": bool(API_KEY),
    }
    # deep=1：真合成一个字符，验证微软端点还通不通（403 会在这里暴露）
    if req.query.get("deep") == "1":
        try:
            audio = await asyncio.wait_for(synthesize("你好", DEFAULT_VOICE, "+0%"), timeout=TIMEOUT_S)
            info["deep"] = {"ok": bool(audio), "bytes": len(audio)}
        except Exception as e:
            info["ok"] = False
            info["deep"] = {"ok": False, "error": str(e)[:160]}
            return web.json_response(info, status=503)
    return web.json_response(info)


def build_app() -> web.Application:
    app = web.Application(client_max_size=1 << 20)
    app.router.add_post("/v1/audio/speech", handle_speech)
    app.router.add_get("/health", handle_health)
    return app


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not API_KEY:
        LOG.warning("TTS_API_KEY 为空 —— 端口暴露在公网时等于没有防护，务必配置")
    LOG.info("listening on 127.0.0.1:%d (voices=%d, per-ip=%d/min)", PORT, len(VOICE_MAP), RATE_PER_IP)
    web.run_app(build_app(), host="127.0.0.1", port=PORT, print=None)
