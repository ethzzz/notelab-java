#!/usr/bin/env bash
# 同步并重建某个仓：同步 git → 构建 → 重启 pm2 → HTTP 探活。
#
# 两种跑法，**同一个脚本**：
#   · 在服务器上（能看到 /root/Notelab 的环境）→ 直接执行 ops/sync-deploy.sh
#   · 在本机 → ssh 让服务器执行它
# ⚠️ 之所以不写死 ssh：这些入口会随仓库同步到服务器，若无条件 ssh 就会在服务器上自己连自己。
#
# 用法：sync-repo.sh <仓|all> [--build | --no-build | --dry-run | -h]
#   <仓> ∈ notelab-java | notelab-b | notelab-c | ai-lab | home | all
#
# 日常不必直接调它，用同目录的快捷入口：
#   bash sync-notelab-java.sh | sync-notelab-b.sh | sync-notelab-c.sh | sync-ai-lab.sh | sync-all.sh
#
# 服务器脚本的行为（细节见 ops/sync-deploy.sh 头部注释）：
#   · 工作区脏 → 直接拒绝（说明有人在服务器上手改过代码，先查清来源）
#   · HEAD 没变 → 打印「已是最新」，不构建
#   · 只改了 *.md / ops/* / docs/* → 默认跳过构建与重启，要强制重建加 --build
#   · 构建失败 → **不重启**，老进程继续服务（"没变化"时先看 /root/ops/sync-deploy-<仓>.log）
set -euo pipefail

SERVER_SCRIPT=/root/Notelab/notelab-java/ops/sync-deploy.sh
KNOWN="notelab-java notelab-b notelab-c ai-lab home all"

REPO=${1:-}
shift || true

if [ -z "$REPO" ] || [ "$REPO" = "-h" ] || [ "$REPO" = "--help" ]; then
	echo "用法：$(basename "$0") <仓|all> [--build | --no-build | --dry-run | -h]" >&2
	echo "  仓 ∈ $KNOWN" >&2
	echo "" >&2
	echo "快捷入口：bash sync-notelab-java.sh / sync-notelab-b.sh / sync-notelab-c.sh / sync-ai-lab.sh / sync-all.sh" >&2
	echo "注意：只有文档变更（*.md / ops/* / docs/*）时默认不重建，要强制加 --build。" >&2
	exit 2
fi

# 本地先校验仓名：拼错了没必要白跑一趟 ssh
case " $KNOWN " in
	*" $REPO "*) ;;
	*) echo "[x] 不认识的仓：$REPO（支持：$KNOWN）" >&2; exit 2 ;;
esac

if [ -f "$SERVER_SCRIPT" ]; then
	# 已经在服务器上：直接跑实现，别再 ssh
	exec bash "$SERVER_SCRIPT" "$REPO" "$@"
fi

# 在本机：ssh 只接受**单个命令字符串**，所以把仓名与开关拼成一条
exec ssh -n myapp "$SERVER_SCRIPT $REPO $*"
