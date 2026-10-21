#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""每日巡检告警推送（2026-10-21）。

规则（刻意保守，避免告警疲劳）：
  1. 报告状态 🔴（严重 > 0）→ 立即推送；
  2. 🟡 但「连续 N 天」→ 推送（默认 N=3，可在 notify.conf 改 streak_days）；
  3. 🟢 或 🟡 未达连续天数 → 不推送，只记日志；
  4. 同一天、同状态、同告警集合只推一次（防 cron 重跑重复轰炸）；
  5. 报告文件不存在（巡检脚本挂了）→ 按 🔴 处理。

渠道（三者都配就都发；一个都没配就只写本地日志）：
  - telegram：telegram_bot_token + telegram_chat_id
  - smtp（不需要本机 MTA）：smtp_host/smtp_port/smtp_user/smtp_pass/smtp_from/smtp_to
  - webhook：webhook_url（支持 generic / wecom / dingtalk / slack 四种载荷）

配置放在 /root/ops/notify.conf（chmod 600，**不进 git**），格式见 ops/notify.conf.example。

用法：
  python3 notify.py              # 正常执行（cron 用这个）
  python3 notify.py --dry-run    # 只打印决策，不发送
  python3 notify.py --test       # 给已配置的渠道发一条测试消息
"""
import datetime
import hashlib
import json
import os
import re
import smtplib
import ssl
import sys
import urllib.parse
import urllib.request
from email.header import Header
from email.mime.text import MIMEText

OPS = "/root/ops"
RDIR = os.path.join(OPS, "reports")
STATE = os.path.join(OPS, ".alertstate.json")
ALERT_LOG = os.path.join(OPS, "alerts.log")
CONF = os.path.join(OPS, "notify.conf")

DEFAULT_STREAK_DAYS = 3


# ---------------------------------------------------------------- 配置
def load_conf():
    """key=value，忽略空行与 # 注释；值两边的引号会被去掉。"""
    conf = {}
    if not os.path.exists(CONF):
        return conf
    with open(CONF, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            conf[k.strip()] = v.strip().strip('"').strip("'")
    return conf


def streak_days(conf):
    try:
        return max(1, int(conf.get("streak_days", DEFAULT_STREAK_DAYS)))
    except Exception:
        return DEFAULT_STREAK_DAYS


# ---------------------------------------------------------------- 报告解析
STATUS_RE = re.compile(r"##\s*状态：(\S+).*?严重\s*(\d+)\s*·\s*注意\s*(\d+)")


def parse_report(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = STATUS_RE.search(text)
    if not m:
        return {"lamp": "?", "red": 0, "yellow": 0, "warnings": [], "title": os.path.basename(path)}
    lamp, red, yellow = m.group(1), int(m.group(2)), int(m.group(3))
    warnings = []
    in_block = False
    for line in text.splitlines():
        if line.startswith("## ⚠️ 告警汇总"):
            in_block = True
            continue
        if in_block:
            if line.startswith("## "):
                break
            if line.startswith("- 🔴") or line.startswith("- 🟡"):
                warnings.append(line[2:].strip())
    first = text.splitlines()[0].strip("# ").strip() if text else ""
    return {"lamp": lamp, "red": red, "yellow": yellow, "warnings": warnings, "title": first}


# ---------------------------------------------------------------- 状态
def load_state():
    try:
        with open(STATE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(st):
    try:
        with open(STATE, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def decide(report, st, n_days):
    """返回 (是否发送, 原因, 新的 streak)。"""
    today = datetime.date.today().isoformat()
    yellow = report["red"] == 0 and (report["yellow"] > 0 or report["lamp"] == "🟡")
    streak = (st.get("yellow_streak", 0) + 1) if yellow else 0

    if report["red"] > 0:
        return True, f"严重告警 {report['red']} 条", streak
    if report["lamp"] == "?":
        return True, "巡检报告缺失或格式异常", streak
    if yellow and streak >= n_days:
        return True, f"连续 {streak} 天有注意项（≥{n_days}）", streak
    return False, f"状态 {report['lamp']}（注意 {report['yellow']}，连续 {streak} 天）未达推送阈值", streak


def digest_of(report):
    raw = "|".join(report["warnings"]) or f"{report['lamp']}/{report['red']}/{report['yellow']}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:10]


# ---------------------------------------------------------------- 渠道
def build_text(report, reason):
    lines = [
        f"【NoteLab 巡检告警】{datetime.date.today().isoformat()}",
        f"状态：{report['lamp']}　严重 {report['red']} · 注意 {report['yellow']}",
        f"触发：{reason}",
    ]
    if report["warnings"]:
        lines.append("")
        lines.append("告警明细：")
        lines += [f"· {w}" for w in report["warnings"][:10]]
    lines += ["", f"报告：{RDIR}/{datetime.date.today().isoformat()}.md"]
    return "\n".join(lines)


def send_telegram(conf, text):
    token, chat = conf.get("telegram_bot_token"), conf.get("telegram_chat_id")
    if not token or not chat:
        return False
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    data = urllib.parse.urlencode({"chat_id": chat, "text": text}).encode()
    with urllib.request.urlopen(url, data=data, timeout=15) as r:
        return r.status == 200


def send_smtp(conf, subject, text):
    host = conf.get("smtp_host")
    if not host or not conf.get("smtp_to"):
        return False
    port = int(conf.get("smtp_port", 465))
    user, pwd = conf.get("smtp_user", ""), conf.get("smtp_pass", "")
    sender = conf.get("smtp_from") or user
    msg = MIMEText(text, "plain", "utf-8")
    msg["Subject"] = Header(subject, "utf-8")
    msg["From"] = sender
    msg["To"] = conf["smtp_to"]

    if str(conf.get("smtp_ssl", "true")).lower() != "false":
        with smtplib.SMTP_SSL(host, port, context=ssl.create_default_context(), timeout=20) as s:
            if user:
                s.login(user, pwd)
            s.sendmail(sender, [x.strip() for x in conf["smtp_to"].split(",") if x.strip()], msg.as_string())
    else:
        with smtplib.SMTP(host, port, timeout=20) as s:
            s.starttls(context=ssl.create_default_context())
            if user:
                s.login(user, pwd)
            s.sendmail(sender, [x.strip() for x in conf["smtp_to"].split(",") if x.strip()], msg.as_string())
    return True


def send_webhook(conf, text):
    url = conf.get("webhook_url")
    if not url:
        return False
    kind = conf.get("webhook_type", "generic").lower()
    if kind == "wecom":
        payload = {"msgtype": "text", "text": {"content": text}}
    elif kind == "dingtalk":
        payload = {"msgtype": "text", "text": {"content": text}}
    elif kind == "slack":
        payload = {"text": text}
    else:
        payload = {"title": "NoteLab 巡检告警", "text": text}
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=15) as r:
        return 200 <= r.status < 300


def deliver(conf, subject, text):
    sent = []
    for name, fn in (
        ("telegram", lambda: send_telegram(conf, text)),
        ("smtp", lambda: send_smtp(conf, subject, text)),
        ("webhook", lambda: send_webhook(conf, text)),
    ):
        try:
            if fn():
                sent.append(name)
        except Exception as e:
            print(f"[notify] {name} 发送失败：{e}", file=sys.stderr)
    return sent


def append_alert_log(status, reason, sent, detail=""):
    """审计行：一行一条，含状态、触发原因、渠道、告警明细（明细压成单行）。"""
    flat = " / ".join(x.replace("\n", " ") for x in detail.splitlines() if x.strip())[:400] if detail else ""
    line = (
        f"{datetime.datetime.now().isoformat(timespec='seconds')}\t{status}\t{reason}\t"
        f"channels={','.join(sent) or 'none'}\t{flat}\n"
    )
    try:
        with open(ALERT_LOG, "a", encoding="utf-8") as f:
            f.write(line)
    except Exception:
        pass


# ---------------------------------------------------------------- 主流程
def main():
    dry = "--dry-run" in sys.argv
    test = "--test" in sys.argv

    conf = load_conf()
    if test:
        text = "【NoteLab 巡检】这是一条测试消息，收到说明通知渠道已配置正确。"
        sent = [] if dry else deliver(conf, "NoteLab 巡检测试", text)
        print(f"测试结果：channels={sent or '未配置任何渠道'}")
        return 0

    report_path = os.path.join(RDIR, f"{datetime.date.today().isoformat()}.md")
    if os.path.exists(report_path):
        report = parse_report(report_path)
    else:
        report = {"lamp": "?", "red": 0, "yellow": 0, "warnings": [], "title": "报告缺失"}

    st = load_state()
    send, reason, streak = decide(report, st, streak_days(conf))
    dg = digest_of(report)
    today = datetime.date.today().isoformat()
    already = f"{today}:{report['lamp']}:{dg}" in st.get("sent_keys", [])
    if already:
        send, reason = False, f"今天已推送过同样内容（{reason}）"

    print(f"[notify] 状态={report['lamp']} 严重={report['red']} 注意={report['yellow']} 连续={streak} → "
          f"{'推送' if send else '跳过'}：{reason}")

    if not send:
        if not dry:
            st.update({"date": today, "status": report["lamp"], "yellow_streak": streak})
            save_state(st)
        return 0

    text = build_text(report, reason)
    if dry:
        print("\n" + text)
        return 0

    sent = deliver(conf, f"[NoteLab] {report['lamp']} 巡检告警 {today}", text)
    keys = (st.get("sent_keys", []) + [f"{today}:{report['lamp']}:{dg}"])[-20:]
    st.update({"date": today, "status": report["lamp"], "yellow_streak": streak, "sent_keys": keys})
    save_state(st)
    append_alert_log(report["lamp"], reason, sent, text)
    print(f"[notify] 已发送：{sent or '无渠道配置（仅记录 alerts.log）'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
