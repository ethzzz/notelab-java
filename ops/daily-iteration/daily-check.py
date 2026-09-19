#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""NoteLab 每日巡检采集器（纯只读，不做任何修改）。

- 每日快检：服务健康、资源水位、错误日志、git 漂移/落后、备份新鲜度
- 每周日深检（或 --deep 强制）：依赖更新（npm/pip）、npm audit 安全漏洞
- 输出：/root/ops/reports/YYYY-MM-DD.md，同时打印到 stdout（供 cron.log）
- 由 Qoder 读取报告做 AI 研判：低风险项直接改+验证，高风险项列清单等人工拍板
"""
import json, subprocess, os, sys, glob, datetime, socket

OPS = "/root/ops"
RDIR = os.path.join(OPS, "reports")
PM2LOG = "/root/.pm2/logs"
BACKUP_DIR = "/root/backups"
KEEP_DAYS = 30
LOGSTATE = os.path.join(OPS, ".logstate.json")   # 错误日志基线（用于算新增量，消除告警疲劳）

# 服务定义：name, 端口, 探活路径, 可接受状态码集合
SERVICES = [
    ("notelab-java", 8001, "/api/menu",    {"200", "401", "403"}),
    ("notelab-c",    3010, "/games/",      {"200", "308", "301"}),
    ("notelab-b",    3020, "/admin/",      {"200", "308", "301"}),
    ("ai-lab",       8002, "/api/health",  {"200"}),
]
# git 仓：目录名 -> GitHub 仓（用于 fetch 落后检查）
REPOS = ["notelab-java", "notelab-c", "notelab-b", "ai-lab"]
# 前端仓（有 package-lock，可 npm audit / outdated）
NPM_REPOS = ["/root/notelab-c", "/root/notelab-b"]
# ai-lab venv pip
PIP = "/root/ai-lab/.venv/bin/pip"

today = datetime.date.today()
TODAY = today.isoformat()
DOW = today.isoweekday()          # 1=周一 .. 7=周日
DOW_CN = "一二三四五六日"[DOW - 1]
DEEP = ("--deep" in sys.argv) or (DOW == 7)
NOW = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

warnings = []   # (level, msg)，level in {"red","yellow"}
body = []       # 报告明细行


def warn(level, msg):
    warnings.append((level, msg))


def out(s=""):
    body.append(s)


def sh(cmd, timeout=30):
    """执行 shell，返回 (rc, stdout, stderr)；超时/异常不抛出，返回 rc=-1。

    安全说明：所有 cmd 均由本脚本内的常量（SERVICES/REPOS/固定路径）拼接，
    不接受任何外部或用户输入，无命令注入面；依赖管道与 awk/grep 等 shell 特性，故用 shell=True。
    """
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout.strip(), r.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "TIMEOUT"
    except Exception as e:
        return -1, "", str(e)


# ================= 1. 服务健康 =================
def check_services(pmmap):
    out("## 1. 服务健康（pm2 + HTTP 探活）")
    out("| 服务 | pm2 | 重启 | 内存 | 探活 | 判定 |")
    out("|---|---|---|---|---|---|")
    for name, port, path, ok_codes in SERVICES:
        p = pmmap.get(name)
        if p:
            st = p["pm2_env"]["status"]
            rst = p["pm2_env"].get("restart_time", "?")
            mem = f"{round(p['monit']['memory']/1048576)}MB"
        else:
            st, rst, mem = "missing", "?", "?"
        _, code, _ = sh(f"curl -s -o /dev/null -w '%{{http_code}}' --max-time 5 http://127.0.0.1:{port}{path}", 8)
        code = code or "000"
        healthy = (st == "online") and (code in ok_codes)
        out(f"| {name} | {st} | {rst} | {mem} | {code} | {'🟢' if healthy else '🔴'} |")
        if st != "online":
            warn("red", f"{name} pm2 状态={st}（应为 online）")
        if code not in ok_codes:
            warn("red", f"{name} 探活异常：HTTP {code}（期望 {'/'.join(sorted(ok_codes))}）")
        if isinstance(rst, int) and rst > 60:
            warn("yellow", f"{name} 累计重启 {rst} 次，偏高，查是否有崩溃循环")
    out()


# ================= 2. 资源水位 =================
def check_resources():
    out("## 2. 资源水位")
    _, disk, _ = sh("df -P / | awk 'NR==2{print $5}' | tr -d '%'")
    _, inode, _ = sh("df -Pi / | awk 'NR==2{print $5}' | tr -d '%'")
    _, memline, _ = sh("free -m | awk '/^Mem:/{print $2\" \"$7}'")
    _, swapused, _ = sh("free -m | awk '/^Swap:/{print $3}'")
    _, load, _ = sh("cat /proc/loadavg | cut -d' ' -f1")
    _, nproc, _ = sh("nproc")
    _, upt, _ = sh("awk '{printf \"%.1f\", $1/86400}' /proc/uptime")
    try:
        dp = int(disk)
    except Exception:
        dp = -1
    try:
        total, avail = [int(x) for x in memline.split()]
        mem_pct = round((total - avail) * 100 / total)
    except Exception:
        mem_pct = -1
    try:
        load1 = float(load); cores = int(nproc)
    except Exception:
        load1, cores = 0.0, 1
    out(f"- 磁盘使用率：**{disk}%**（inode {inode}%）")
    out(f"- 内存使用率：**{mem_pct}%**　Swap 已用：{swapused}MB")
    out(f"- 负载(1m)：**{load}** / {nproc} 核　运行时长：{upt} 天")
    if dp >= 90: warn("red", f"磁盘使用率 {disk}%，即将写满")
    elif dp >= 80: warn("yellow", f"磁盘使用率 {disk}%，需关注")
    if mem_pct >= 90: warn("red", f"内存使用率 {mem_pct}%")
    elif mem_pct >= 80: warn("yellow", f"内存使用率 {mem_pct}%")
    if load1 > cores * 2: warn("red", f"负载 {load1} 远超 {cores} 核")
    elif load1 > cores: warn("yellow", f"负载 {load1} 超过核数 {cores}")
    try:
        if int(swapused) > 512: warn("yellow", f"Swap 已用 {swapused}MB，物理内存可能吃紧")
    except Exception:
        pass
    out()


# ================= 3. 错误日志扫描（按新增量，消除告警疲劳） =================
def _load_logstate():
    try:
        with open(LOGSTATE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _save_logstate(st):
    try:
        with open(LOGSTATE, "w", encoding="utf-8") as f:
            json.dump(st, f)
    except Exception:
        pass


def check_logs():
    out("## 3. 错误日志扫描（较上次新增）")
    # (标签, 路径, grep 模式, grep 参数)
    targets = [(f"{name}-error.log", os.path.join(PM2LOG, f"{name}-error.log"),
                "error|exception|traceback|fatal|critical|refused|timeout", "-iE")
               for name, *_ in SERVICES]
    targets.append(("nginx/error.log", "/var/log/nginx/error.log",
                    r"\[(error|crit|alert|emerg)\]", "-E"))
    state = _load_logstate()
    first_run = not state
    newstate = {}
    total_new = 0
    for label, f, pat, gp in targets:
        if not os.path.exists(f):
            continue
        _, n, _ = sh(f"grep -c {gp} '{pat}' '{f}'")
        try:
            n = int(n)
        except Exception:
            n = 0
        _, size, _ = sh(f"du -h '{f}' | cut -f1")
        prev = state.get(label)
        newstate[label] = n
        if first_run or prev is None:
            out(f"- ⚪ `{label}`（{size}）：建立基线，累计 {n} 条（首次不告警）")
            continue
        delta = n - prev
        if delta < 0:      # 日志被轮转/截断，视为全量新增
            delta = n
        flag = "🟡" if delta > 0 else "🟢"
        out(f"- {flag} `{label}`（{size}）：新增 **{delta}** 条（累计 {n}）")
        if delta > 0:
            total_new += delta
            _, sample, _ = sh(f"tail -n 200 '{f}' | grep {gp} '{pat}' | tail -n 3")
            if sample:
                out("  ```")
                for ln in sample.splitlines()[-3:]:
                    out("  " + ln[:160])
                out("  ```")
    _save_logstate(newstate)
    if not first_run and total_new > 20:
        warn("yellow", f"错误日志较上次新增 {total_new} 条，建议排查样本")
    out()


# ================= 4. git 一致性 =================
def check_git():
    out("## 4. Git 一致性（服务器 vs GitHub）")
    out("| 仓 | 工作区脏文件 | HEAD | 落后 origin/main |")
    out("|---|---|---|---|")
    for r in REPOS:
        d = f"/root/{r}"
        if not os.path.isdir(os.path.join(d, ".git")):
            out(f"| {r} | — | 无 git | — |")
            continue
        _, dirty, _ = sh(f"git -C {d} status -s | wc -l")
        _, head, _ = sh(f"git -C {d} log -1 --format='%h %cd' --date=short")
        sh(f"git -C {d} fetch -q origin main", timeout=40)   # 联网，容忍失败
        _, behind, _ = sh(f"git -C {d} rev-list --count HEAD..origin/main 2>/dev/null")
        behind = behind if behind.isdigit() else "?"
        out(f"| {r} | {dirty} | {head} | {behind} |")
        try:
            if int(dirty) > 0:
                warn("yellow", f"{r} 工作区有 {dirty} 个未提交改动（服务器漂移，可能未同步回 GitHub）")
        except Exception:
            pass
        if behind.isdigit() and int(behind) > 0:
            warn("yellow", f"{r} 落后 origin/main {behind} 个提交（服务器未 pull 最新）")
    out()


# ================= 5. 备份新鲜度 =================
def check_backup():
    out("## 5. 数据库备份新鲜度")
    files = glob.glob(os.path.join(BACKUP_DIR, "*.sql")) + glob.glob(os.path.join(BACKUP_DIR, "*.sql.gz"))
    if not files:
        out(f"- 🔴 `{BACKUP_DIR}` 下无任何 .sql 备份")
        warn("red", f"{BACKUP_DIR} 无数据库备份文件")
        out()
        return
    latest = max(files, key=os.path.getmtime)
    age = (datetime.datetime.now() - datetime.datetime.fromtimestamp(os.path.getmtime(latest))).days
    size = os.path.getsize(latest) // 1024
    flag = "🟢" if age <= 7 else ("🟡" if age <= 30 else "🔴")
    out(f"- {flag} 最新备份：`{os.path.basename(latest)}`（{size}KB，**{age} 天前**）")
    out(f"- 备份总数：{len(files)} 个（目录 {BACKUP_DIR}）")
    if age > 30:
        warn("red", f"数据库最近备份已 {age} 天，严重过期，立即 mysqldump")
    elif age > 7:
        warn("yellow", f"数据库最近备份已 {age} 天，超过 7 天窗口，建议尽快备份")
    out()


# ================= 6. 依赖与安全（深检） =================
def check_deep():
    out("## 6. 依赖更新与安全（深检）")
    if not DEEP:
        out("_每周日自动深检；如需立即执行：`python3 /root/ops/daily-check.py --deep`_")
        out()
        return
    # npm outdated + audit
    for d in NPM_REPOS:
        name = os.path.basename(d)
        _, oj, _ = sh(f"cd {d} && npm outdated --json 2>/dev/null", timeout=120)
        cnt = 0
        try:
            cnt = len(json.loads(oj)) if oj else 0
        except Exception:
            cnt = -1
        _, aj, _ = sh(f"cd {d} && npm audit --json --audit-level=high 2>/dev/null", timeout=120)
        high = crit = 0
        try:
            v = json.loads(aj).get("metadata", {}).get("vulnerabilities", {}) if aj else {}
            high, crit = v.get("high", 0), v.get("critical", 0)
        except Exception:
            pass
        flag = "🔴" if crit else ("🟡" if high or cnt > 0 else "🟢")
        out(f"- {flag} **{name}**：可更新依赖 {cnt} 个；npm audit 高危 {high}、严重 {crit}")
        if crit: warn("red", f"{name} npm audit 有 {crit} 个 critical 漏洞")
        elif high: warn("yellow", f"{name} npm audit 有 {high} 个 high 漏洞")
    # pip outdated (ai-lab)
    if os.path.exists(PIP):
        _, pj, _ = sh(f"{PIP} list --outdated --format=json 2>/dev/null", timeout=120)
        try:
            n = len(json.loads(pj)) if pj else 0
        except Exception:
            n = -1
        out(f"- {'🟡' if n>0 else '🟢'} **ai-lab**：可更新 pip 依赖 {n} 个")
    out()


# ================= 主流程 =================
def main():
    os.makedirs(RDIR, exist_ok=True)
    _, pmjson, _ = sh("pm2 jlist 2>/dev/null", timeout=30)
    try:
        pm = json.loads(pmjson)
        pmmap = {p["name"]: p for p in pm}
    except Exception:
        pmmap = {}
        warn("red", "无法解析 pm2 jlist，pm2 可能异常")

    check_services(pmmap)
    check_resources()
    check_logs()
    check_git()
    check_backup()
    check_deep()

    # 组装报告头 + 状态灯 + 告警汇总
    red = sum(1 for l, _ in warnings if l == "red")
    yel = sum(1 for l, _ in warnings if l == "yellow")
    lamp = "🔴" if red else ("🟡" if yel else "🟢")
    head = [
        f"# NoteLab 每日巡检 · {TODAY}",
        f"_生成 {NOW} · 主机 {socket.gethostname()} · 周{DOW_CN} · 深检={'是' if DEEP else '否'}_",
        "",
        f"## 状态：{lamp}　严重 {red} · 注意 {yel}",
        "",
    ]
    if warnings:
        head.append("## ⚠️ 告警汇总（按严重度）")
        for l, m in sorted(warnings, key=lambda x: 0 if x[0] == "red" else 1):
            head.append(f"- {'🔴' if l=='red' else '🟡'} {m}")
    else:
        head.append("## ✅ 全部正常，无需处理")
    head.append("")

    content = "\n".join(head + body) + "\n"
    report_path = os.path.join(RDIR, f"{TODAY}.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(content)

    # 清理超过 KEEP_DAYS 天的旧报告
    cutoff = datetime.datetime.now() - datetime.timedelta(days=KEEP_DAYS)
    for old in glob.glob(os.path.join(RDIR, "*.md")):
        try:
            if datetime.datetime.fromtimestamp(os.path.getmtime(old)) < cutoff:
                os.remove(old)
        except Exception:
            pass

    print(content)
    print(f"[report saved] {report_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
