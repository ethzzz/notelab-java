package com.notelab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.notelab.common.JsonUtil;
import com.notelab.service.UiConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目实践 · 项目开发总结：GET|POST /api/dev-notes。
 *
 * <p>存放「做项目时踩到的技术难点」的结构化记录：所属项目 → 条目（现象 / 原因 / 解决方案 / 代码）。
 * 整包存 ui_config JSON 的 "dev_notes" 键，不新建表；结构与写法对齐 {@link LootContentController}：
 * <ul>
 *   <li><b>手工白名单</b>：save() 里逐字段 put，没登记的字段被静默丢弃（爬塔踩过的坑）；</li>
 *   <li><b>懒 seed</b>：库中无 dev_notes 或项目列表为空 → 回内置记录（BASE_PROJECTS），
 *       保证页面首次打开就有内容，用户可以在此基础上增删改；</li>
 *   <li><b>整包覆盖写</b>：POST 提交全量项目数组。</li>
 * </ul>
 *
 * <p>没有发布快照：这不是 C 端消费的内容，只是后台的知识库，编辑即生效。
 *
 * <p>LLM 依赖：无。本 Controller 全程确定性计算。
 */
@RestController
@RequestMapping("/api/dev-notes")
public class DevNoteController {

    /** 条目性质：阻断 / 缺陷 / 坑 / 备忘（展示用，不参与逻辑） */
    private static final Set<String> SEVERITIES = Set.of("blocker", "bug", "pitfall", "note");

    private static final int MAX_DEVNOTES_CHARS = 1_000_000;
    private static final int MAX_PROJECTS = 20;
    private static final int MAX_ENTRIES_PER_PROJECT = 200;
    private static final int MAX_TAGS = 20;

    // ==================================================================================
    // 内置记录（懒 seed 初值）—— 摸金行动 W1 开发过程中真实踩到的技术难点。
    // 与 docs/PRACTICE/loot-开发实践.md 同源；新增条目优先写这里（或直接在后台页面里加）。
    // ==================================================================================

    private static Map<String, Object> devEntry(String id, String title, String stage, String severity,
            String symptom, String cause, String solution, String code, List<String> tags, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("title", title); m.put("stage", stage); m.put("severity", severity);
        m.put("symptom", symptom); m.put("cause", cause); m.put("solution", solution);
        m.put("code", code); m.put("tags", tags); m.put("date", date);
        return m;
    }

