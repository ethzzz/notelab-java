const path = require('path');
const fs = require('fs');
const { Project, SyntaxKind } = require('ts-morph');

const IGNORE = new Set(['node_modules', '.git', '.next', '.workbuddy', 'dist', 'build', 'public', '.recycle-home-20260927']);

function collect(rootDir, out = []) {
  const stack = [rootDir];
  while (stack.length) {
    const cur = stack.pop();
    for (const e of fs.readdirSync(cur, { withFileTypes: true })) {
      const full = path.join(cur, e.name);
      if (e.isDirectory()) {
        if (IGNORE.has(e.name)) continue;
        stack.push(full);
      } else if (e.name.endsWith('.ts') || e.name.endsWith('.tsx')) {
        out.push(full);
      }
    }
  }
  return out;
}

function parseTsFile(sf) {
  const imports = [];
  for (const d of sf.getImportDeclarations()) {
    imports.push({ spec: d.getModuleSpecifierValue(), via: 'import' });
  }
  for (const e of sf.getExportDeclarations()) {
    const m = e.getModuleSpecifier();
    if (m) imports.push({ spec: m.getLiteralValue(), via: 'export-from' });
  }
  const seen = new Set(imports.map((i) => i.spec));
  for (const lit of sf.getImportStringLiterals()) {
    const v = lit.getLiteralValue();
    if (v && !seen.has(v)) imports.push({ spec: v, via: 'dynamic' });
  }
  return imports;
}

function analyze(repoRoot) {
  const files = collect(repoRoot);
  const project = new Project({ useInMemoryFileSystem: true, skipFileDependencyResolution: true });
  const out = [];
  for (const f of files) {
    try {
      const sf = project.createSourceFile(f, fs.readFileSync(f, 'utf8'), { overwrite: true });
      out.push({ file: f, lang: 'ts', imports: parseTsFile(sf) });
    } catch (e) {
      out.push({ file: f, lang: 'ts', imports: [], error: e.message });
    }
  }
  return out;
}

module.exports = { analyze, collect };
