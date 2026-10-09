# NoteLab 每日迭代机制（AI 巡检 + 半自动）

两层架构：**底层服务器 cron 自动采集**（不依赖 Qoder，天天跑）+ **上层 Qoder 每日研判**（半自动处理）。
本目录是这套机制的**脚本权威副本**（随 notelab-java 仓版本管理）；cron 直接执行本目录脚本，避免副本漂移。

## 文件清单
| 文件 | 作用 |
|---|---|
| `daily-check.py` | 纯只读巡检器。查服务健康/资源/错误日志(增量)/git漂移/备份新鲜度/依赖安全，输出 markdown 报告 |
| `backup-db.sh` | 每日 mysqldump notelab 库 → gzip → `/root/backups/`，保留最近 7 份滚动 |
| `backup-collab-rooms.py` | 每日备份协作画布的 SQLite `rooms.db` → `/root/backups/collab-rooms-*.db`，保留最近 14 份 |
| `setup-mybackup.sh` | 一次性：从 `.env` 解析 MySQL 凭据 → 生成 `/root/.my.cnf`(600) + `/root/ops/.backup-db-name`。密码变更后重跑 |
| `install-cron.sh` | 幂等安装/刷新下面三条 cron（指向本目录脚本） |

### 为什么 rooms.db 要单独一份脚本
协作画布**内容**存在协作服务的本地 SQLite（`/root/Notelab/notelab-b/collab/data/rooms.db`），
元数据才在 MySQL —— 所以 `mysqldump` **完全覆盖不到画布内容**。
⚠️ 且**不能直接 `cp`**：该库是 WAL 模式，只 cp 主文件会丢未 checkpoint 的写入、三文件一起 cp 又无一致性保证。
脚本用 SQLite 在线备份 API（`sqlite3.Connection.backup`，等价 `sqlite3 .backup`）拿一致快照，
并跑 `PRAGMA integrity_check` 自检 —— 不可读的备份直接判失败删掉（留个坏备份比没有更危险）。
用 Python 而非 sqlite3 CLI：服务器没装 CLI，而 python3 本就是本机制的依赖，标准库自带 sqlite3。

## 定时任务（crontab）
```
0 3 * * *  /root/Notelab/notelab-java/ops/daily-iteration/backup-db.sh >>/root/ops/backup.log 2>&1
15 3 * * * /usr/bin/python3 /root/Notelab/notelab-java/ops/daily-iteration/backup-collab-rooms.py >>/root/ops/backup.log 2>&1
30 7 * * * /usr/bin/python3 /root/Notelab/notelab-java/ops/daily-iteration/daily-check.py >/dev/null 2>>/root/ops/cron.log
```
- **3:00 备份** → 早于巡检，让巡检看到新鲜备份。
- **3:15 画布备份** → 串在 MySQL 之后，避免两个备份任务抢 IO；产物是独立的 `collab-rooms-*.db`。
- **7:30 巡检** → 报告落 `/root/ops/reports/YYYY-MM-DD.md`（保留 30 天）；每周日或 `--deep` 加做依赖更新 + `npm audit`。
- **8:00 Qoder 研判**（Qoder schedule，不在本仓）→ 读最新报告定性告警、低风险项直接改+验证、高风险项列清单等人工拍板。⚠️ 需 Qoder 客户端运行才触发；没开也不漏数据，cron 采集照常。

## 运行产物（不进 git，数据与代码分离）
- `/root/ops/reports/*.md` — 每日巡检报告
- `/root/ops/.logstate.json` — 错误日志基线（算新增量用，消除历史噪音告警疲劳）
- `/root/ops/.backup-db-name` — 备份库名（600）
- `/root/ops/backup.log`、`/root/ops/cron.log` — cron 输出
- `/root/backups/notelab-*.sql.gz` — MySQL 数据库备份
- `/root/backups/collab-rooms-*.db` — 协作画布 SQLite 备份
- `/root/.my.cnf` — MySQL 凭据（600，**含密码，严禁进 git**）

## 首次部署 / 重装
```bash
cd /root/Notelab/notelab-java && git pull            # 取到本目录脚本
bash ops/daily-iteration/setup-mybackup.sh   # 生成凭据 + 连通性测试
bash ops/daily-iteration/install-cron.sh     # 安装两条 cron
python3 ops/daily-iteration/daily-check.py --deep   # 手动跑一次全量巡检验证
```

## 改动流程（防漂移）
1. 本目录脚本是**唯一权威副本**。改脚本 → 在本目录改 → `git commit && git push`。
2. cron 直接执行本目录脚本，**改完即生效**，无需再 scp 到别处。
3. 改 `daily-check.py` 后必须 `python3 -m py_compile` 校验；改 `*.sh` 后 `bash -n` 校验。
4. 本地镜像 `e:\code\NoteLab\notelab-java\ops\daily-iteration\` 通过 `git pull` 同步。

## 已知噪音（Qoder 研判时勿误判为真实故障）
- `notelab-java` 关机期 `ClassNotFoundException` / `NoClassDefFoundError`（Spring Boot fat-jar shutdown hook，无害）。
- `notelab-c` / `notelab-b` 的 `Server Reference ID did not match`（爬虫/扫描器乱 POST Server Action，外部噪音）。
- Swap 长期 ~1980MB（Kokoro TTS 历史驻留；物理内存充足即非紧急）。
- 错误日志已改为**按较上次新增量**告警，只关注新增部分，历史累计不再重复报。

## 安全纪律
- `setup-mybackup.sh` 不回显任何密码值；密码只写入 `/root/.my.cnf`(600)，不上命令行、不进 git。
- 巡检器 `daily-check.py` 纯只读，不做任何修改；`git` 操作仅 `fetch`（读落后），不 commit/push。
- 数据库备份为只读 `mysqldump --single-transaction`，不锁表、不改数据。
