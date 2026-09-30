#!/usr/bin/env bash
# ArchGuard 扫描 + 入库。用法（在 notelab-java 仓内执行）：
#   bash ops/archguard/run.sh                 # 扫描并入库
#   bash ops/archguard/run.sh --no-persist    # 只扫描不入库
#   bash ops/archguard/run.sh --dry           # 只入库预览（打印 SQL，不连库）
#   bash ops/archguard/run.sh --no-persist --dry  # 两者都不做，等价于只扫描
# 输出报告落在本目录 arch-report.json。凭据走 /root/.my.cnf（见 src/persist.js）。
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [ ! -d node_modules ]; then
  echo "[archguard] 首次运行，安装依赖（java-parser + ts-morph）..."
  npm ci --no-audit --no-fund || exit 3
fi

WANT_PERSIST=1
WANT_DRY=0
for a in "$@"; do
  if [ "$a" = "--no-persist" ]; then WANT_PERSIST=0; fi
  if [ "$a" = "--dry" ]; then WANT_DRY=1; fi
done

node src/index.js "$@"
rc=$?
[ "$rc" -ne 0 ] && exit "$rc"

if [ "$WANT_PERSIST" -eq 0 ]; then
  echo "[archguard] --no-persist：未入库"
elif [ "$WANT_DRY" -eq 1 ]; then
  node src/persist.js --report "$DIR/arch-report.json" --dry
else
  node src/persist.js --report "$DIR/arch-report.json"
fi
exit $?
