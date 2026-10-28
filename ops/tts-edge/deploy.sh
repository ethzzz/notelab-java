#!/usr/bin/env bash
# 把仓库里的源码同步到运行目录再安装。
# 为什么分两步：运行目录 /root/ops/tts-edge 里有 .env（密钥）和 venv，这两样不该进仓库，
# 也不该被仓库覆盖；代码文件则始终以仓库为准。
#
# 用法（服务器上，root）：
#   bash /root/Notelab/notelab-java/ops/tts-edge/deploy.sh
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DST="${DST:-/root/ops/tts-edge}"

mkdir -p "$DST"
install -m 644 "$SRC/server.py" "$SRC/README.md" "$SRC/.env.example" "$DST/"
install -m 755 "$SRC/run.sh" "$SRC/install.sh" "$DST/"
echo "[deploy] 代码已同步 → $DST（.env 与 venv 未覆盖）"

bash "$DST/install.sh"
