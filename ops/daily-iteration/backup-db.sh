#!/usr/bin/env bash
# NoteLab 每日数据库备份：mysqldump -> gzip -> /root/backups，保留最近 KEEP 份滚动。
# 凭据读 /root/.my.cnf（chmod 600，由 setup-mybackup.sh 从 .env 生成），密码不上命令行。
# 权威副本：本文件（notelab-java 仓 ops/daily-iteration/），由 cron 直接执行；产物落 /root/backups。
set -uo pipefail
BACKUP_DIR=/root/backups
KEEP=7
DB="$(cat /root/ops/.backup-db-name 2>/dev/null || echo notelab)"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="${BACKUP_DIR}/notelab-${TS}.sql.gz"
mkdir -p "$BACKUP_DIR"
mysqldump --defaults-file=/root/.my.cnf \
  --single-transaction --quick --routines --triggers \
  --default-character-set=utf8mb4 "$DB" 2>/tmp/backup-db.err | gzip > "$OUT"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ] || [ ! -s "$OUT" ]; then
  echo "[$(date '+%F %T')] BACKUP FAILED rc=$rc $(tail -1 /tmp/backup-db.err 2>/dev/null)"
  rm -f "$OUT"; exit 1
fi
# 滚动保留最近 KEEP 份
ls -1t "${BACKUP_DIR}"/notelab-*.sql.gz 2>/dev/null | tail -n +$((KEEP+1)) | xargs -r rm -f
echo "[$(date '+%F %T')] backup ok: $OUT ($(du -h "$OUT" | cut -f1))"
