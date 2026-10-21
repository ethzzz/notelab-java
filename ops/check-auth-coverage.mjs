// Controller 鉴权覆盖率检查 —— 「新增接口忘了判会话」的唯一自动化兜底。
//
// 背景：ApiPermInterceptor 对**匿名**和**C 端会话**访问非 /api/c/** 的路径一律放行
// （CPermGuard 对非 C 前缀直接 return null），安全性完全依赖每个 Controller 自己调
// AuthUtil.user / CAuthUtil.user 判空。新增一个 Controller 忘记写这句，接口就直接对
// 匿名和全体 C 端用户开放，而且**没有任何运行时报错**。
//
// 本脚本把这个漏洞变成构建期错误：逐个方法检查方法体内有没有鉴权调用，
// 缺失就打印出来并以非零码退出（sync-deploy.sh 会因此不部署）。
//
// 用法：node ops/check-auth-coverage.mjs [--json]
// 退出码：0 = 全部覆盖（或命中的都是已确认的公开端点）；1 = 有未覆盖的方法
//
// ⚠️ 白名单**不在这里硬编码**：公开端点与登录即可用的端点的权威定义在 PermGuard 里
//    （PUBLIC_PREFIXES / SESSION_ENDPOINTS），本脚本直接解析那份源码，避免两份清单漂移。
import fs from "node:fs"
import path from "node:path"

const ROOT = path.resolve(import.meta.dirname, "..")
const CTRL_DIR = path.join(ROOT, "src/main/java/com/notelab/controller")
const PERM_GUARD = path.join(ROOT, "src/main/java/com/notelab/common/PermGuard.java")
const C_PERM_GUARD = path.join(ROOT, "src/main/java/com/notelab/common/CPermGuard.java")

const AUTH_CALL =
	/AuthUtil\.user\(|CAuthUtil\.user\(|unauth\(|isSuperAdmin\s*\(|require(?:Admin|Login|Super|Auth)\w*\(/

/**
 * 显式的「匿名入口」清单：这些接口**刻意**不判会话，安全性靠自身机制保证。
 *
 * ⚠️ 往这里加一条必须在同一行写清楚「凭什么能匿名」—— 这份清单是给它自己看的 review 清单，
 *    不是免检通道。当前只有一条：
 *    · /api/auth/external-login —— 外部演示登录，靠 EXTERNAL_LOGIN_ENABLED 开关（默认已关）
 *      + 每日全站限额 + 按 IP 限流 + LoginAudit 审计；且 external 角色的写权限已被收回（只读）。
 */
const KNOWN_ANONYMOUS = ["/api/auth/external-login"]

/** 从 PermGuard.java 抽取某个常量里的路径字面量 */
function pathsFromGuardConstant(src, name) {
	const m = src.match(new RegExp(`(?:List|Set)<String>\\s+${name}\\s*=\\s*(?:List|Set)\\.of\\(([\\s\\S]*?)\\);`))
	if (!m) return []
	return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
}

/** 去掉行注释与块注释（保留字符串内容，避免把注释里的关键词算作鉴权调用） */
function stripComments(s) {
	return s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "")
}

let publicPrefixes = []
let sessionEndpoints = []
try {
	const guardSrc = fs.readFileSync(PERM_GUARD, "utf8")
	publicPrefixes = pathsFromGuardConstant(guardSrc, "PUBLIC_PREFIXES")
	sessionEndpoints = pathsFromGuardConstant(guardSrc, "SESSION_ENDPOINTS")
	// C 端的两个常量也解析进来：会话基础端点（登录/注册/登出）与「我自己的」前缀
	// —— 都在 CPermGuard 里，重复维护一份必然漂移
	const cGuardSrc = fs.readFileSync(C_PERM_GUARD, "utf8")
	sessionEndpoints.push(...pathsFromGuardConstant(cGuardSrc, "C_SESSION_ENDPOINTS"))
	publicPrefixes.push(...pathsFromGuardConstant(cGuardSrc, "C_SELF_PREFIXES"))
} catch (e) {
	console.error(`⚠️ 读不到 Guard 的端点常量（${e.message}），白名单将为空`)
}

/** 该路径是否属于「已确认公开 / 登录即可用」—— 不需要 Controller 再判会话 */
function isWhitelisted(url) {
	if (KNOWN_ANONYMOUS.includes(url)) return true
	for (const p of publicPrefixes) {
		if (url === p || url.startsWith(p + "/") || url.startsWith(p)) return true
	}
	for (const p of sessionEndpoints) {
		if (url === p) return true
	}
	return false
}

/** 取类级 @RequestMapping 的前缀 */
function classPrefix(src) {
	const m = src.match(/@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"/)
	// 只有出现在第一个 class 声明之前的才算类级
	const clsAt = src.search(/\bclass\s+\w+/)
	if (!m || (clsAt >= 0 && m.index > clsAt)) return ""
	return m[1]
}

const files = fs.readdirSync(CTRL_DIR).filter((f) => f.endsWith(".java"))
const MAP_RE = /@(Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"/g

const missing = []
const whitelistedHits = []
let checked = 0

for (const f of files) {
	const raw = fs.readFileSync(path.join(CTRL_DIR, f), "utf8")
	const src = stripComments(raw)
	const prefix = classPrefix(src)
	const clsAt = src.search(/\bclass\s+\w+/)

	const marks = []
	MAP_RE.lastIndex = 0
	let m
	while ((m = MAP_RE.exec(src))) marks.push({ idx: m.index, method: m[1].toUpperCase(), url: m[2] })

	for (let i = 0; i < marks.length; i++) {
		// 类级映射：出现在 class 声明之前，不是方法
		if (clsAt >= 0 && marks[i].idx < clsAt) continue
		const seg = src.slice(marks[i].idx, i + 1 < marks.length ? marks[i + 1].idx : src.length)
		checked++
		if (AUTH_CALL.test(seg)) continue

		const full = (prefix + marks[i].url).replace(/\/+$/, "") || "/"
		if (isWhitelisted(full)) whitelistedHits.push({ file: f, method: marks[i].method, url: full })
		else missing.push({ file: f, method: marks[i].method, url: full })
	}
}

const out = { checked, missing, whitelisted: whitelistedHits }
if (process.argv.includes("--json")) {
	console.log(JSON.stringify(out, null, 2))
} else {
	console.log(`Controller 鉴权覆盖检查：共 ${checked} 个方法级映射`)
	console.log(`  白名单命中（公开端点 / 登录即可用）：${whitelistedHits.length}`)
	if (whitelistedHits.length) {
		for (const w of whitelistedHits) console.log(`    · ${w.method.padEnd(6)} ${w.url}  (${w.file})`)
	}
	if (missing.length) {
		console.error(`\n❌ ${missing.length} 个映射方法体内没有鉴权调用：`)
		for (const x of missing) console.error(`    ${x.method.padEnd(6)} ${x.url.padEnd(38)} ${x.file}`)
		console.error(`
修法二选一：
  a) 在方法里加   Map<String,Object> me = AuthUtil.user(request); if (me == null) return AuthUtil.unauth();
     （C 端用 CAuthUtil.user）
  b) 如果它确实是公开端点，把前缀加进 PermGuard.PUBLIC_PREFIXES（并在注释里写明为什么可以公开）`)
	} else {
		console.log("\n✅ 所有非公开映射方法都有鉴权调用")
	}
}
process.exitCode = missing.length ? 1 : 0
