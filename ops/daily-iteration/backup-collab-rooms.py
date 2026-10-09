#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""协作画布 SQLite 备份：rooms.db -> /root/backups/collab-rooms-<TS>.db，保留最近 KEEP 份。

⚠️ 为什么不能直接 `cp`：rooms.db 开了 WAL（collab/lib/db.mjs 里的 journal_mode = WAL）。
   - 只 cp 主库文件 → 丢掉 WAL 里尚未 checkpoint 的写入，拿到**旧快照**；
   - 连 -wal/-shm 一起 cp → 三个文件之间没有一致性保证，**恢复出来可能是坏的**。
   正确做法是用 SQLite 的**在线备份 API**（等价 sqlite3 CLI 的 `.backup`）：由 SQLite 自己在
   页/事务边界上拷贝，与并发写入安全共存，产出单个自洽文件。Python 标准库自带（sqlite3.Connection.backup）。

为什么用 Python 而不是 sqlite3 CLI：服务器**没装 sqlite3 CLI**，而 python3 是这套 cron 的既有依赖
（daily-check.py 就用它），标准库自带 sqlite3 → 零新增依赖。

备份后会跑 `PRAGMA integrity_check` 自检：**备份文件不可读就直接判失败并删掉**，
宁可报错也不要留一个"看起来有、其实坏的"备份 —— 那比没有备份更危险。

权威副本：本文件（notelab-java 仓 ops/daily-iteration/，与 backup-db.sh 同目录），由 cron 直接执行。
"""
import glob
import os
import sqlite3
import sys
import time

BACKUP_DIR = "/root/backups"
KEEP = 14
PREFIX = "collab-rooms-"
# 与 collab/lib/db.mjs 的默认一致：仓目录下 data/rooms.db（可用 COLLAB_DB 覆盖）
DB = os.environ.get("COLLAB_DB") or "/root/Notelab/notelab-b/collab/data/rooms.db"

TS = time.strftime("%Y%m%d-%H%M%S")
OUT = os.path.join(BACKUP_DIR, f"{PREFIX}{TS}.db")


def stamp() -> str:
    return time.strftime("%F %T")


def fail(msg: str) -> int:
    print(f"[{stamp()}] BACKUP FAILED {msg}")
    try:
        if os.path.exists(OUT):
            os.remove(OUT)
    except OSError:
        pass
    return 1


def main() -> int:
    # 库不存在 = 还没人建过任何画布（房间表懒建），不算故障
    if not os.path.exists(DB):
        print(f"[{stamp()}] 跳过：{DB} 不存在（协作服务尚未建过任何房间）")
        return 0

    os.makedirs(BACKUP_DIR, exist_ok=True)

    # 优先只读打开（拿一致性快照不需要写权限）；某些 WAL 只读场景开不了就回落读写。
    src = None
    for uri in (True, False):
        try:
            src = sqlite3.connect(f"file:{DB}?mode=ro", uri=True) if uri else sqlite3.connect(DB)
            break
        except sqlite3.Error as e:
            if not uri:
                return fail(f"打不开 {DB}：{e}")
    if src is None:
        return fail(f"打不开 {DB}")

    try:
        dst = sqlite3.connect(OUT)
        try:
            src.backup(dst)          # 在线备份：与并发写入安全共存
            dst.commit()
        finally:
            dst.close()
    except sqlite3.Error as e:
        return fail(f"backup 失败：{e}")
    finally:
        src.close()

    # 自检：备份必须能打开且通过完整性检查
    try:
        con = sqlite3.connect(OUT)
        try:
            verdict = con.execute("PRAGMA integrity_check").fetchone()[0]
        finally:
            con.close()
    except sqlite3.Error as e:
        return fail(f"备份文件打不开：{e}")
    if verdict != "ok":
        return fail(f"integrity_check = {verdict}")
    if os.path.getsize(OUT) == 0:
        return fail("备份文件为 0 字节")

    # 滚动保留最近 KEEP 份
    olds = sorted(
        glob.glob(os.path.join(BACKUP_DIR, f"{PREFIX}*.db")),
        key=os.path.getmtime,
        reverse=True,
    )
    for f in olds[KEEP:]:
        try:
            os.remove(f)
        except OSError:
            pass

    size_kb = os.path.getsize(OUT) // 1024
    print(f"[{stamp()}] collab rooms.db 备份 ok: {OUT} ({size_kb}KB，完整性 ok，保留 {min(len(olds), KEEP)} 份)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
