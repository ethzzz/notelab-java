#!/usr/bin/env bash
# 服务器侧「从 git 仓库同步并重建」。
#
# 定位：本地改 → commit/push 之后，**服务器上跑这一条**即可完成同步+构建+重启。
#       服务器是只读部署目标，不要在 /root/<仓> 里手改业务代码。
#
# 用法：
#   sync-deploy.sh <仓|all> [--build] [--no-build] [--dry-run]
#   <仓> ∈ notelab-c | notelab-b | notelab-java | home | ai-lab
#
# 行为：校验分支与工作区 → git fetch --prune → git pull --ff-only →
#       有变更才构建并重启（**仅文档变更默认跳过**，用 --build 强制）。
#       工作区脏 → 拒绝执行：说明有人在服务器上手改了代码，先查清。
#
# 退出码：0 成功（含"已是最新"）；非 0 表示同步或构建失败，需人工介入。
set -euo pipefail

ROOT=/root
OPS_LOG_DIR=/root/ops
PROG=$(basename "$0")

BUILD=auto          # auto | force | skip
DRY_RUN=0

log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '[%s] 警告：%s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf '[%s] 失败：%s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit 1; }

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 0
}

ALL_REPOS="notelab-c notelab-b notelab-java home ai-lab"

TARGETS=()
for arg in "$@"; do
  case "$arg" in
    -h|--help)   usage ;;
    --build)     BUILD=force ;;
    --no-build)  BUILD=skip ;;
    --dry-run)   DRY_RUN=1 ;;
    -*)          die "未知参数：$arg（--help 看用法）" ;;
    all)         for r in $ALL_REPOS; do TARGETS+=("$r"); done ;;
    *)           TARGETS+=("$arg") ;;
  esac
done
[ ${#TARGETS[@]} -gt 0 ] || die "至少要给一个仓名（或用 all）"
for r in "${TARGETS[@]}"; do
  case " $ALL_REPOS " in *" $r "*) ;; *) die "不认识的仓：$r（支持：$ALL_REPOS）" ;; esac
done

