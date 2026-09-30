# ArchGuard —— 跨仓依赖架构守护台

扫描 notelab-java / notelab-b / notelab-c 三个仓的源码 `import`，把跨仓依赖关系建成图，
用 Tarjan 强连通分量找循环依赖、用规则引擎查分层违规，并落库供趋势查询。

**全程不依赖大模型**（只用 `java-parser` + `ts-morph` 做 AST 解析）。

## 用法

```bash
# 首次运行自动装依赖（java-parser + ts-morph）
node src/index.js                 # 只扫描，产出 ./arch-report.json
bash run.sh                       # 扫描 + 入库
bash run.sh --no-persist          # 只扫描不入库
node src/persist.js --dry         # 打印将要执行的 SQL，不连库（本地校验转义用）
```

扫描约 2 秒，三个仓 260 个文件、解析零失败。

## 目录

| 文件 | 职责 |
|---|---|
| `src/extract-java.js` | java-parser 提取 package / import / 类名 |
| `src/extract-ts.js` | ts-morph 提取 import（覆盖静态 / `export ... from` / 动态 `import()`） |
| `src/build-graph.js` | 跨语言统一建依赖图，节点 id = `仓名:模块`（Java 用包名、TS 用 app 下二级目录） |
| `src/analyze.js` | Tarjan SCC 找环 + 三条分层规则 + 枢纽度统计 |
| `src/persist.js` | 报告落库（写 MySQL），`--dry` 只看不写 |
| `run.sh` | 一键「装依赖 → 扫描 → 入库」 |

## 规则

| id | 级别 | 含义 |
|---|---|---|
| `no-controller-to-dao` | error | controller 不得直接依赖 mapper / dao / entity（应经 service） |
| `no-service-to-controller` | error | service 不得反向依赖 controller（分层倒灌） |
| `no-controller-to-entity` | warn | controller 直接引用 entity 持久化模型 |

## 入库与查询

写入端（本目录的 `persist.js`）直连 MySQL，凭据走 `/root/.my.cnf`（chmod 600，
与 `../daily-iteration/setup-mybackup.sh` 同一套约定）——**刻意不引入超管会话**，
扫描器只在服务器本地跑，没必要为此新增一套口令；写入端与查询端职责分离。

三张表（`DbSchema.migrateSchema()` 建，重启后端即生效）：

- `arch_scan_runs` —— 每次扫描的汇总（节点/边/环/违规/error/warn/耗时）
- `arch_scan_cycles` —— 每个循环依赖环的各环成员（按环内顺序）
- `arch_scan_violations` —— 每条命中的规则违规

查询端在后端 `ArchScanController`（`/api/arch/*`，B 端超管）：

| 接口 | 用途 |
|---|---|
| `GET /api/arch/summary` | 最近一次扫描 + 与上一次的环比 delta + 该次的环/违规 |
| `GET /api/arch/trend?limit=30` | 趋势序列（时间升序，M3 可视化画折线用） |
| `GET /api/arch/runs/{id}` | 单次扫描明细（汇总 + 环成员 + 违规列表） |

## 部署与跑批

仓库随 notelab-java 分发。刷新依赖：

```bash
cd /root/notelab-java/ops/archguard && npm ci --no-audit --no-fund
```

入库依赖 `/root/.my.cnf` 与 `mysql` 客户端；库名读 `/root/ops/.backup-db-name`（兜底 `notelab`），
可用 `ARCH_DB=<库名>` 覆盖。

## 踩过的坑（改动前先读）

1. **`__dirname` 层级**：本工具在 `<仓根>/ops/archguard/src`，向上三级是 notelab-java 仓根，
   再向上一级才是工作区根（B/C 端是平级的独立仓）。写错就是 ENOENT。
2. **Windows 路径**：Git Bash 的 `/e/code/...` 传给 Windows 版 node 会变成 `E:\e\code\`，
   脚本一律用 `path.join(__dirname, ...)` 相对路径。
3. **java-parser v3 只导出 `parse`/`lexAndParse`**，CST 形状要 unwrap 掉 `{name,children}` 两层，
   否则 import 会**静默全丢**（不报错，只表现为边数为 0）。
4. **ts-morph@28 没有 `getDynamicImports()`**，用 `getImportStringLiterals()` 一次覆盖三种写法。
