#!/usr/bin/env bash
# NoteLab 日志查询（单机版，不依赖 ELK/Loki）
#
# 为什么是脚本而不是日志平台：这台机器 2 核 3.9G，跑 ELK 会挤掉业务；
# 而排查真正需要的能力只有三件——按 rid 串链路、按状态码/耗时筛、按时间窗看错误。
#
# 日志位置：
#   - 应用：/root/.pm2/logs/<服务>-{out,error}.log（由 pm2-logrotate 轮转，10M × 30 份）
#   - 访问：/var/log/nginx/notelab.json.log（JSON，含 rid/status/rt/ut）
#
# 用法：
#   logs.sh req <rid>             按链路 ID 查（nginx + 所有应用日志）
#   logs.sh err [--since 30m]     最近的 ERROR（自动带上 rid）
#   logs.sh slow [--ms 1000]      慢请求（默认 >1000ms，来自 nginx JSON）
#   logs.sh 5xx                   服务端错误（nginx status>=500 + 应用 ERROR）
#   logs.sh stats [--since 1h]    状态码分布 / TOP 路径 / 平均耗时
#   logs.sh tail <服务> [行数]     实时跟日志（服务名如 notelab-java）
#   logs.sh list                  列出所有日志文件
#
# 依赖：bash、grep、awk、date（GNU 或 BSD）。不需要 jq。
set -euo pipefail

PM2_LOGS="${PM2_LOGS:-/root/.pm2/logs}"
NGINX_JSON="${NGINX_JSON:-/var/log/nginx/notelab.json.log}"

die() { echo "错误：$*" >&2; exit 1; }

# 把 "30m" / "2h" / "7d" 换算成「本地时间字符串」，供 awk 做前缀比较。
# 注意：nginx 的 ts 与 Java 的日志都带本地时区偏移，取前 19 位比较即可。
since_cutoff() {
  local spec="${1:-1h}" n u
  n="${spec%[!0-9]*}"          # 前导数字：30m -> 30
  u="${spec#"$n"}"              # 单位：m/h/d/s
  [ -n "$n" ] || die "时间窗格式应为 30m / 2h / 1d（收到：$spec）"
  case "$u" in
    m|min) u="minutes" ;;
    h) u="hours" ;;
    d) u="days" ;;
    s) u="seconds" ;;
    *) die "时间窗单位只支持 m / h / d / s（收到：$spec）" ;;
  esac
  date -d "-${n} ${u}" +%Y-%m-%dT%H:%M:%S 2>/dev/null \
    || date -v-"${n}${u:0:1}" +%Y-%m-%dT%H:%M:%S 2>/dev/null \
    || die "无法解析时间窗：$spec"
}

