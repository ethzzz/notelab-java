#!/usr/bin/env bash
# 在限额 cgroup 里执行一条耗时命令（构建 / 依赖安装），并报告峰值内存。
#
# 为什么需要：服务器只有 2 vCPU / 3.9 GB 内存。`next build`、`mvn package` 本身就能吃满
# CPU；一旦再叠加并发部署、或让构建无上限地使用 swap，整机会失去响应直到硬重启
# （2026-10-27 实际发生过一次）。这里给构建套上 cgroup 硬上限：超了只杀构建自己，
# nginx / pm2 / sshd 不受影响。
#
# 用法：build-guard.sh '<命令>'
#
# 可调环境变量（默认值按当前服务器定）：
#   BUILD_CPU_QUOTA=180%  构建可用 CPU（2 核 = 200%，留 20% 给线上服务与 ssh）
#   BUILD_MEM_HIGH=1900M  软上限：超过后内核只在 cgroup 内回收/限流
#   BUILD_MEM_MAX=2700M   硬上限：超过即杀构建（只杀构建）
#   BUILD_SWAP_MAX=1024M  构建最多用多少 swap（不设上限会疯狂换页，是整机卡死的主因）
#   BUILD_TIMEOUT=1800    超时秒数
set -uo pipefail

usage() {
  sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 0
}

case "${1:-}" in ''|-h|--help) usage ;; esac
cmd=$1

if [ "${BUILD_GUARD_INNER:-0}" != 1 ]; then
  command -v systemd-run >/dev/null 2>&1 || {
    echo "[guard] 需要 systemd-run 才能设置 cgroup 限额（systemd 248+）" >&2
    exit 2
  }
  # 外层：把自己放进一个临时 scope 再跑一遍，整棵构建进程树都关进同一个 cgroup。
  # --collect：失败/超时后自动清理 scope，不残留 failed unit。
  exec systemd-run --scope --quiet --collect \
    -p "CPUQuota=${BUILD_CPU_QUOTA:-180%}" \
    -p "MemoryHigh=${BUILD_MEM_HIGH:-1900M}" \
    -p "MemoryMax=${BUILD_MEM_MAX:-2700M}" \
    -p "MemorySwapMax=${BUILD_SWAP_MAX:-1024M}" \
    /usr/bin/env \
      BUILD_GUARD_INNER=1 \
      BUILD_CPU_QUOTA="${BUILD_CPU_QUOTA:-180%}" \
      BUILD_MEM_MAX="${BUILD_MEM_MAX:-2700M}" \
      BUILD_SWAP_MAX="${BUILD_SWAP_MAX:-1024M}" \
      BUILD_TIMEOUT="${BUILD_TIMEOUT:-1800}" \
    /bin/bash "$0" "$cmd"
fi

cg="/sys/fs/cgroup$(cut -d: -f3 /proc/self/cgroup)"
# 内层监督进程必须比构建本身更"耐杀"：否则构建撞上限时内核可能连它一起收走，
# 该打的 [guard] 诊断信息就丢了（只剩一个 255 退出码）。
echo -500 >/proc/self/oom_score_adj 2>/dev/null || true
mem_peak() {
  [ -r "$cg/memory.peak" ] && awk '{printf "%.0f", $1/1048576}' "$cg/memory.peak"
}
oom_kills() {
  [ -r "$cg/memory.events" ] && awk '$1=="oom_kill"{print $2}' "$cg/memory.events"
}

echo "[guard] 限额：CPU <= ${BUILD_CPU_QUOTA} / 内存 <= ${BUILD_MEM_MAX} / swap <= ${BUILD_SWAP_MAX} / 超时 ${BUILD_TIMEOUT}s"

timeout -k 15 "$BUILD_TIMEOUT" /bin/bash -c "$cmd"
rc=$?

peak=$(mem_peak || true)
oom=$(oom_kills || true)
[ -n "${peak:-}" ] && echo "[guard] 峰值内存 ${peak} MiB"

if [ "${oom:-0}" != "0" ]; then
  echo "[guard] 构建触到内存上限被内核杀掉（oom_kill=${oom}）。构建失败，线上服务未受影响。" >&2
  echo "[guard] 确认是构建本身吃太多时可临时放宽：BUILD_MEM_MAX=3200M BUILD_SWAP_MAX=2048M bash $0 '<命令>'" >&2
elif [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
  echo "[guard] 构建超时（${BUILD_TIMEOUT}s）被终止；确认不是卡死后可调大 BUILD_TIMEOUT" >&2
fi

exit "$rc"
