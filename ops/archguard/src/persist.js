#!/usr/bin/env node
/**
 * ArchGuard 快照入库：把 arch-report.json 的汇总 / 循环依赖环 / 规则违规写进 MySQL。
 *
 * 凭据约定与 ops/daily-iteration/backup-db.sh 完全一致：走 /root/.my.cnf（chmod 600），
 * 密码不上命令行、不进 git。刻意不引入超管会话——扫描器只在服务器本地跑，
 * 没必要为此新增一套口令体系；写入端（本脚本）与查询端（后端 /api/arch/*）职责分离。
 *
 * 用法：
 *   node src/persist.js                 # 入库默认报告路径
 *   node src/persist.js --dry           # 只打印将要执行的 SQL（本地调试/校验转义用）
 *   node src/persist.js --report <path> # 指定报告
 *   ARCH_DB=notelab node src/persist.js # 指定库名（默认读 /root/ops/.backup-db-name，兜底 notelab）
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const args = process.argv.slice(2);
const argOf = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : def;
};
const dry = args.includes('--dry');
const reportPath = path.resolve(argOf('report', path.join(__dirname, '..', 'arch-report.json')));
const MY_CNIF = '/root/.my.cnf';
const DB = process.env.ARCH_DB
  || (fs.existsSync('/root/ops/.backup-db-name')
    ? fs.readFileSync('/root/ops/.backup-db-name', 'utf8').trim()
    : 'notelab');

if (!fs.existsSync(reportPath)) {
  console.error('[archguard] 找不到报告：' + reportPath + '（先跑 node src/index.js）');
  process.exit(2);
}

const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
const c = report.counts;
if (!c) {
  console.error('[archguard] 报告缺少 counts 字段：' + reportPath);
  process.exit(2);
}

function esc(v) {
  return String(v === null || v === undefined ? '' : v).replace(/\\/g, '\\\\').replace(/'/g, "''");
}
const lit = (v) => (typeof v === 'number' ? String(v) : "'" + esc(v) + "'");
const lits = (arr) => arr.map(lit).join(',');

const stamp = (report.meta && report.meta.graph && report.meta.graph.generatedAt) || '';
const sql = [
  '-- ArchGuard 快照入库 ' + stamp,
  'INSERT INTO arch_scan_runs (node_count,edge_count,cycle_count,violation_count,error_count,warn_count,build_ms)',
  '    VALUES (' + lits([c.nodes, c.edges, c.cycles, c.violations, c.errors, c.violations - c.errors, report.meta.buildMs]) + ');',
  'SET @run_id = LAST_INSERT_ID();',
].concat(
  (report.cycles || []).map((cy) => (cy.members || [])
    .map((m, seq) => 'INSERT INTO arch_scan_cycles (run_id,member_seq,member) VALUES (@run_id,' + seq + ",'" + esc(m) + "');")
    .join('\n')),
  (report.violations || []).map((v) =>
    'INSERT INTO arch_scan_violations (run_id,rule_id,level,from_module,to_module) VALUES (@run_id,'
    + [v.rule, v.level, v.from, v.to].map(lit).join(',') + ');')
).join('\n');

console.log('[archguard] 待入库：run 1 条 · cycles ' + (report.cycles || []).length
  + ' 环 · violations ' + (report.violations || []).length + ' 条');

if (dry) {
  console.log('--- dry run，未连接数据库 ---');
  console.log(sql);
  process.exit(0);
}

if (!fs.existsSync(MY_CNIF)) {
  console.error('[archguard] 缺少 ' + MY_CNIF + '（daily-iteration 的 setup-mybackup.sh 会生成），拒绝带口令硬编码入库');
  process.exit(2);
}

try {
  execFileSync('mysql', ['--defaults-file=' + MY_CNIF, DB], { input: sql, stdio: ['pipe', 'pipe', 'pipe'] });
} catch (e) {
  const err = (e.stderr && e.stderr.toString()) || e.message;
  console.error('[archguard] 入库失败：' + err.trim().split('\n').slice(-3).join(' '));
  process.exit(1);
}

if (c.errors > 0) {
  console.log('[archguard] 入库 ok，但当前有 ' + c.errors + ' 处 error 级违规（门禁脚本见 M3，此处仅记录事实）');
} else {
  console.log('[archguard] 入库 ok');
}
