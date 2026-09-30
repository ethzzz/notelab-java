const path = require('path');
const fs = require('fs');
const { buildGraph } = require('./build-graph');
const { analyze } = require('./analyze');

// 本工具随 notelab-java 仓分发（路径 <仓根>/ops/archguard/src），向上三级即 notelab-java 仓根。
// B/C 端是与 notelab-java 平级的独立 git 仓，所以再向上一层才是工作区根，兄弟仓从那里找。
const REPO_ROOT = path.join(__dirname, '..', '..', '..');
const WORKSPACE = path.join(REPO_ROOT, '..');

const REPOS = [
  { name: 'notelab-java', root: path.join(REPO_ROOT, 'src', 'main', 'java'), lang: 'java' },
  { name: 'notelab-b', root: path.join(WORKSPACE, 'notelab-b'), lang: 'ts' },
  { name: 'notelab-c', root: path.join(WORKSPACE, 'notelab-c'), lang: 'ts' },
];

const outArg = process.argv.indexOf('--out');
const out = outArg >= 0 ? process.argv[outArg + 1] : path.join(__dirname, '..', 'arch-report.json');

const t0 = Date.now();
const graph = buildGraph(REPOS);
const report = analyze(graph);
report.meta = { buildMs: Date.now() - t0, graph };

fs.writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');

const c = report.counts;
console.log(`扫描完成 ${report.meta.buildMs}ms → ${out}`);
console.log(`节点 ${c.nodes} · 依赖边 ${c.edges} · 循环依赖环 ${c.cycles} · 规则违规 ${c.violations}（error ${c.errors}）`);
if (report.cycles.length) {
  console.log('\n【循环依赖】');
  report.cycles.forEach((cy, i) => console.log(`  ${i + 1}. ${cy.members.join(' → ')} → ${cy.members[0]}`));
}
if (report.violations.length) {
  console.log('\n【规则违规】');
  const byRule = {};
  for (const v of report.violations) (byRule[v.rule] ||= []).push(`${v.from} → ${v.to}`);
  for (const [rule, list] of Object.entries(byRule)) console.log(`  ${rule} (${list.length}):\n    ${list.slice(0, 6).join('\n    ')}`);
}
const byRepo = {};
for (const n of report.meta.graph.nodes) {
  const r = n.id.split(':')[0];
  byRepo[r] ||= { nodes: 0, edges: 0, files: 0 };
  byRepo[r].nodes++;
  byRepo[r].files += n.files;
}
for (const e of report.meta.graph.edges) {
  const r = e.from.split(':')[0];
  if (byRepo[r]) byRepo[r].edges++;
}
console.log('\n【按仓分组】');
for (const [r, v] of Object.entries(byRepo)) console.log(`  ${r}: 节点${v.nodes} 边${v.edges} 文件${v.files}`);

console.log('\n【依赖枢纽 top8】');
report.hubs.slice(0, 8).forEach((h) => console.log(`  ${h.id}  入${h.in} 出${h.out}`));
process.exit(report.counts.errors > 0 ? 1 : 0);
