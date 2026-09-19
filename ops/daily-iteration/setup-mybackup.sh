#!/usr/bin/env bash
# NoteLab 数据库备份 · 凭据初始化脚本（在服务器执行一次；MySQL 密码变更后可重跑）。
# 作用：按 AppConfig 相同优先级（java .env > python .env > 默认）解析 MySQL 连接参数，
#       生成 /root/.my.cnf（chmod 600）供 mysqldump 免命令行密码使用，并把库名写到 /root/ops/.backup-db-name。
# 备份脚本 backup-db.sh 随 notelab-java 仓管理；定时任务用同目录 install-cron.sh 安装。详见 README.md。
# 纪律：密码全程只在服务器内流转，脚本不回显任何密码值。
set -uo pipefail

get_key() {
  local key="$1" def="$2" f line val
  for f in /root/notelab-java/.env /root/notelab/.env; do
    [ -f "$f" ] || continue
    line=$(grep -E "^[[:space:]]*${key}=" "$f" | tail -1)
    [ -n "$line" ] || continue
    val="${line#*=}"
    val="${val%\"}"; val="${val#\"}"
    val="${val%\'}"; val="${val#\'}"
    val="$(printf '%s' "$val" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if [ -n "$val" ]; then printf '%s' "$val"; return; fi
  done
  printf '%s' "$def"
}

MY_HOST="$(get_key MYSQL_HOST 127.0.0.1)"
MY_PORT="$(get_key MYSQL_PORT 3306)"
MY_USER="$(get_key MYSQL_USER notelab)"
MY_PASS="$(get_key MYSQL_PASSWORD '')"
MY_DB="$(get_key MYSQL_DB notelab)"

if [ -z "$MY_PASS" ]; then
  echo "ERROR: 无法从 .env 解析 MYSQL_PASSWORD"; exit 1
fi

umask 077
cat > /root/.my.cnf <<EOF
[client]
host=${MY_HOST}
port=${MY_PORT}
user=${MY_USER}
password="${MY_PASS}"
EOF
chmod 600 /root/.my.cnf
mkdir -p /root/ops
printf '%s\n' "$MY_DB" > /root/ops/.backup-db-name
chmod 600 /root/ops/.backup-db-name
echo "已写 /root/.my.cnf 与 /root/ops/.backup-db-name（host=$MY_HOST port=$MY_PORT user=$MY_USER db=$MY_DB，密码已隐藏）"

if mysql --defaults-file=/root/.my.cnf -e "SELECT 1" "$MY_DB" >/dev/null 2>&1; then
  echo "数据库连通性 OK"
else
  echo "数据库连通性 FAILED"; exit 1
fi

echo "下一步：bash /root/notelab-java/ops/daily-iteration/install-cron.sh 安装定时备份+巡检"
