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
# 资源守护（2026-10-27 宕机后加，2 vCPU / 3.9 GB 机器上的硬约束）：
#   · 全局部署锁：同一时刻只允许一个部署，后来的排队而不是并发叠加；
#   · 构建前体检：可用内存/swap/负载/磁盘不达标就等，等不到就拒绝；
#   · 构建限额：CPU/内存/swap/超时全部封顶（默认见 ops/build-guard.sh：180% / 3200M / 1536M / 1800s），
#     超限只杀构建自己，nginx 与 pm2 服务不受影响。可用同名环境变量覆盖：
#     BUILD_CPU_QUOTA、BUILD_MEM_HIGH、BUILD_MEM_MAX、BUILD_SWAP_MAX、BUILD_TIMEOUT、
#     MIN_MEM_MB、MIN_SWAP_MB、MIN_DISK_MB、LOCK_WAIT。
#
# 退出码：0 成功（含"已是最新"）；非 0 表示同步或构建失败，需人工介入。
set -euo pipefail

ROOT=/root                        # 家目录：日志/备份仍在 /root/ops、/root/backups
REPO_ROOT=/root/Notelab           # 三个 notelab 仓所在（2026-10-01 从 /root 平铺迁入）
OPS_LOG_DIR=/root/ops
PROG=$(basename "$0")
OPS_DIR=$(cd "$(dirname "$0")" && pwd)

# 构建资源上限的唯一来源是 ops/build-guard.sh（默认值写在那里，避免两处漂移）；
# 临时放宽在调用前 export 即可，例如 BUILD_MEM_MAX=4000M ./sync-deploy.sh notelab-b。

# 构建前体检红线
MIN_MEM_MB=${MIN_MEM_MB:-800}
MIN_SWAP_MB=${MIN_SWAP_MB:-256}
MIN_DISK_MB=${MIN_DISK_MB:-2048}
PREFLIGHT_WAIT=${PREFLIGHT_WAIT:-90}
MAX_LOAD_FACTOR=${MAX_LOAD_FACTOR:-2}

LOCK_FILE=/run/notelab-deploy.lock
LOCK_OWNER=/run/notelab-deploy.owner
LOCK_WAIT=${LOCK_WAIT:-1800}

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

ARGS="$*"

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

# 全局部署锁：agent 并行发起多个 ssh 部署时，这里排队而不是让两个 npm/mvn 同时跑。
# 2026-10-27 宕机复盘：构建把 2 核吃满、swap 被打穿，整机失去响应，只能硬重启。
acquire_deploy_lock() {
  if [ "$DRY_RUN" = 1 ]; then log "dry-run：不占用部署锁"; return 0; fi
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    local holder
    holder=$(cat "$LOCK_OWNER" 2>/dev/null || echo '未知')
    log "另一个部署正在运行（$holder），排队等待（最多 ${LOCK_WAIT}s）…"
    flock -w "$LOCK_WAIT" 9 || die "等待部署锁超时：另一个部署仍未结束（$holder）"
    log "已获得部署锁，继续"
  fi
  printf 'pid=%s args=%s since=%s\n' "$$" "$ARGS" "$(date '+%F %T')" >"$LOCK_OWNER"
  trap 'rm -f "$LOCK_OWNER"' EXIT
}

# 开工前体检：内存/swap/负载/磁盘任一项在红线上就先等，等不到就拒绝。
# 目的是「不在机器已经吃紧的时候再压一个构建上去」。
preflight_resources() {
  local i=0 mem swap load ncpu disk
  while :; do
    mem=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)
    swap=$(awk '/^SwapFree:/{print int($2/1024)}' /proc/meminfo)
    load=$(awk '{print $1}' /proc/loadavg)
    ncpu=$(nproc)
    disk=$(df -Pk / | awk 'NR==2{print int($4/1024)}')
    log "  体检：可用内存 ${mem}MiB / 可用 swap ${swap}MiB / load1 ${load}（${ncpu} 核）/ 磁盘 ${disk}MiB"

    [ "$disk" -ge "$MIN_DISK_MB" ] \
      || die "根分区可用空间仅 ${disk}MiB（< ${MIN_DISK_MB}MiB），构建可能写满磁盘，已中止"

    if awk -v l="$load" -v n="$ncpu" -v f="$MAX_LOAD_FACTOR" 'BEGIN{exit !(l <= n*f)}' \
       && [ "$mem" -ge "$MIN_MEM_MB" ] && [ "$swap" -ge "$MIN_SWAP_MB" ]; then
      return 0
    fi

    [ "$i" -lt "$PREFLIGHT_WAIT" ] \
      || die "资源不达标且已等待 ${i}s（内存 ${mem}MiB / swap ${swap}MiB / load1 ${load}）；先查清谁在吃资源：pm2 list、ps aux --sort=-%mem"
    sleep 5; i=$((i + 5))
  done
}