cmd_req() {
  local rid="${1:-}"
  [ -n "$rid" ] || die "用法：logs.sh req <rid>"
  # rid 同时出现在 nginx JSON（"rid":"..."）与 Java 日志（[rid]）里
  grep -h -- "$rid" "$NGINX_JSON" "$PM2_LOGS"/*.log 2>/dev/null \
    | sort \
    || echo "没有找到 rid=$rid 的记录"
}

cmd_err() {
  local since="1h" lines=200
  while [ $# -gt 0 ]; do
    case "$1" in
      --since) since="${2:?}"; shift 2 ;;
      --lines) lines="${2:?}"; shift 2 ;;
      *) die "未知参数：$1" ;;
    esac
  done
  local cutoff; cutoff="$(since_cutoff "$since")"
  echo "== $since 内的 ERROR（cutoff=$cutoff）=="
  grep -h ' ERROR ' "$PM2_LOGS"/*.log 2>/dev/null \
    | awk -v c="$cutoff" 'substr($0,1,19) >= c' \
    | tail -n "$lines" \
    || echo "（无）"
}

cmd_slow() {
  local ms=1000 since="1h"
  while [ $# -gt 0 ]; do
    case "$1" in
      --ms) ms="${2:?}"; shift 2 ;;
      --since) since="${2:?}"; shift 2 ;;
      *) die "未知参数：$1" ;;
    esac
  done
  [ -f "$NGINX_JSON" ] || die "找不到 $NGINX_JSON"
  local cutoff; cutoff="$(since_cutoff "$since")"
  echo "== $since 内 rt >= ${ms}ms 的请求 =="
  gawk -v c="$cutoff" -v ms="$ms" '
    match($0, /"ts":"([^"]+)"/, a) {
      ts = a[1]
      if (substr(ts, 1, 19) < c) next
      match($0, /"rt":([0-9.]+)/, r)
      if ((r[1] * 1000) >= ms) print
    }
  ' "$NGINX_JSON" || echo "（无）"
}

cmd_5xx() {
  local since="2h"
  while [ $# -gt 0 ]; do
    case "$1" in
      --since) since="${2:?}"; shift 2 ;;
      *) die "未知参数：$1" ;;
    esac
  done
  [ -f "$NGINX_JSON" ] || die "找不到 $NGINX_JSON"
  local cutoff; cutoff="$(since_cutoff "$since")"
  echo "== nginx 侧 5xx（$since 内，cutoff=$cutoff）=="
  gawk -v c="$cutoff" '
    match($0, /"ts":"([^"]+)"/, a) {
      if (substr(a[1], 1, 19) < c) next
      match($0, /"status":([0-9]+)/, s)
      if (s[1] + 0 >= 500) print
    }
  ' "$NGINX_JSON" || echo "（无）"
  echo
  echo "== 应用侧 ERROR（$since 内，最近 50 条）=="
  grep -h ' ERROR ' "$PM2_LOGS"/*.log 2>/dev/null \
    | awk -v c="$cutoff" 'substr($0,1,19) >= c' \
    | tail -n 50 || echo "（无）"
}

cmd_stats() {
  local since="1h"
  while [ $# -gt 0 ]; do
    case "$1" in
      --since) since="${2:?}"; shift 2 ;;
      *) die "未知参数：$1" ;;
    esac
  done
  [ -f "$NGINX_JSON" ] || die "找不到 $NGINX_JSON"
  local cutoff; cutoff="$(since_cutoff "$since")"
  echo "== $since 内请求统计（cutoff=$cutoff）=="
  gawk -v c="$cutoff" '
    match($0, /"ts":"([^"]+)"/, a) {
      if (substr(a[1], 1, 19) < c) next
      match($0, /"status":([0-9]+)/, s)
      match($0, /"uri":"([^"]*)"/, u)
      match($0, /"rt":([0-9.]+)/, r)
      total++
      status[s[1]]++
      uri[u[1]]++
      sum += r[1]
      if (r[1] + 0 > max) { max = r[1]; maxuri = u[1] }
    }
    END {
      printf "总请求：%d  平均耗时：%.0fms  最慢：%.0fms (%s)\n\n", total, (total ? sum / total * 1000 : 0), max * 1000, maxuri
      print "按状态码："
      for (k in status) printf "  %s  %d\n", k, status[k]
      print "\nTOP 10 路径："
      n = 0
      for (k in uri) { print uri[k], k }
    }
  ' "$NGINX_JSON" | head -n 40
}

cmd_tail() {
  local svc="${1:-}"; local n="${2:-100}"
  [ -n "$svc" ] || die "用法：logs.sh tail <服务名> [行数]（logs.sh list 可看服务名）"
  pm2 logs "$svc" --lines "$n" --nostream
}

cmd_list() {
  ls -lah "$PM2_LOGS"/*.log 2>/dev/null || true
  ls -lah "$NGINX_JSON" 2>/dev/null || true
}

case "${1:-help}" in
  req) shift; cmd_req "$@" ;;
  err) shift; cmd_err "$@" ;;
  slow) shift; cmd_slow "$@" ;;
  5xx) shift; cmd_5xx "$@" ;;
  stats) shift; cmd_stats "$@" ;;
  tail) shift; cmd_tail "$@" ;;
  list) cmd_list ;;
  help|--help|-h)
    sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *) die "未知命令：$1（logs.sh help）" ;;
esac