    private static Map<String, Object> devProject(String code, String name, String desc,
            List<Map<String, Object>> entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code); m.put("name", name); m.put("desc", desc); m.put("entries", entries);
        return m;
    }

    private static final List<Map<String, Object>> BASE_PROJECTS = List.of(
            devProject("loot", "摸金行动（Loot Raid）",
                    "搜刮撤离类 H5 游戏：进图门槛 → 容器搜刮 → 风险累积 → 撤离结算。B 端配内容、C 端只读消费，LLM 零依赖。",
                    List.of(
                            devEntry("loot-w1-01",
                                    "antd Select 的 onChange 参数类型含 undefined，直接传给要 string 的函数报 TS2322",
                                    "W1 · B 端配置页", "blocker",
                                    "B 端 5 个配置页 build 全挂：Type '(value: string | undefined) => void' is not assignable to … 报 TS2322。\n"
                                            + "本地 dev 不一定复现（热更宽松），是服务器 npm run build 才炸出来的。",
                                    "antd v5 的 Select.onChange 单选取值签名是 (value: ValueType, option) => void，"
                                            + "ValueType 在单选模式下是 string | undefined（allowClear / 值为空时会给 undefined）。"
                                            + "直接把 v 塞进 { ...draft, rarity: v } 就要求 v 是 string → 类型不兼容。",
                                    "所有 Select 的 onChange 加 typeof 守卫（或 ?? \"\" 兜底），别用非空断言骗编译器——"
                                            + "运行时真会拿到 undefined。输入型控件（InputNumber）同理用 v ?? 默认值。",
                                    "// ❌ 报 TS2322\n"
                                            + "<Select value={draft.rarity}\n"
                                            + "  onChange={(r) => setDraft({ ...draft, rarity: r })} />\n\n"
                                            + "// ✅ 加 typeof 守卫\n"
                                            + "<Select value={draft.rarity}\n"
                                            + "  onChange={(r) => { if (typeof r === \"string\") setDraft({ ...draft, rarity: r }) }} />",
                                    List.of("typescript", "antd", "构建", "B端"), "2026-10-03"),
                            devEntry("loot-w1-02",
                                    "内容管线手工白名单：没登记的键会被静默丢弃（保存成功但刷新就没了）",
                                    "W1 · Java 内容管线", "pitfall",
                                    "B 端保存返回 200，刷新后新加的字段/切片消失，且**不报任何错**。"
                                            + "排查时容易怀疑是前端没提交或数据库没写。",
                                    "save() 里是手工逐键 out.put(\"items\", …)、out.put(\"containers\", …) 的白名单写法，"
                                            + "JSON 里没被显式 put 的键直接不进落库对象。白名单是为了防脏键，代价是「新增切片必须记得同步改」。",
                                    "新增一个切片要同时改**两处**：save() 的 put 白名单，和 xxxOf() 的读取分支。"
                                            + "漏改任何一处都表现为「存了读不回来」。验收时用「POST 塞一个 evil:1，读回看 keys 里有没有 evil」来证明白名单生效。",
                                    "// save() 白名单（漏写就丢键）\n"
                                            + "Map<String, Object> loot = new LinkedHashMap<>();\n"
                                            + "loot.put(\"items\", sanitizeItems(node.get(\"items\")));\n"
                                            + "loot.put(\"containers\", sanitizeContainers(node.get(\"containers\")));\n\n"
                                            + "// xxxOf() 读取分支（也要同步）\n"
                                            + "Object it = m.get(\"items\");\n"
                                            + "if (it instanceof List && !((List<?>) it).isEmpty()) out.put(\"items\", it);",
                                    List.of("java", "内容管线", "白名单", "静默失败"), "2026-10-03"),
                            devEntry("loot-w1-03",
                                    "用「懒 seed」让后台首次打开就有内容可编辑",
                                    "W1 · Java 内容管线", "note",
                                    "B 端配置页第一次打开是空表，维护者不知道要填什么、格式是什么，只能去翻代码找示例。",
                                    "内容全在后台配置（不像代码里有常量），库里没数据时没有任何参照物。",
                                    "xxxOf() 里某切片为空就回内置默认常量（BASE_*），**不主动写库**——只有用户点「保存」才落库。"
                                            + "这样默认值改了不需要迁移脚本，用户没动过的切片永远跟随代码里的最新默认。"
                                            + "对应地，C 端也要有一份**逐字段一致**的内置默认包，接口挂掉时回落。",
                                    "// 库中某切片为空 → 回内置默认（懒 seed）\n"
                                            + "out.put(\"items\", new ArrayList<>(BASE_ITEMS));\n"
                                            + "Object it = cfg.get(\"loot\").get(\"items\");\n"
                                            + "if (it instanceof List && !((List<?>) it).isEmpty()) out.put(\"items\", it);",
                                    List.of("java", "懒seed", "默认值"), "2026-10-03"),
                            devEntry("loot-w1-04",
                                    "C 端读「发布快照」而不是编辑中的内容，否则半成品会直接上线",
                                    "W1 · 发布链路", "pitfall",
                                    "如果 C 端直接读后台正在编辑的 loot 键，B 端改到一半点保存，线上就变了——"
                                            + "玩家可能抽到没配完的掉落表（池子空档 → 槽位空手）。",
                                    "编辑态与线上态共用一份数据，没有隔离。",
                                    "publish 时把 lootOf() 的结果整体快照进 ui_config.loot_published，C 端**只读快照**；"
                                            + "unpublish 就删掉这个键让 C 端回落内置默认。B 端顶部用「已发布 / 未发布 / 有未保存改动」三个标记"
                                            + "把这三种状态显式区分开（发布前先 commit，避免发布旧版）。",
                                    "// publish：编辑内容 → 快照（C 端只读这个键）\n"
                                            + "cfg.put(\"loot_published\", lootOf(cfg));\n\n"
                                            + "// 匿名 C 端接口读快照\n"
                                            + "@GetMapping(\"/api/c/loot/content\")\n"
                                            + "public ResponseEntity<Map<String,Object>> c() { … get(\"loot_published\") … }",
                                    List.of("发布", "快照", "隔离", "内容管线"), "2026-10-03"),
                            devEntry("loot-w1-05",
                                    "掉落表池子必须每档至少 1 件候选，否则该档轮盘抽空手",
                                    "W1 · 数值设计", "pitfall",
                                    "保险柜连着开出空槽，表现为「高级容器反而什么都摸不到」。日志里看不到报错，因为空手是合法返回值。",
                                    "抽取流程是「按容器权重抽稀有度档 → 在该档候选里按权重抽物品」。"
                                            + "如果掉落表池子里某个档位一件候选都没有，抽到该档时 rollItem 只能返回 null → 该槽位空手。"
                                            + "档位越高越容易空（池子里传说档往往只有 1-2 件，删一件就空了）。",
                                    "B 端保存时对每张表做「五档候选覆盖检查」并**给警告而不是拒绝保存**（配到一半是正常中间态）；"
                                            + "C 端引擎把 null 当合法结果处理（槽位显示空手），不能抛异常。"
                                            + "验收时用「每档至少 1 件」的断言体检整份内容。",
                                    "// 引擎侧：抽不到就空手，不要抛\n"
                                            + "const item = rollItem(seed, table.pool, rarity)\n"
                                            + "if (!item) { /* 该槽空手，UI 显示「空」 */ }\n\n"
                                            + "// B 端体检：五档是否都有候选\n"
                                            + "const missing = RARITIES.filter(r =>\n"
                                            + "  !table.pool.some(p => byId.get(p.itemId)?.rarity === r))",
                                    List.of("数值", "掉落表", "边界", "轮盘"), "2026-10-03"),
                            devEntry("loot-w1-06",
                                    "未使用的 import 会被 TS 拦下，改名/重构后要顺手摘掉",
                                    "W1 · B 端重构", "bug",
                                    "build 报 TS6133 / noUnusedLocals：'sanitizeWeights' is declared but its value is never read。"
                                            + "代码逻辑完全正常，纯粹是残留导入把构建搞挂。",
                                    "重构时把某段逻辑内联或移走后，import 行还留在文件顶部。",
                                    "构建报错后先看是不是 unused import（最快的一类修复）；"
                                            + "用 grep -rn 该标识符确认真的没别处引用，再删导入行，而不是随手注释掉。",
                                    "// 删之前先确认没有别处引用\ngrep -rn \"displayValue\" src/ --include=*.tsx",
                                    List.of("typescript", "构建", "重构"), "2026-10-03"),
                            devEntry("loot-w1-07",
                                    "本机没有 JDK，Java 侧改动只能靠服务器 mvn 编译验证",
                                    "W1 · 环境约束", "note",
                                    "想在本地先编译一遍 Java 再提交，结果本机没有 java/mvn，无法预检。",
                                    "开发机只装了 Node 与 MySQL，没有 JDK17/Maven（~/.m2/repository 只有残渣）。",
                                    "Java 改动提交后立刻在服务器跑 mvn package 验证，**别带着未编译的改动继续往下写 B/C 端**——"
                                            + "Java 是内容管线的源头，它不通过后面全白做。判据用 $? 而不是 tail 日志（日志里 success 也可能是旧构建）。",
                                    "# 服务器编译预检（本机无 JDK）\n"
                                            + "cd /root/Notelab/notelab-java && mvn -B -DskipTests package > log 2>&1; echo $?",
                                    List.of("环境", "java", "预检"), "2026-10-03"),
                            devEntry("loot-w1-08",
                                    "C 端 notelab-c 没有 basePath，游戏路由就是 /play/loot，别按旧注释加 /games 前缀",
                                    "W1 · C 端路由", "pitfall",
                                    "照旧文档/旧注释给素材与接口路径加 /games 前缀，结果 404（或指向错误的路由）。",
                                    "notelab-c 早期曾用 basePath=\"/games\"，后来去掉了；但仓内仍有历史注释与文档写着带前缀的路径。"
                                            + "同时 nginx 里 /games 与 / 都代理到同一个 3010 端口，所以两条路都能通，问题更隐蔽。",
                                    "新增 C 端路由前先 grep next.config.ts 的 basePath 与服务器 nginx 的 location；"
                                            + "素材 URL 统一走一个 helper（如 spireAssetUrl），不要到处手写前缀拼字符串。",
                                    "# 确认 basePath 与 nginx 路由归属\ngrep -n \"basePath\" next.config.ts\nssh myapp \"nginx -T | grep -nE '^\\s*location'\"",
                                    List.of("nextjs", "路由", "nginx", "环境"), "2026-10-03"),
                            devEntry("loot-w2-01",
                                    "用正文里会出现的短语当状态判据 → 无头验证假阳性",
                                    "W2 · 验收方法", "pitfall",
                                    "CDP 走查脚本用 /撤离成功|行动失败/ 判断「这一局已结算」。脚本报 已结算=true，控制台输出也对，"
                                            + "但**截出来的图还在读条中**（按钮显示「撤离中… 3.7s」）—— 结论和证据互相矛盾。",
                                    "撤离按钮下方那行说明文案是「撤离成功物品进仓库；松手或中途离开视为取消」，它**包含**「撤离成功」四个字。"
                                            + "waitFor 拿整页 innerText 做正则匹配，第一次轮询就命中了这行静态提示，于是在真正结算前就返回 true。"
                                            + "resultReason 同理，打印出的「结果」也是匹配到提示文案得来的。",
                                    "状态判据必须选**该状态独有的元素**，不能用正文里可能出现的短语："
                                            + "① 用该状态才有的交互元素（结算页有「回仓库」按钮，局内没有）；"
                                            + "② 取结构化节点而不是全文（标题取 document.querySelector('h2')）；"
                                            + "③ 截图与断言要能互相证伪 —— 图里出现了不该出现的元素（读条进度条）时，要反过来怀疑断言。"
                                            + "教训：自动化验收里「跑绿了」不等于「验对了」。",
                                    "// ❌ 提示文案里含「撤离成功」→ 读条中就已经 true\n"
                                            + "/撤离成功|行动失败/.test(document.body.innerText)\n\n"
                                            + "// ✅ 结算页独有元素\n"
                                            + "Array.from(document.querySelectorAll('button'))\n"
                                            + "  .some(b => /回仓库/.test(b.textContent))",
                                    List.of("验收", "cdp", "假阳性"), "2026-10-03"),
                            devEntry("loot-w2-02",
                                    "风险只有「容器」一个来源时，风险条永远不动、张力为零",
                                    "W2 · 数值设计", "pitfall",
                                    "第一版把风险实现成「开一个容器 → +容器 riskCost」。两张图各摸满跑一遍：仓库区最高 7/20、港口 23/30，"
                                            + "**两张图都摸不到上限**。风险条这一辈子不会满，riskLimit 这个配置项等于不存在，"
                                            + "玩家永远没有「该收手了」的压力 —— 「风险来自贪」的设计目标完全落空。",
                                    "风险只有一个来源（容器数量），而容器数量由地图配比固定死了、上限也就固定死了："
                                            + "只要地图配比 < riskLimit 就永远安全。这是**只在跑数据时才看得出来的设计错误**，肉眼看代码完全正常。",
                                    "改成两条线叠加，让「贪」直接进公式：① 开一个容器 +容器 riskCost（越高级的容器越危险）；"
                                            + "② 每往背包塞一件东西 +balance.riskPerSlot（越贪越危险）。"
                                            + "实测：仓库区摸满 = 8 件×1 + (4 木箱×1 + 1 保险柜×3) = 15/20（适度就安全）；"
                                            + "港口摸满 = 31/30 → 触发清场（必须提前跑）。两张图给出完全不同的决策压力。",
                                    "// 风险两条线\n"
                                            + "if (firstSlot && def.riskCost > 0) risk += def.riskCost   // ① 开容器\n"
                                            + "if (picked) risk += content.balance.riskPerSlot           // ② 贪（背包满被丢弃的不算）\n"
                                            + "if (risk > raid.riskLimit) return finalize(base, { success: false,\n"
                                            + "  reason: `风险累积超过上限（${risk}/${raid.riskLimit}），被清场` }, content)",
                                    List.of("数值", "风险", "玩家体验"), "2026-10-03"),
                            devEntry("loot-w2-03",
                                    "用 Node 的 --experimental-strip-types 直接跑 TS 纯函数层做离线验证",
                                    "W2 · 工程实践", "note",
                                    "局内逻辑（进图 / 搜刮 / 结算 / 回收）写成纯函数放在 lib/loot-store.ts，"
                                            + "目的是「不依赖浏览器也能跑」。要兑现这个设计，就得真的在没有 DOM 的环境里执行它。",
                                    "裸 Node 不能直接 import TS；装 tsx/ts-node 又要往隔离环境里塞依赖。"
                                            + "另外相对导入在后缀补全上的习惯差异会直接报 ERR_MODULE_NOT_FOUND。",
                                    "Node 22 自带类型剥离，可以**直接执行 .ts**：node --experimental-strip-types sim.mts。"
                                            + "两个必须知道的坑：① 相对导入必须写全 .ts 后缀（Node 的 ESM 解析不做扩展名补全）；"
                                            + "② import type 会被完全擦除，所以目标文件不存在也没关系 —— 这正是把存档类型写成 "
                                            + "import type { LootSave } from \"./loot-save.ts\" 就能跑的原因（它依赖 @/lib/api，裸 Node resolve 不了，"
                                            + "但因为是类型导入压根没被加载）。做法：不污染源码，把文件复制到临时目录用 sed 补后缀。",
                                    "cp lib/loot-engine.ts lib/loot-store.ts .workbuddy/tmp/loot-sim/\n"
                                            + "sed -i 's#from \"./loot-engine\"#from \"./loot-engine.ts\"#; s#from \"./loot-save\"#from \"./loot-save.ts\"#' \\\n"
                                            + "  .workbuddy/tmp/loot-sim/loot-store.ts\n"
                                            + "node --experimental-strip-types .workbuddy/tmp/loot-sim/sim.mts",
                                    List.of("node", "typescript", "验收", "纯函数"), "2026-10-03"),
                            devEntry("loot-w2-04",
                                    "三个 interval 与局内状态：别让 StrictMode 把确定性 RNG 多推一格",
                                    "W2 · React 状态", "note",
                                    "局内同时跑三个定时器：搜刮读条（按容器 slotMs）、按住撤离读条（extractHoldMs）、全局倒计时（250ms 一跳）。"
                                            + "而随机数用的是「seed + cursor 游标」的确定性流 —— 多推一格整局结果就全变了，"
                                            + "而确定性又是验收标准之一。",
                                    "如果把「读条结束 → 推进一格」写成「now 变了就检查进度」的 useEffect，"
                                            + "依赖数组里必然带着 raid/now，重渲染就会重跑；开发模式下 StrictMode 还会双调用 effect，"
                                            + "一不留神就推进两格。",
                                    "① 读条用 setInterval + 立即 clearInterval 的**自终止**结构，触发点在 interval 回调里，"
                                            + "不在「响应状态变化的 effect」里 —— 一次性、不会因重渲染重放；"
                                            + "② setRaid 用**函数式更新**保证读到最新状态；"
                                            + "③ effect 依赖只放状态机开关（searching / hold / phase / seed），不放每帧都变的 now（now 只驱动进度条）；"
                                            + "④ 结算写回存档用 ref 按 raid.seed 去重，避免重复入仓库。",
                                    "useEffect(() => {\n"
                                            + "  if (!searching) return\n"
                                            + "  const id = setInterval(() => {\n"
                                            + "    if (Date.now() - searching.start >= searching.ms) {\n"
                                            + "      clearInterval(id)          // ← 先摘掉，保证只触发一次\n"
                                            + "      applySearch(searching.key)\n"
                                            + "      setSearching(null)\n"
                                            + "    } else setNow(Date.now())     // 只驱动进度条\n"
                                            + "  }, 80)\n"
                                            + "  return () => clearInterval(id)\n"
                                            + "}, [searching, applySearch])",
                                    List.of("react", "定时器", "确定性", "rng"), "2026-10-03"),
                            devEntry("loot-w3-01",
                                    "保底永不触发：计数「到顶清零」+ 把 force 当返回值，调用方却只看计数",
                                    "W3 · 保底机制", "bug",
                                    "10,000 局模拟跑下来保底触发 0 次。保险柜配了「12 次未出 epic 就必出」，一次都没触发，"
                                            + "但页面不报错、单局也看不出异常 —— 只有把整局跑一万遍统计才暴露。",
                                    "原实现把「记账」和「判定」塞进同一个函数，而且都发生在**开容器之前**：计到 afterRuns 就清零，"
                                            + "并把 minRarity 当返回值。调用方存下 state 后，下一次开容器时读 state[id] 判断该不该强制 —— "
                                            + "可它已经被清零了；force 返回值也在调用链里被丢掉。计数永远在 0 与 1 之间来回，够不到判定线。",
                                    "拆成「只读判定」与「消费」：计数到顶就**停在顶、不清零**，由下一次真正强制时清。\n"
                                            + "调用方：const force = firstSlot ? pendingPity(raid.pity, def) : null，抽完再 applyPity(..., force != null)。\n"
                                            + "验证：构造性测试 200/200 触发且 100% 达档；10,000 局自然触发 148（仓库区）/ 213（港口）次。",
                                    "export function pendingPity(state, container) {\n"
                                            + "  const p = container.pity\n"
                                            + "  if (!p) return null\n"
                                            + "  return (state[container.id] || 0) >= p.afterRuns ? p.minRarity : null\n"
                                            + "}\n"
                                            + "// applyPity：到顶停驻（Math.min(cur+1, afterRuns)），仅本次真强制时归零",
                                    List.of("数值", "保底", "状态机", "静默失败"), "2026-10-03"),
                            devEntry("loot-w3-02",
                                    "valueMult 只乘了展示价、没乘回收价 → 后台 EV 面板与真实经济差 2.6 倍",
                                    "W3 · 经济口径", "bug",
                                    "B 端 EV 面板算出港口 1.88×，模拟器（跑真实引擎）跑出 4.89×，差 2.6 倍。"
                                            + "两个数来自同一份配置，却对不上。",
                                    "地图的 valueMult 只在**展示价**上生效，回收时漏了：displayValue 乘了 mult，"
                                            + "recycleValue 却只用 baseValue * recycleRate。玩家眼里「这图的东西值 2 倍」，到手却按原价折算。",
                                    "recycleValue 增加 mult 参数；更关键的是**入库时就把单价钉死**（物品离开地图后就没有 mult 上下文了）："
                                            + "backpack.push({ item, value, unit: recycleValue(pick, balance, map.valueMult) })，结算直接累加 unit。\n"
                                            + "仓库合并键带 unit（${itemId}@${unit}）—— 同一物品从不同倍率的图带出，价格本就不同，按 itemId 合并会抹掉差价；"
                                            + "旧存档无 unit 时按 1× 折算。验证：两图 EV 收敛到 1.71× / 2.30×，两个面板互相印证。",
                                    "displayValue = baseValue * map.valueMult                 // 展示 ✅\n"
                                            + "recycleValue = baseValue * map.valueMult * balance.recycleRate  // 修复前漏了 mult ❌",
                                    List.of("数值", "经济", "口径不一致"), "2026-10-03"),
                            devEntry("loot-w3-03",
                                    "EV 用算术平均 + 忽略背包上限 → 算出 3.00× 而真实只有 2.30×",
                                    "W3 · EV 建模", "bug",
                                    "解析公式算出港口 EV 3.00×，模拟只有 2.30×，差 30%。方向说「经济很厚」，实际没那么厚。",
                                    "两处错误都藏在「平均值」里：① 池内平均用了**算术平均**，而抽中概率与 weight 成正比，"
                                            + "算术平均会把权重 1 的传说当成和权重 30 的普通一样常见，期望被系统性抬高；"
                                            + "② 完全**忽略背包上限** —— 港口 33 个槽位但玩家只背得动 8 件，按「全清」算等于假设无限背包。",
                                    "① 按 weight 加权平均；② 改成「会算账的玩家」模型：单格期望值高的容器先开、装满背包就撤、"
                                            + "会把自己撑爆的格子跳过（risk + riskCost > limit 就 continue）。\n"
                                            + "验证（反例法）：把 backpackCap 从 4 → 8 → 20 扫一遍，gross 应随之上台阶（1875 → 2963 → 3235，20 之后到顶）——"
                                            + "若还是全清，这个数会一步到位顶格。",
                                    "const wsum = cands.reduce((s, p) => s + p.weight, 0)\n"
                                            + "const wavg = cands.reduce((s, p) => s + p.weight * item(p.itemId).baseValue, 0) / wsum\n"
                                            + "for (const g of groups.sort((a, b) => b.ev - a.ev)) {\n"
                                            + "  if (bag >= cap) break\n"
                                            + "  if (risk + g.riskCost > limit) continue\n"
                                            + "  ...\n"
                                            + "}",
                                    List.of("数值", "EV", "建模", "加权"), "2026-10-03"),
                            devEntry("loot-w3-04",
                                    "卡方检验测的是「轮盘」，测不了保底：两种跑法要分开",
                                    "W3 · 验收方法", "note",
                                    "验收要同时证明「各稀有度实测频率 = 配置权重」（卡方）与「单局真实收益 EV 落 [1.5, 3.5]」，"
                                            + "两件事互相干扰。",
                                    "卡方检验的前提是**每次抽取独立同分布**，而保底机制会**主动改写**稀有度分布"
                                            + "（连续 12 次没出 epic 就强制 epic）。拿带保底的模拟跑卡方，测的就不是轮盘而是「轮盘 + 保底」，"
                                            + "偏差必然偏大且随 afterRuns 漂移。",
                                    "两种跑法、两条随机流：\n"
                                            + "· 全清抽样（不带保底，rngDist）→ 稀有度频率 vs 配置权重（卡方）；\n"
                                            + "· 政策模拟（带保底，rngPlay）→ 真实 EV。\n"
                                            + "两条流用不同种子派生，互不污染游标。判据：最大偏差 ≤ 0.015 且 p > 0.05。\n"
                                            + "实测：仓库区 χ²=6.52 (df 4)、p=0.1635、最大偏差 0.107%；港口 χ²=4.87、p=0.3005、0.113%。",
                                    "",
                                    List.of("验收", "统计", "卡方", "保底"), "2026-10-03"),
                            devEntry("loot-w3-05",
                                    "B 端模拟器是 C 端引擎的同构端口，靠「指纹 + 整局逐字段对拍」兜漂移",
                                    "W3 · 跨端同构", "note",
                                    "后台模拟器要跑「真实引擎」，但 notelab-b 与 notelab-c 是两个仓库、不能互相 import，"
                                            + "只能在 B 端手抄一份 C 端引擎逻辑。",
                                    "手抄件一旦和母本漂移（改了一边忘了另一边），后台给出的分布/EV 就是**假的** —— "
                                            + "而且它会一直显示「通过」，因为两边都在自洽地算错。",
                                    "三层对拍：① 指纹 —— 同 seed + 同容器 → 抽取序列哈希逐位相同（rollFingerprint 两端都实现）；"
                                            + "② 整局逐字段 —— 同 seed 跑完整一局，比 rolls / 各档命中数 / EV（实测两端一致：rolls=66000、"
                                            + "命中[common]=32116、EV=2.2762）；③ 取整口径也要对齐（C 端 recycleValue 用 Math.round，"
                                            + "B 端端口必须同样 Math.round，差 1 金币第 2 层就对不上）。",
                                    "",
                                    List.of("跨端", "同构", "验收", "对拍"), "2026-10-03"),
                            devEntry("loot-w3-06",
                                    "EV 守卫挂在页面的保存按钮上 = 没有守卫（换个页面就绕过去了）",
                                    "W3 · 保存守卫", "pitfall",
                                    "EV 守卫（ratio 超阈值就拒绝保存）最初写在「全局参数」页的保存按钮回调里。"
                                            + "测试发现：从「地图配置」页保存，守卫完全不生效。",
                                    "valueMult 是在**地图配置页**改的，EV 面板在**全局参数页** —— 守卫挂在其中一页的按钮上，"
                                            + "另一页的保存路径就绕过去了。更一般地：只要有第二个写入口，挂在按钮上的校验必然被绕过。",
                                    "移到共享 store 的**唯一写入口** commit()（所有页面的 save() 都汇到这里）："
                                            + "reject 直接 toast.error + return false，warn 提示后继续。页面保存按钮退化成 () => save()，"
                                            + "只负责把校验结果画出来。\n"
                                            + "验证（CDP，在「地图配置」页操作）：valueMult=3 → toast「EV 倍率超过 10×……已拒绝保存」且草稿未变；"
                                            + "=0.8 → toast「EV 倍率偏高……已保存」且草稿写入。",
                                    "const evs = d.maps.map((m) => evalMap(m, d.containers, d.tables, d.items, d.balance))\n"
                                            + "const bad = evs.filter((e) => e.level === \"reject\")\n"
                                            + "if (bad.length) { toast.error(`EV 倍率超过 ${d.balance.evRejectRatio}×（...），已拒绝保存`); return false }",
                                    List.of("架构", "守卫", "写入口", "可绕过"), "2026-10-03"),
                            devEntry("loot-w3-07",
                                    "告警阈值低于设计区间下限 → 健康配置常驻告警，验收口径永远不成立",
                                    "W3 · 数值自洽", "pitfall",
                                    "任务书里三条口径同时存在：EV 落 [1.5, 3.5] / evWarnRatio=1.15、evRejectRatio=3.0 / "
                                            + "「valueMult 改成 1.6 → 保存成功但带警告」。实测第三条**在任何门槛下都不成立**："
                                            + "线上配置 ratio/valueMult=4.594（常数），1.6 → 7.35× > 3.0 被拒；退回改前的门槛 900 也是 3.27× 仍被拒。",
                                    "阈值与设计区间**量纲不自洽**：evWarnRatio=1.15 低于区间下限 1.5 → 落进设计区间的健康图"
                                            + "（1.71×/2.30×）都常驻告警（告警疲劳）；evRejectRatio=3.0 几乎贴着区间上限 3.5 → "
                                            + "手改 valueMult 一点点就被拒绝。",
                                    "让阈值由设计区间推导：evWarnRatio = 3.5（= 区间上限，超出健康区间才提示），"
                                            + "evRejectRatio = 10.0（= 10× 门槛，崩到这个量级才拒绝保存）。三端必须同值"
                                            + "（Java BASE_BALANCE / B model.ts / C loot-content.ts），且因懒 seed 不改线上已有数据，"
                                            + "必须显式重发布一次 ui_config.loot。\n"
                                            + "验证：现状 1.711×/2.297× → ok；1.6 → 7.351× warn；10 → 45.943× reject。\n"
                                            + "教训：验收口径里的每一个数都要先用真实配置验算一遍，别等实现完了才发现口径自相矛盾。",
                                    "",
                                    List.of("数值", "阈值", "自洽", "需求缺陷"), "2026-10-03"),
                            devEntry("loot-w3-08",
                                    "CDP 验 antd v5：modal / toast 的类名全变了；且「保存」写的是草稿不是快照",
                                    "W3 · 验收方法", "pitfall",
                                    "无头走查一连四个坑：① waitForSelector(\".ant-modal-content\") 超时 10s，但弹窗明明已打开；"
                                            + "② toast 用 .ant-message-notice-content 也取不到；③ antd InputNumber 用 click({clickCount:3}) "
                                            + "选不中已有文本，Backspace+type 变成追加（0.5 → 0.53）；④ 保存后读匿名接口验证「没生效」。",
                                    "① antd v5 新结构里 Modal 根节点是 .ant-modal、内容层是 .ant-modal-container，**没有 .ant-modal-content**；"
                                            + "② 本项目 toast 走 antd message（lib/toast.ts 门面 + ToastHost），实际容器是 .ant-message-notice；"
                                            + "③ clickCount:3 在无头下选不中文本；④ B 端「保存」只更新 ui_config.loot（**草稿**），"
                                            + "要点「发布到 C 端」才更新 loot_published，而 /api/c/loot/content（匿名）读的是**快照** —— "
                                            + "所以保存后读匿名接口必然读到旧值。",
                                    "① 先 dump document.querySelectorAll(\"[class*=modal]\") / [class*=message] 的 className 列表再定选择器；"
                                            + "② InputNumber 用 Ctrl+A 全选再输入（keyboard.down(\"Control\") → press(\"KeyA\") → up → type），"
                                            + "改完**必须回读** input.value 确认；③ 断言要么读登录接口 /api/loot-content（草稿），要么显式调一次 publish；"
                                            + "④ 改过线上配置的验收脚本一定要把值改回去，最后做一次「草稿 vs 快照」全切片比对（本次五项全部一致才算干净）。",
                                    "await el.click()\n"
                                            + "await page.keyboard.down(\"Control\"); await page.keyboard.press(\"KeyA\"); await page.keyboard.up(\"Control\")\n"
                                            + "await page.keyboard.type(String(v)); await page.keyboard.press(\"Tab\")",
                                    List.of("验收", "cdp", "antd", "选择器"), "2026-10-03")
                    ))
    );

    // ==================================================================================
    // 接口
    // ==================================================================================

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        return ResponseEntity.ok(devNotesOf(UiConfigService.getConfig()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) String raw,
                                                    HttpServletRequest request) {
        if (AuthUtil.user(request) == null) return AuthUtil.unauth();
        JsonNode node;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty");
            node = JsonUtil.parse(raw);
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "请求体不是合法 JSON"));
        }
        if (!node.isObject()) return ResponseEntity.status(400).body(Map.of("error", "body 必须是对象"));

        // ⚠️ 手工白名单：这里没 put 的键会被静默丢弃（存了刷新就没了且不报错）
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("projects", sanitizeProjects(node.get("projects")));

        String json = JsonUtil.write(doc);
        if (json.length() > MAX_DEVNOTES_CHARS) {
            return ResponseEntity.status(400).body(Map.of("error",
                    "内容过大（>" + (MAX_DEVNOTES_CHARS / 1000) + "KB），精简几条记录再保存"));
        }
        Map<String, Object> cfg = new LinkedHashMap<>(UiConfigService.getConfig());
        cfg.put("dev_notes", doc);
        try {
            UiConfigService.saveUiConfig(JsonUtil.write(cfg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "保存失败：" + e));
        }
        UiConfigService.invalidate();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** dev_notes 出口：库中没有或项目列表为空 → 回内置记录（懒 seed） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> devNotesOf(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", new ArrayList<>(BASE_PROJECTS));
        Object o = cfg.get("dev_notes");
        if (o instanceof Map) {
            Object ps = ((Map<String, Object>) o).get("projects");
            if (ps instanceof List && !((List<?>) ps).isEmpty()) out.put("projects", ps);
        }
        return out;
    }

    // ==================================================================================
    // 净化（只做结构与体积校验；不合法丢该条，不让整包 400）
    // ==================================================================================

    private static List<Map<String, Object>> sanitizeProjects(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode p : node) {
            if (out.size() >= MAX_PROJECTS) break;
            if (p == null || !p.isObject()) continue;
            String code = str(p, "code", 64);
            String name = str(p, "name", 100);
            if (code.isEmpty() || name.isEmpty() || seen.contains(code)) continue;
            seen.add(code);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            m.put("desc", str(p, "desc", 500));
            m.put("entries", sanitizeEntries(p.get("entries")));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> sanitizeEntries(JsonNode node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode e : node) {
            if (out.size() >= MAX_ENTRIES_PER_PROJECT) break;
            if (e == null || !e.isObject()) continue;
            String id = str(e, "id", 64);
            String title = str(e, "title", 200);
            if (id.isEmpty() || title.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            String severity = str(e, "severity", 16);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("stage", str(e, "stage", 100));
            m.put("severity", SEVERITIES.contains(severity) ? severity : "pitfall");
            m.put("symptom", str(e, "symptom", 4000));
            m.put("cause", str(e, "cause", 4000));
            m.put("solution", str(e, "solution", 8000));
            m.put("code", str(e, "code", 20000));
            m.put("tags", strList(e.get("tags"), MAX_TAGS, 32));
            m.put("date", str(e, "date", 20));
            out.add(m);
        }
        return out;
    }

    private static List<String> strList(JsonNode node, int max, int itemMax) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode t : node) {
            if (out.size() >= max) break;
            if (t == null || !t.isTextual()) continue;
            String s = t.asText().trim();
            if (s.isEmpty()) continue;
            out.add(s.length() > itemMax ? s.substring(0, itemMax) : s);
        }
        return out;
    }

    private static String str(JsonNode n, String field, int max) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || !v.isTextual()) return "";
        String s = v.asText().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