# 所有构建/依赖安装的唯一入口：先体检，再在 cgroup 限额里执行。
run_guarded() {  # $1=命令 $2=日志文件（可选）
  local cmd=$1 logfile=${2:-}
  preflight_resources
  if [ -n "$logfile" ]; then
    bash "$OPS_DIR/build-guard.sh" "$cmd" >"$logfile" 2>&1
  else
    bash "$OPS_DIR/build-guard.sh" "$cmd"
  fi
}

run_build() {   # $1=仓名 $2=构建命令 $3=构建失败时的恢复命令（可选）
  local repo=$1 cmd=$2 restore=${3:-}
  local logfile="$OPS_LOG_DIR/sync-deploy-$repo.log"
  mkdir -p "$OPS_LOG_DIR"
  log "  构建：$cmd （输出 → $logfile；限额与峰值见日志里的 [guard] 行）"
  if [ "$DRY_RUN" = 1 ]; then log "  [dry-run] 跳过实际执行"; return 0; fi
  if ! run_guarded "$cmd" "$logfile"; then
    tail -25 "$logfile" >&2
    # 恢复命令：用于「构建前必须先停服务」的场景（见 notelab-java 分支），
    # 否则构建一失败服务就永远停在 stopped，比构建失败本身更致命。
    if [ -n "$restore" ]; then
      warn "构建失败，执行恢复动作：$restore"
      eval "$restore" >/dev/null 2>&1 || warn "恢复动作也失败了，需人工介入：pm2 restart $repo"
    fi
    die "$repo 构建失败（完整日志：$logfile）"
  fi
  log "  构建完成"
}

npm_install_if_needed() {  # $1=变更文件清单
  # ⚠️ 2026-10-11（阶段 2）：notelab-c 已是 npm workspace（workspaces: packages/* + apps/*）。
  #    子包的 package.json 变更（apps/play、packages/shared）同样需要重跑 `npm install`
  #    —— 它不只是装依赖，还负责**重建 node_modules/@notelab/* 软链**。
  #    漏掉这一步的表现是：改了 workspace 包的 exports 后，新子路径 import 全报
  #    "Module not found"，而 package.json 明明是对的（软链指向的元数据没刷新）。
  #    这里从"全行精确匹配"放宽为"根 or 任意一层子路径"，覆盖 workspaces 下的所有子包。
  if printf '%s\n' "$1" | grep -qE '^(.*/)?package(-lock)?\.json$|^pnpm-lock\.yaml$' \
     && ! printf '%s\n' "$1" | grep -qE '^archguard/package(-lock)?\.json$'; then
    log "  package.json / lock 有变更 → 先 npm install"
    if [ "$DRY_RUN" != 1 ]; then
      local nlog="$OPS_LOG_DIR/sync-deploy-npm-install.log"
      if ! run_guarded "npm install --no-audit --no-fund" "$nlog"; then
        tail -20 "$nlog" >&2
        die "npm install 失败（日志：$nlog）"
      fi
      restore_lockfile_noise
    fi
  fi
  # 2026-10-01：ArchGuard 在 notelab-c 里是**子包**（notelab-c/archguard），有自己的 package.json。
  # ⚠️ 它**不属于** npm workspace（不在 workspaces 声明里，自己带 node_modules），
  #    所以必须从上面的放宽规则里排除，否则会在仓根误跑一次、真正的子包 install 反而没跑。
  if printf '%s\n' "$1" | grep -qE '^archguard/package(-lock)?\.json$'; then
    log "  archguard 子包依赖有变更 → 在 notelab-c/archguard 内 npm install"
    if [ "$DRY_RUN" != 1 ]; then
      local alog="$OPS_LOG_DIR/sync-deploy-npm-install-archguard.log"
      # 子 shell 里的 cd 不影响 run_guarded：这里直接给出带路径的命令
      if ! run_guarded "cd /root/Notelab/notelab-c/archguard && npm install --no-audit --no-fund" "$alog"; then
        tail -20 "$alog" >&2
        die "archguard npm install 失败（日志：$alog）"
      fi
      restore_lockfile_noise
    fi
  fi
}

