#!/usr/bin/env bash
# 快捷入口：同步 上面四个依次同步（哪个失败就停在哪）
# 逻辑在 sync-repo.sh（参数原样透传：--build / --no-build / --dry-run / -h）
# 在服务器上直接跑本机实现，在本机走 ssh —— 同一个文件两边都能用。
set -euo pipefail
exec bash "$(dirname "$0")/sync-repo.sh" all "$@"