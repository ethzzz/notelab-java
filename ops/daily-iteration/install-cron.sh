#!/usr/bin/env bash
# 安装/刷新 NoteLab 每日迭代的两条 cron（幂等：先剔除同类旧条目再追加）。
# 关键设计：cron 直接执行 notelab-java 仓 ops/daily-iteration/ 内的脚本（单一真相源），
#          避免"仓里一份、/root/ops 一份"的副本漂移。运行产物仍落 /root/ops 与 /root/backups。
set -uo pipefail
DIR=/root/notelab-java/ops/daily-iteration
CHECK="$DIR/daily-check.py"
BACKUP="$DIR/backup-db.sh"
[ -f "$CHECK" ]  || { echo "缺少 $CHECK";  exit 1; }
[ -f "$BACKUP" ] || { echo "缺少 $BACKUP"; exit 1; }
chmod +x "$BACKUP"
mkdir -p /root/ops/reports /root/backups
# 保留不含本机制脚本的其它 cron 条目，再重新追加这两条
crontab -l 2>/dev/null | grep -vE 'daily-check\.py|backup-db\.sh' > /tmp/cron.base
{
  cat /tmp/cron.base
  echo "0 3 * * * $BACKUP >>/root/ops/backup.log 2>&1"
  echo "30 7 * * * /usr/bin/python3 $CHECK >/dev/null 2>>/root/ops/cron.log"
} | crontab -
rm -f /tmp/cron.base
echo "--- 当前 crontab ---"
crontab -l
