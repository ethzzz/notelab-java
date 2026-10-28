#!/usr/bin/env bash
# 安装 / 升级 tts-edge：建 venv、装依赖、生成 .env、交给 pm2 托管。
# 幂等：重复执行只会重装依赖并重启进程，不会覆盖已有的 .env。
#
# 用法（服务器上，root）：
#   TTS_API_KEY=kk-xxxx bash /root/ops/tts-edge/install.sh
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY_BIN="${PY_BIN:-python3}"
PIP_INDEX="${PIP_INDEX:-https://pypi.tuna.tsinghua.edu.cn/simple}"

echo "[1] venv + 依赖（edge-tts / aiohttp）"
[ -x "$DIR/venv/bin/python" ] || "$PY_BIN" -m venv "$DIR/venv"
"$DIR/venv/bin/pip" install -q --upgrade pip
"$DIR/venv/bin/pip" install -q -i "$PIP_INDEX" --upgrade edge-tts aiohttp
"$DIR/venv/bin/python" -c "import edge_tts; print('    edge-tts', getattr(edge_tts, '__version__', '?'))"

echo "[2] .env"
if [ -f "$DIR/.env" ]; then
  echo "    已存在，保持不变"
else
  KEY="${TTS_API_KEY:-}"
  if [ -z "$KEY" ]; then
    KEY="kk-$(head -c 32 /dev/urandom | base64 | tr -dc 'a-z0-9' | head -c 28)"
    echo "    未提供 TTS_API_KEY，已生成随机 key（下文只打印前 6 位）"
  fi
  sed "s|^TTS_API_KEY=.*|TTS_API_KEY=$KEY|" "$DIR/.env.example" > "$DIR/.env"
  chmod 600 "$DIR/.env"
  echo "    key=${KEY:0:6}…（完整值见 $DIR/.env）"
fi

echo "[3] pm2 托管"
pm2 delete notelab-tts >/dev/null 2>&1 || true
pm2 start "$DIR/run.sh" --name notelab-tts --interpreter bash --cwd "$DIR" >/dev/null
pm2 save >/dev/null
sleep 2

echo "[4] 健康检查（deep=1 会真合成两个字符，能验证微软端点通不通）"
KEY="$(grep '^TTS_API_KEY=' "$DIR/.env" | cut -d= -f2-)"
PORT="$(grep '^TTS_PORT=' "$DIR/.env" | cut -d= -f2- || true)"
PORT="${PORT:-8881}"
if curl -s -m 15 "http://127.0.0.1:${PORT}/health?deep=1" -H "Authorization: Bearer $KEY"; then
  echo
else
  echo "!! 健康检查失败，看 pm2 logs notelab-tts"
  exit 1
fi
