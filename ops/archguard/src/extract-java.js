const path = require('path');
const fs = require('fs');
const { parse } = require('java-parser');

// java-parser 的 CST 里同一个规则名可能是「数组」或「{name,children}」包装层，
// unwrap 统一吃掉这两层，返回实际的 children 对象。
function unwrap(x) {
  if (x === null || x === undefined) return null;
  if (Array.isArray(x)) return unwrap(x[0]);
  if (typeof x === 'object' && x.name) return unwrap(x.children || x.name === x.name ? x.children : null);
  return null;
}
function kids(x) {
  const n = Array.isArray(x) ? x[0] : x;
  if (!n) return null;
  return n.children || null;
}
function images(x) {
  const c = kids(x);
  if (!c) return [];
  return ((c.Identifier || []).filter((t) => t && t.image && t.image !== '*')).map((t) => t.image);
}

function parseJavaFile(file) {
  const src = fs.readFileSync(file, 'utf8');
  const root = parse(src);
  const rootKids = kids(root) || {};
  const cu = kids(rootKids.ordinaryCompilationUnit) || {};

  let pkg = null;
  if (cu.packageDeclaration) {
    const segs = images(cu.packageDeclaration);
    if (segs.length) pkg = segs.join('.');
  }

  const imports = [];
  for (const imp of cu.importDeclaration || []) {
    const ic = kids(imp);
    if (!ic) continue;
    const isStatic = !!(ic.Static && ic.Static.length);
    let segs = [];
    let wildcard = false;
    const potn = kids(ic.packageOrTypeName);
    if (potn) {
      const raw = (potn.Identifier || []).map((t) => t.image);
      wildcard = raw.some((s) => s === '*') || !!((potn.Asterisk && potn.Asterisk.length) || (potn.Star && potn.Star.length));
      segs = raw.filter((s) => s !== '*');
    }
    if (!segs.length && !wildcard) segs = images(ic.packageOrTypeName);
    if (segs.length) imports.push({ target: segs.join('.'), wildcard, static: isStatic });
  }

  const classes = [];
  const collectName = (node) => {
    const c = kids(node);
    if (!c) return null;
    const idt = (c.Identifier || []).map((t) => t.image);
    return idt.length ? idt[0] : null;
  };
  const walk = (node) => {
    const c = kids(node);
    if (!c) return;
    for (const key of ['classDeclaration', 'interfaceDeclaration', 'enumDeclaration', 'recordDeclaration']) {
      for (const d of c[key] || []) {
        const n = collectName(d);
        if (n) classes.push(n);
        walk(d);
      }
    }
    for (const t of c.typeDeclaration || []) walk(t);
    for (const t of c.classBody || []) walk(t);
  };
  for (const t of cu.typeDeclaration || []) walk(t);

  return { file, lang: 'java', pkg: pkg || '(default)', imports, classes };
}

function collect(rootDir, out = []) {
  const stack = [rootDir];
  while (stack.length) {
    const cur = stack.pop();
    for (const e of fs.readdirSync(cur, { withFileTypes: true })) {
      const full = path.join(cur, e.name);
      if (e.isDirectory()) {
        if (['target', 'node_modules', '.git', '.workbuddy'].includes(e.name)) continue;
        stack.push(full);
      } else if (e.name.endsWith('.java')) {
        out.push(full);
      }
    }
  }
  return out;
}

module.exports = { parseJavaFile, collect };