# 只有文档变更时跳过构建：这些路径不会被构建期读取（若某页面直接渲染 md，用 --build 强制）
docs_only() {
  local f
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in *.md|ops/*|docs/*) ;; *) return 1 ;; esac
  done <<< "$1"
  return 0
}

# 等端口能应答 HTTP（不校验具体状态码——401/302/200 都算活着），只在完全无响应时失败
wait_http() {
  local url=$1 timeout=${2:-40} i=0 code
  while [ "$i" -lt "$timeout" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null || true)
    if [ -n "$code" ] && [ "$code" != "000" ]; then
      log "  探活 $url → HTTP $code"
      return 0
    fi
    sleep 1; i=$((i + 1))
  done
  warn "$url 在 ${timeout}s 内无 HTTP 响应（进程可能没起来，看 pm2 logs）"
  return 1
}

run_build() {   # $1=仓名 $2=工作目录内的构建命令
  local repo=$1 cmd=$2
  local logfile="$OPS_LOG_DIR/sync-deploy-$repo.log"
  mkdir -p "$OPS_LOG_DIR"
  log "  构建：$cmd （输出 → $logfile）"
  if [ "$DRY_RUN" = 1 ]; then log "  [dry-run] 跳过实际执行"; return 0; fi
  if ! ( eval "$cmd" ) >"$logfile" 2>&1; then
    tail -25 "$logfile" >&2
    die "$repo 构建失败（完整日志：$logfile）"
  fi
  log "  构建完成"
}

npm_install_if_needed() {  # $1=变更文件清单
  if printf '%s\n' "$1" | grep -qE '^(package(-lock)?\.json|pnpm-lock\.yaml)$'; then
    log "  package.json / lock 有变更 → 先 npm install"
    [ "$DRY_RUN" = 1 ] || npm install --no-audit --no-fund >/dev/null 2>&1 || die "npm install 失败"
  fi
}

pm2_restart() {
  local name=$1 probe=$2 timeout=${3:-40}
  if [ "$DRY_RUN" = 1 ]; then log "  [dry-run] pm2 restart $name"; return 0; fi
  pm2 restart "$name" --update-env >/dev/null 2>&1 || die "pm2 restart $name 失败"
  log "  pm2 $name 已重启"
  wait_http "$probe" "$timeout" || true   # 探活失败只告警：某些服务启动慢或需鉴权
}

build_and_restart() {  # $1=仓名 $2=变更文件清单
  local repo=$1 changed=$2
  case "$repo" in
    notelab-c)
      npm_install_if_needed "$changed"
      run_build "$repo" "npm run build"
      pm2_restart notelab-c "http://127.0.0.1:3010/games/" ;;
    notelab-b)
      npm_install_if_needed "$changed"
      run_build "$repo" "npm run build"
      pm2_restart notelab-b "http://127.0.0.1:3020/admin/" ;;
    notelab-java)
      run_build "$repo" "/usr/bin/mvn -B -DskipTests -q package"
      # Spring Boot 冷启动 ~15-20s，探活超时给足
      pm2_restart notelab-java "http://127.0.0.1:8001/api/menu" 60 ;;
    home)
      npm_install_if_needed "$changed"
      run_build "$repo" "npm install --no-audit --no-fund && npm run build"
      if [ "$DRY_RUN" = 1 ]; then log "  [dry-run] 跳过 /var/www/home 发布"; return 0; fi
      local ts bak; ts=$(date +%Y%m%d-%H%M%S); bak="/root/backups/home-static-$ts"
      cp -a /var/www/home "$bak" || die "备份 /var/www/home 失败，已中止发布"
      rm -rf /var/www/home && mkdir -p /var/www/home
      cp -a dist/. /var/www/home/ || die "回填 dist 失败（旧版备份仍在：$bak）"
      chmod -R a+rX /var/www/home
      log "  已发布到 /var/www/home（旧版备份：$bak）" ;;
    ai-lab)
      if printf '%s\n' "$changed" | grep -q '^frontend/'; then
        run_build "$repo" "cd frontend && npm install --no-audit --no-fund && npm run build"
      else
        log "  仅后端变更 → 只重启，不构建前端"
      fi
      pm2_restart ai-lab "http://127.0.0.1:8002/" ;;
    *) die "没有为 $repo 定义构建流程" ;;
  esac
}

sync_one() {
  local repo=$1 dir="$ROOT/$1"
  log "=== $repo ==="
  [ -d "$dir/.git" ] || die "$dir 不是 git 工作副本"
  cd "$dir"

  local branch; branch=$(git symbolic-ref --short HEAD 2>/dev/null || echo "")
  [ "$branch" = "main" ] || die "$repo 当前分支是 '$branch'，不是 main —— 先切回去"

  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    git status --short --untracked-files=no >&2
    die "$repo 工作区有未提交改动。服务器上不应手改业务代码 —— 先 git diff 查清来源；
   确认这些改动是垃圾再 git checkout -- <file> 丢弃，或把它们挪回本地仓库。"
  fi

  local before after
  before=$(git rev-parse HEAD)
  git fetch origin --prune --quiet || die "$repo git fetch 失败"
  if ! git pull --ff-only --quiet; then
    die "$repo 无法快进合并（本地与 origin 分叉），需人工介入"
  fi
  after=$(git rev-parse HEAD)

  if [ "$before" = "$after" ]; then
    log "已是最新（${after:0:7}），无需构建"
    return 0
  fi

  local changed n
  changed=$(git diff --name-only "$before" "$after")
  n=$(printf '%s\n' "$changed" | grep -c . || true)
  log "更新 ${before:0:7} → ${after:0:7}（$n 个文件）"

  if [ "$BUILD" != force ] && docs_only "$changed"; then
    log "仅文档变更 → 跳过构建与重启（要强制构建加 --build）"
    return 0
  fi
  if [ "$BUILD" = skip ]; then
    log "--no-build：已同步，跳过构建与重启"
    return 0
  fi

  build_and_restart "$repo" "$changed"
  log "=== $repo 同步完成 ==="
}

for r in "${TARGETS[@]}"; do sync_one "$r"; done
log "全部完成：${TARGETS[*]}"