# npm 版本差异会顺手重写 lock（典型：删掉可选依赖的 libc 字段），把服务器工作区改脏，
# 下一次部署直接被「工作区脏」守护拦下 —— 这正是有人绕过脚本手搓构建的起点。
# 部署只负责按 lock 装依赖，不该改仓库：装完发现 lock 被动过就还原。
restore_lockfile_noise() {
  local dirty
  dirty=$(git status --porcelain -- '*package-lock.json' 2>/dev/null | awk '{print $2}' || true)
  [ -n "$dirty" ] || return 0
  warn "npm install 改写了 lockfile（通常是 npm 版本差异），已还原以免工作区变脏："
  printf '%s\n' "$dirty" | sed 's/^/    /' >&2
  local -a paths=()
  while IFS= read -r p; do [ -n "$p" ] && paths+=("$p"); done <<< "$dirty"
  git checkout -- "${paths[@]}" || warn "还原 lockfile 失败，需手工执行 git checkout -- ${dirty}"
}

# 2026-10-01：架构门禁。ArchGuard 扫 java/b/c 三仓源码建依赖图，error>0 即中止部署。
#   · index.js 退出码 1 == 有 error 违规（这是门禁的**正常结果**不是故障），
#     所以只把 rc=1 判成「架构退步」；报告写 /root/Notelab/notelab-c/archguard/arch-report.json。
#   · 只在碰了源码时才扫（archguard 自带 node_modules，一次约 2s）。
#   · 紧急绕过：SKIP_ARCH=1 ./sync-deploy.sh notelab-c
archguard_gate() {
  [ "${SKIP_ARCH:-0}" = 1 ] && { log "  SKIP_ARCH=1 → 跳过 ArchGuard 门禁"; return 0; }
  # 变更落在 java/b/c 任一仓的源码下，或本仓 archguard/ 下（改了扫描器本身就该重扫）。
  # ⚠️ 2026-10-11：notelab-c 阶段 0 重排后，源码除 src/ 外还落在 apps/ 与 packages/，
  #    只匹配 src/ 会让「改了 play 域却跳过架构扫描」——正是最该扫的变更被漏掉。
  printf '%s\n' "$1" | grep -qE '^(notelab-(java|b|c)/)?(src/|apps/|packages/|archguard/)' || return 0
  if [ "$DRY_RUN" = 1 ]; then log "  [dry-run] 跳过 ArchGuard 扫描"; return 0; fi
  log "  ArchGuard 架构门禁扫描…"
  # 🔴 2026-10-11 修：原写法 `( ... )` 后紧跟 `local rc=$?`，在 `set -e` 下**子 shell 的失败会
  #    让函数立即返回非零**，`local rc=$?` 根本执行不到 —— 后果是：
  #    · die 从未被调用，用户看不到任何失败原因（输出停在「扫描…」就没了）
  #    · 退出码虽仍是 1（部署确实被拦），但要靠 `bash -x` 才查得出原因
  #    这里局部 `set +e` 再 `set -e` 恢复，直白无歧义。
  #    （另一种写法是放进 `if` 条件里让 set -e 豁免，但 `if ! (...)` 的 `$?` 是取反后的值，易错。）
  local rc=0
  set +e
  ( cd /root/Notelab/notelab-c/archguard && node src/index.js >/dev/null 2>&1 )
  rc=$?
  set -e
  if [ "$rc" -eq 1 ]; then
    die "ArchGuard 检出 error 级架构违规，已中止部署；详情见 /root/Notelab/notelab-c/archguard/arch-report.json"
  fi
  [ "$rc" -ne 0 ] && die "ArchGuard 扫描异常（rc=$rc，扫描器本身坏了，不是架构问题）"
  log "  ArchGuard 通过（无 error 级违规）"
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
      archguard_gate "$changed"
      run_build "$repo" "npm run build"
      pm2_restart notelab-c "http://127.0.0.1:3010/games/"
      # ⚠️ 2026-10-11（阶段 2）：/play/* 已是**独立 Next 应用**（apps/play，pm2 notelab-play，:3011）。
      #    根目录的 `npm run build` **完全构建不到它** —— 与 notelab-b 的 collab/ 是同一个坑：
      #    只改 apps/play/ 而不在这里单独构建+重启，代码更新会静默不生效（页面照旧，没有任何报错）。
      #
      # 触发条件：变更落在 apps/play/ 或 packages/shared/ 下。
      #   · apps/play/ 是它自己的源码；
      #   · packages/shared/ 是**两个应用共享**的 workspace 包（改它必须两个都重建，
      #     否则会出现「core 已更新、play 还是旧逻辑」的半新半旧状态）。
      #   · 根 tsconfig / package.json 变更同样包含在内（影响 node_modules 软链与路径映射）。
      if printf '%s\n' "$changed" | grep -qE '^(apps/play/|packages/shared/|package(-lock)?\.json$|tsconfig\.json$)'; then
        log "  apps/play 或 packages/shared 有变更 → 构建并重启独立应用 notelab-play"
        run_build "notelab-play" "cd /root/Notelab/notelab-c/apps/play && npm run build"
        pm2_restart notelab-play "http://127.0.0.1:3011/play/spire" 40
      fi ;;
    notelab-b)
      npm_install_if_needed "$changed"
      run_build "$repo" "npm run build"
      pm2_restart notelab-b "http://127.0.0.1:3020/admin/"
      # ⚠️ 协作画布的同步服务是**独立进程**（pm2 notelab-collab，:3030），
      #    `npm run build` 完全覆盖不到它 —— 只改 collab/ 而不重启它，
      #    代码更新会静默不生效（表现为"改了没反应"，很难排查）。
      if printf '%s\n' "$changed" | grep -q '^collab/'; then
        log "  collab/ 有变更 → 重启协作服务 notelab-collab"
        pm2_restart notelab-collab "http://127.0.0.1:3030/health" 20
      fi ;;
    notelab-java)
      # ① 鉴权覆盖率闸门（2026-10-21 加）：拦「新增接口忘了判会话」。
      #    ApiPermInterceptor 对**匿名**与**C 端会话**访问非 /api/c/** 的路径一律放行
      #    （CPermGuard 对非 C 前缀直接 return null），正确性完全靠每个 Controller 自己调
      #    AuthUtil.user / CAuthUtil.user 判空。漏一个，那个接口就直接对全网开放，
      #    而且运行时没有任何报错 —— 这类洞只能在构建期兜。
      #    必须放在 pm2 stop **之前**：检查不过就别把服务停了。
      if [ "$DRY_RUN" = 1 ]; then
        log "  [dry-run] 跳过鉴权覆盖率检查"
      else
        log "  鉴权覆盖率检查…"
        if ! /usr/bin/node "$REPO_ROOT/notelab-java/ops/check-auth-coverage.mjs"; then
          die "$repo 鉴权覆盖率检查未通过（见上方清单），已中止部署；服务未停"
        fi
      fi
      # ⚠️ 必须**先停进程再构建**（2026-10-10 血的教训）：
      #   Spring Boot fat jar 里的类由 LaunchedURLClassLoader **惰性加载**，mvn package
      #   重写 target/notelab-java.jar 的瞬间，仍在运行的旧进程就开始
      #   NoClassDefFoundError（error.log 里刷 `ClassNotFoundException: ch/qos/logback/
      #   classic/spi/ThrowableProxy`，栈带 SpringApplicationShutdownHook）。
      #   从 jar 被覆盖到 pm2 restart 完成这几十秒里 :8001 是死的，后果不只是 Java 自身：
      #   nginx 的 `location ^~ /ailab/` 用 auth_request 调 /api/auth/verify → 子请求 502
      #   → nginx 给客户端 **500**（`/ailab/` 直接白屏），`/api/c/**` 也全挂。
      #   所以：先 stop（把不可用窗口挪到"没人指望它活着"的阶段），再构建，最后 restart。
      if [ "$DRY_RUN" = 1 ]; then
        log "  [dry-run] 跳过 pm2 stop notelab-java"
      else
        pm2 stop notelab-java >/dev/null 2>&1 || true
        log "  已停 notelab-java（避免 mvn 覆盖运行中的 jar）"
      fi
      # 第三个参数 = 构建失败时把服务重新拉起，避免编译失败就把 Java 永久留在 stopped。
      run_build "$repo" "/usr/bin/mvn -B -DskipTests -q package" \
        "pm2 restart notelab-java --update-env"
      # Spring Boot 冷启动 ~15-20s，探活超时给足。
      # 上面已 stop，`pm2 restart` 对 stopped 进程同样能拉起（restart ≈ start）。
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
  local repo=$1 dir="$REPO_ROOT/$1"
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

# 整段部署（含 all 的多个仓）独占执行，防止并发构建把机器压死
acquire_deploy_lock
for r in "${TARGETS[@]}"; do sync_one "$r"; done
log "全部完成：${TARGETS[*]}"
