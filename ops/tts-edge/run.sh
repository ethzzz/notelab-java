#!/usr/bin/env bash
# pm2 入口：加载 .env 后用 venv 里的 python 跑 server.py。
# 单独拆一个脚本，是因为 pm2 不会读 .env，而把密钥写进 pm2 命令行会被 `ps aux` 看见。
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$DIR/.env"
  set +a
fi

PY="$DIR/venv/bin/python"
if [ ! -x "$PY" ]; then
  echo "venv 不存在：先执行 $DIR/install.sh" >&2
  exit 1
fi

exec "$PY" "$DIR/server.py"
