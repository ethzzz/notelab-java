function layerOf(nodeId) {
  const seg = nodeId.split(':');
  const tail = seg[1].split('.').pop();
  return tail;
}

function tarjanSCC(nodes, edges) {
  const adj = new Map();
  for (const n of nodes) adj.set(n.id, []);
  for (const e of edges) if (adj.has(e.from)) adj.get(e.from).push(e.to);

  let counter = 0;
  const idx = new Map();
  const low = new Map();
  const onStack = new Set();
  const stack = [];
  const comps = [];

  const strongConnect = (v) => {
    idx.set(v, counter);
    low.set(v, counter);
    counter++;
    stack.push(v);
    onStack.add(v);
    for (const w of adj.get(v) || []) {
      if (!idx.has(w)) {
        strongConnect(w);
        low.set(v, Math.min(low.get(v), low.get(w)));
      } else if (onStack.has(w)) {
        low.set(v, Math.min(low.get(v), idx.get(w)));
      }
    }
    if (low.get(v) === idx.get(v)) {
      const comp = [];
      let w;
      do {
        w = stack.pop();
        onStack.delete(w);
        comp.push(w);
      } while (w !== v);
      comps.push(comp);
    }
  };

  for (const n of nodes) if (!idx.has(n.id)) strongConnect(n.id);
  return comps.filter((c) => c.length > 1);
}

const RULES = [
  {
    id: 'no-controller-to-dao',
    level: 'error',
    desc: 'controller 不得直接依赖 mapper / dao / entity（应经 service 层）',
    check: (e) => layerOf(e.from) === 'controller' && ['mapper', 'dao', 'entity'].includes(layerOf(e.to)),
  },
  {
    id: 'no-service-to-controller',
    level: 'error',
    desc: 'service 不得反向依赖 controller（分层倒灌）',
    check: (e) => layerOf(e.from) === 'service' && layerOf(e.to) === 'controller',
  },
  {
    id: 'no-controller-to-entity',
    level: 'warn',
    desc: 'controller 直接引用 entity 持久化模型',
    check: (e) => layerOf(e.from) === 'controller' && layerOf(e.to) === 'entity',
  },
];

function analyze(graph) {
  const cycles = tarjanSCC(graph.nodes, graph.edges).map((c) => ({
    members: c.sort(),
    size: c.length,
  }));

  const violations = [];
  for (const e of graph.edges) {
    for (const r of RULES) {
      if (r.check(e)) violations.push({ rule: r.id, level: r.level, from: e.from, to: e.to, desc: r.desc });
    }
  }

  const scored = graph.nodes.map((n) => ({ id: n.id, kind: n.kind, files: n.files, in: n.in, out: n.out, score: n.in + n.out }));
  const hubs = scored.filter((n) => n.score > 0).sort((a, b) => b.score - a.score).slice(0, 12);
  const heavyFiles = graph.nodes.filter((n) => n.files >= 5).sort((a, b) => b.files - a.files).slice(0, 10);

  return {
    cycles,
    violations,
    hubs,
    heavyFiles,
    counts: {
      nodes: graph.stats.nodeCount,
      edges: graph.stats.edgeCount,
      cycles: cycles.length,
      violations: violations.length,
      errors: violations.filter((v) => v.level === 'error').length,
    },
  };
}

module.exports = { analyze, tarjanSCC, RULES };
