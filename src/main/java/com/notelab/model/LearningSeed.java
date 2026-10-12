package com.notelab.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编程学习 · 内置内容（懒 seed 初值）。
 *
 * <p>与 {@link MenuTree}、{@link PageRoutes} 同级：都是「代码里维护一份、启动即可用」的常量数据结构。
 * 内容存 ui_config 的 learning 键（见 LearningContentController），编辑即生效、无发布快照 ——
 * 这是后台知识库，不是 C 端消费的内容。
 *
 * <p><b>核心设计</b>：每个语言（track）带一个 {@code runner} 字段，指向 {@link #RUNNERS} 里的运行方案 id。
 * 页面据此显示「能不能在浏览器里真跑、走什么方案」。这样架子先立起来，
 * 将来接真实运行时只需要实现对应 runner，**不用改数据结构**。
 *
 * <p>三档划分（2026-10-12 调研结论，见每条 runner 的 note）：
 * <ul>
 *   <li><b>ready</b> —— 浏览器里就能跑，自托管、离线可用、体积可控（JS/TS/Python/SQL/Lua）</li>
 *   <li><b>heavy</b> —— 能跑但要权衡：体积大（C/C++ 首屏 70MB+）或不能自托管（Java 的 CheerpJ 社区版）</li>
 *   <li><b>server</b> —— 浏览器里跑不了，要真跑只能上后端沙箱（Go/Rust/C#：编译链太重，业界都是后端方案）</li>
 * </ul>
 *
 * <p>LLM 依赖：无。全是确定性常量。
 */
public final class LearningSeed {

    private LearningSeed() {}

    // ==================================================================================
    // 运行方案能力表 —— 页面用它解释「为什么这门语言现在跑不了 / 怎么才能跑」
    // ==================================================================================

    private static Map<String, Object> runner(String id, String name, String level, String engine,
            String size, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        // ready=浏览器内可跑 / heavy=能跑但代价大 / server=必须后端沙箱 / none=只能讲解
        m.put("level", level);
        m.put("engine", engine);
        m.put("size", size);
        m.put("note", note);
        return m;
    }

    public static final List<Map<String, Object>> RUNNERS = List.of(
            runner("worker", "浏览器原生 Worker", "ready", "运行时自带",
                    "0",
                    "JS/TS/HTML 直接用浏览器的 Worker + Blob URL 隔离执行，不下载任何东西。"
                            + "零成本、零延迟，是最该先接的一档。TS 只需在浏览器里调一次 transpileModule（不做类型检查）。"),
            runner("pyodide", "Pyodide（CPython→WASM）", "ready", "Pyodide 0.29.x / Python 3.13",
                    "核心约 6–12MB（gzip）",
                    "真正的 CPython 编译到 WebAssembly，numpy/pandas 等 200+ 包预编译好按需下载。"
                            + "必须放 Worker 里（初始化要 1–3 秒，主线程会卡页面）。首次加载后浏览器缓存，可离线。"
                            + "⚠️ 资源要自托管：走公共 CDN 在国内不稳（本项目刚在 Quill 样式上踩过 jsdelivr 的坑）。"),
            runner("sqljs", "sql.js（SQLite→WASM）", "ready", "SQLite 3.x",
                    "约 1.5MB",
                    "整个 SQLite 编译成 WASM，在内存里建库跑任意 SQL，可导入/导出 .sqlite 文件。"
                            + "教程场景非常合适：每条 SQL 都能真跑真看结果。"),
            runner("fengari", "Fengari（Lua 5.3 的 JS 实现）", "ready", "Lua 5.3",
                    "约 300KB",
                    "纯 JavaScript 实现的 Lua 虚拟机，没有 WASM 下载成本。体积在这一档里最小，适合讲脚本语言基础。"),
            runner("clang-wasm", "Clang→WASM", "heavy", "Clang 22（LiveCodes 用同一份）",
                    "首屏 70MB+（clang 43MB + lld 23MB + sysroot 29MB，未压缩）",
                    "能把 C/C++ 源码在浏览器里真编译再执行。代价是首次加载量级到几十兆，"
                            + "且编译期间内存占用高 —— 适合做成「点一下才下载」的可选能力，别放在默认路径上。"),
            runner("cheerpj", "CheerpJ（WASM JVM）", "heavy", "Java 8（CheerpJ 3）",
                    "由官方云端提供，不占本站体积",
                    "社区版对个人项目免费（含个人商用），但**明确不允许自托管** —— 运行时由 LeaningTech 云端提供，"
                            + "意味着这条路径依赖外部服务可用性。另外只支持 Java 8，学不到现代 Java 的新特性。"),
            runner("server", "后端沙箱", "server", "Docker 容器（一语言一镜像）",
                    "不占首屏",
                    "Go/Rust/C# 这类编译链，浏览器里没有实用方案（业界 playground 也都是后端跑）。"
                            + "本站服务器有 Docker，可做「跑完即毁 + 超时 + 限内存 + 禁网」的沙箱。"
                            + "⚠️ 但服务器只有 2 核 3.9G 且已在吃 swap —— 真要做必须先加上资源上限与并发闸门。"),
            runner("none", "暂不执行", "none", "—",
                    "—",
                    "只做「知识讲解 + 随堂测试」，不执行代码。语言本身没方案，或当前阶段先不接运行器。")
    );

    // ==================================================================================
    // 语言（track）—— 每个带 runner 指向上面的方案
    // ==================================================================================

    /** 一条随堂测试题。answer 是正确选项的**下标**（0 起）。 */
    private static Map<String, Object> quiz(String q, List<String> options, int answer, String explain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("q", q);
        m.put("options", options);
        m.put("answer", answer);
        m.put("explain", explain);
        return m;
    }

    /**
     * 一节课：讲解 + 示例代码 + 随堂测试。
     *
     * <p>{@code code} 是「能直接丢进运行器跑」的完整片段 —— 以后接 runner 时不用再改内容。
     */
    private static Map<String, Object> lesson(String id, String title, int minutes, String content,
            String code, String lang, List<Map<String, Object>> quizzes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("minutes", minutes);
        m.put("content", content);
        m.put("code", code);
        m.put("lang", lang);
        m.put("quiz", quizzes);
        return m;
    }

    private static Map<String, Object> stage(String id, String name, String goal,
            List<Map<String, Object>> lessons) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("goal", goal);
        m.put("lessons", lessons);
        return m;
    }

    private static Map<String, Object> track(String code, String name, String icon, String desc,
            String runner, List<Map<String, Object>> stages) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("icon", icon);
        m.put("desc", desc);
        m.put("runner", runner);
        m.put("stages", stages);
        return m;
    }

    // --------------------------------------------------------------------------------
    // JavaScript —— 内容写全，作为其它语言的模板
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> JS_LESSONS = List.of(
            lesson("js-01", "变量与类型", 15,
                    """
                    声明变量只用两种：`const` 和 `let`。默认写 `const`，只有确实要重新赋值时才用 `let`。

                    JS 是动态类型语言，同一个变量可以先放数字再放字符串，但这不代表类型不重要 ——
                    真正决定行为的是**值本身**的类型。

                    几个新手必踩的点：

                    1. `==` 会做隐式类型转换（`"1" == 1` 是 true），一律用 `===`
                    2. 基本类型有 7 种：number、string、boolean、null、undefined、symbol、bigint
                    3. `typeof null` 返回 `"object"` —— 这是 1995 年留下的历史 bug，至今没修（改了就破坏兼容）
                    4. `null` 和 `undefined` 是两个东西：前者是「我主动置空」，后者是「还没有值」

                    判断一个值是不是数字更稳的写法是 `Number.isFinite(x)`，它同时排除 NaN 和 Infinity。
                    """,
                    """
                    const name = "Alice";          // 不会变 → const
                    let count = 0;                 // 会变 → let

                    count += 1;
                    console.log(name, count);       // Alice 1

                    // 一律用 === 而不是 ==：后者会隐式转换类型
                    console.log(1 === "1");         // false
                    console.log(typeof null);       // "object"（历史遗留）
                    console.log(Number.isFinite(42)); // true
                    """,
                    "javascript",
                    List.of(
                            quiz("`1 === \"1\"` 的结果是？",
                                    List.of("true", "false", "抛异常", "取决于浏览器"), 1,
                                    "`===` 是严格相等，类型不同直接返回 false，不做任何转换。"
                                            + "换成 `==` 就会得到 true —— 这正是它容易出 bug 的原因。"),
                            quiz("`typeof null` 返回什么？",
                                    List.of("\"null\"", "\"object\"", "\"undefined\"", "报错"), 1,
                                    "这是 JS 最早期的实现缺陷（用低位标记类型，null 恰好落在 object 那一档），"
                                            + "修它会破坏大量既有代码。要判断 null 直接写 `x === null`。"))),

            lesson("js-02", "函数与作用域", 20,
                    """
                    函数有三种常见写法，日常优先用箭头函数：

                    ```js
                    function a(x) { return x * 2 }        // 函数声明，会提升
                    const b = function (x) { return x * 2 } // 函数表达式
                    const c = (x) => x * 2                 // 箭头函数，最简
                    ```

                    箭头函数和普通函数有一个关键区别：**它没有自己的 `this`**。
                    箭头函数里的 `this` 直接取外层作用域的值，所以它特别适合做回调：

                    ```js
                    const obj = {
                      items: [1, 2, 3],
                      sum() {
                        // 用 function 的话这里的 this 会变成 undefined
                        return this.items.reduce((a, b) => a + b, 0)
                      },
                    }
                    ```

                    另一个必须理解的概念是**闭包**：函数会「记住」它定义时所在的作用域，
                    即使那个作用域已经执行完了。

                    ```js
                    function counter() {
                      let n = 0
                      return () => ++n     // 这个函数一直持有 n
                    }
                    const next = counter()
                    next() // 1
                    next() // 2
                    ```

                    `n` 不会被回收，也不会被外部直接访问 —— 这就是闭包，也是模块私有变量的实现基础。
                    """,
                    """
                    // 闭包：函数记住了它定义时的作用域
                    function counter() {
                      let n = 0;
                      return () => ++n;
                    }

                    const next = counter();
                    console.log(next());  // 1
                    console.log(next());  // 2

                    // 箭头函数没有自己的 this，取外层的
                    const obj = {
                      items: [1, 2, 3],
                      sum() {
                        return this.items.reduce((a, b) => a + b, 0);
                      },
                    };
                    console.log(obj.sum()); // 6
                    """,
                    "javascript",
                    List.of(
                            quiz("箭头函数和普通函数最本质的区别是？",
                                    List.of("箭头函数不能传参", "箭头函数没有自己的 this", "箭头函数更快", "箭头函数不能返回值"), 1,
                                    "箭头函数没有自己的 `this`/`arguments`，会沿作用域链向外找。"
                                            + "这正是它在回调里好用的原因，也是它不能当构造函数（不能 new）的原因。"),
                            quiz("`counter()` 内部的 `n` 为什么不会被回收？",
                                    List.of("因为 n 是全局变量", "因为返回的函数仍持有它（闭包）", "因为 JS 不回收数字", "因为用了 let"), 1,
                                    "只要还有函数引用着这个作用域，作用域就不会被回收。"
                                            + "这既让闭包能保存状态，也意味着用不好会内存泄漏（比如把大对象挂在闭包里）。"))),

            lesson("js-03", "数组三件套：map / filter / reduce", 20,
                    """
                    这三个方法覆盖了日常绝大部分数组处理。它们的共同点是**不修改原数组**，都返回新值。

                    - `map`：一对一转换，长度不变
                    - `filter`：按条件筛选，长度会变
                    - `reduce`：把整个数组「折叠」成一个值

                    ```js
                    const nums = [1, 2, 3, 4, 5]

                    nums.map((n) => n * 2)              // [2, 4, 6, 8, 10]
                    nums.filter((n) => n % 2 === 1)     // [1, 3, 5]
                    nums.reduce((sum, n) => sum + n, 0) // 15
                    ```

                    `reduce` 的第二个参数是初始值。**一定要传它**：不传时会把第一个元素当初始值，
                    空数组还会直接抛 TypeError。

                    链式调用很自然，但注意每调一次就多遍历一遍：

                    ```js
                    const total = orders
                      .filter((o) => o.paid)          // 只算已支付的
                      .map((o) => o.amount)           // 取出金额
                      .reduce((s, v) => s + v, 0)     // 求和
                    ```

                    数据量大到几万条以上时，才需要考虑换成一次 for 循环合并处理 —— 提前优化没有意义。
                    """,
                    """
                    const nums = [1, 2, 3, 4, 5];

                    console.log(nums.map((n) => n * 2));               // [2,4,6,8,10]
                    console.log(nums.filter((n) => n % 2 === 1));      // [1,3,5]
                    console.log(nums.reduce((sum, n) => sum + n, 0));  // 15

                    // 链式：不修改原数组
                    const orders = [
                      { paid: true, amount: 30 },
                      { paid: false, amount: 50 },
                      { paid: true, amount: 20 },
                    ];
                    const total = orders
                      .filter((o) => o.paid)
                      .map((o) => o.amount)
                      .reduce((s, v) => s + v, 0);
                    console.log(total); // 50
                    console.log(nums);  // [1,2,3,4,5] 原数组没变
                    """,
                    "javascript",
                    List.of(
                            quiz("`reduce` 不传第二个参数（初始值）时，在空数组上会怎样？",
                                    List.of("返回 undefined", "返回 0", "抛 TypeError", "返回 null"), 2,
                                    "没有初始值时 reduce 会把第一个元素当初始值，空数组一个都没有 → 直接抛错。"
                                            + "所以只要数组可能为空，就必须传初始值。"),
                            quiz("`[1,2,3].map((n) => n * 2)` 之后原数组变成了什么？",
                                    List.of("[2,4,6]", "[1,2,3]", "[2,2,3]", "报错"), 1,
                                    "map/filter/reduce 都是**纯**方法，返回新数组、不动原数组。"
                                            + "要改原数组得用 splice/sort 这类原地方法 —— 混用是常见 bug 来源。"))));

    // --------------------------------------------------------------------------------
    // TypeScript —— 与 JS 同构三节，重点在「JS 没有的那部分」：类型系统
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> TS_LESSONS = List.of(
            lesson("ts-01", "类型标注与类型推断", 15,
                    """
                    TypeScript 是 JavaScript 的**超集** —— 所有合法的 JS 都是合法的 TS，此外你还能写类型。

                    先记住最重要的一件事：**类型只存在于编译期，运行时会被全部擦除**。

                    ```ts
                    const n: number = 42
                    ```
                    编译产物就是：
                    ```js
                    const n = 42
                    ```
                    类型不产生运行时开销，也不做运行期校验 —— 它拦的是「写代码时的错」，不是「运行时的错」。
                    （所以本站 TS 和 JS 共用一个 Worker 运行器，只多一步「抹掉类型」。）

                    **什么时候写、什么时候不写**

                    TS 会自己推断，能推出来就别手写：

                    ```ts
                    const n = 42              // 推断为 number
                    const arr = [1, 2, 3]     // 推断为 number[]
                    let s = "hi"              // 推断为 string
                    ```
                    必须手写的只有两处：

                    1. **函数参数** —— TS 无法从调用点反推
                    2. **导出的函数返回值** —— 显式写出，避免实现改动时类型悄悄漂移

                    ```ts
                    export function add(a: number, b: number): number {
                      return a + b
                    }
                    ```

                    **any 与 unknown**

                    `any` 会**关掉检查**，而且会传染（碰过它之后整条链路都失去类型）。
                    `unknown` 是安全的对应物：什么都能装进去，但**用之前必须先收窄**。

                    ```ts
                    function len(v: unknown) {
                      // v.length        // ❌ 编译不过：不保证 v 有 length
                      if (typeof v === "string") return v.length   // ✅ 收窄后可以
                      return 0
                    }
                    ```

                    一个常见误解：`const x: {}` **不是**「空对象」，而是「任何非 null/undefined 的值」—— 连数字都能装。
                    """,
                    """
                    // 类型只服务编译期，运行前会被擦除 —— 这里看到的是擦除后的行为
                    const n = 42;                     // 推断 number，不必手写
                    const tags: string[] = ["ts", "js"];

                    function describe(v: unknown): string {
                      if (typeof v === "string") return `字符串，长度 ${v.length}`;
                      if (Array.isArray(v)) return `数组，${v.length} 项`;
                      return "其它类型";
                    }

                    console.log(describe("hello"));  // 字符串，长度 5
                    console.log(describe(tags));     // 数组，2 项
                    console.log(describe(42));       // 其它类型
                    """,
                    "typescript",
                    List.of(
                            quiz("TS 的类型标注在编译产物里会变成什么？",
                                    List.of("原样保留", "被全部擦除，不产生运行时开销", "变成运行时的类型校验", "只在开发模式保留"), 1,
                                    "类型只服务编译期，擦除是 TS 的核心设计 —— 这正是它零运行时开销、"
                                            + "能与 JS 无缝共存的原因。要运行时校验得自己写（或用 zod 这类库）。"),
                            quiz("`unknown` 与 `any` 的关键区别是？",
                                    List.of("unknown 运行更慢", "unknown 用之前必须先收窄类型", "any 只能装数字", "两者完全等价"), 1,
                                    "`any` 直接关掉检查且会传染；`unknown` 什么都能装，但读取属性/调用方法前"
                                            + "必须先用 typeof、instanceof 等收窄 —— 逼你处理「可能不是这个类型」的情况。"))),

            lesson("ts-02", "对象形状、联合类型与「让非法状态无法表示」", 20,
                    """
                    对象形状用 `interface` 或 `type` 描述。日常差异很小，团队统一选一个即可（这里用 interface）：

                    ```ts
                    interface User {
                      id: number
                      name: string
                      email?: string             // 可选：可能不存在
                      readonly createdAt: number  // 只读：编译期禁止后续赋值
                    }
                    ```

                    ⚠️ 两个容易误解的点：

                    1. `email?: string` 是「可以没有」，**不是**「一个可能为 undefined 的字符串」—— 取值前必须判
                    2. `readonly` **只在编译期有效**。绕过方式很多（`as any`、纯 JS 调用、反射），它不是不可变的保证

                    **联合类型才是 TS 价值密度最高的地方**

                    限定「只能是这几个值之一」，非法值在编译期就被拦下：

                    ```ts
                    type Role = "admin" | "user" | "guest"
                    const r: Role = "root"    // ❌ 编译错误
                    ```

                    **判别联合**（discriminated union）是它最有用的形态 —— 用一个共同字段区分几种情况，
                    配合 `switch` 能做**穷尽检查**（漏了一种，TS 会报错）：

                    ```ts
                    type Result =
                      | { state: "loading" }
                      | { state: "ok"; data: string }
                      | { state: "error"; message: string }
                    ```
                    注意 `case "ok"` 里**不需要**任何类型断言 —— TS 已经知道这时 `data` 一定存在。

                    这就是「让非法状态无法表示」：`{ state: "ok" }` 却不带 `data` 的组合，**从类型上就写不出来**。
                    比起「用几个 boolean 标记状态、靠注释约定它们互斥」的老办法，这类错误根本不会发生。
                    """,
                    """
                    // 判别联合：三态互斥，非法组合写不出来
                    type Result =
                      | { state: "loading" }
                      | { state: "ok"; data: string }
                      | { state: "error"; message: string };

                    function render(r: Result): string {
                      switch (r.state) {
                        case "loading": return "加载中…";
                        case "ok":      return `成功：${r.data}`;      // 收窄后可直接用 data
                        case "error":   return `失败：${r.message}`;
                      }
                    }

                    const list: Result[] = [
                      { state: "loading" },
                      { state: "ok", data: "42 条记录" },
                      { state: "error", message: "超时" },
                    ];

                    for (const r of list) console.log(render(r));
                    """,
                    "typescript",
                    List.of(
                            quiz("判别联合里，`{ state: \"ok\" }` 不带 `data` 为什么编译不过？",
                                    List.of("因为 state 是只读的", "因为 ok 这一支声明了 data 是必需的", "因为缺少 interface", "其实能编译过"), 1,
                                    "判别联合把「状态」和「该状态下必须携带的数据」绑在一起。`ok` 分支声明了 "
                                            + "`data: string`，所以状态与数据不匹配的组合天然非法 —— 这正是它的价值："
                                            + "把运行时才发现的错误提前到编译期。"),
                            quiz("`readonly` 修饰的属性能保证运行时不可改吗？",
                                    List.of("能，会变成 getter", "不能，它只是编译期约束", "能，会自动 Object.freeze", "取决于 strict 模式"), 1,
                                    "`readonly` 只参与类型检查，不产生任何运行时代码。要真正的不可变得用 `Object.freeze`；"
                                            + "`as const` 也主要在类型层面收紧。"))),

            lesson("ts-03", "泛型：类型的「参数」", 20,
                    """
                    泛型解决的问题是：**一段逻辑要处理多种类型，同时保持类型之间的关系**。

                    没有泛型时只能退回 `any`，一写就丢了类型关系：

                    ```ts
                    function firstAny(arr: any[]): any { return arr[0] }
                    const x = firstAny([1, 2, 3])   // x 是 any —— 后面全失去检查
                    ```

                    把「元素类型」变成参数，关系就保住了：

                    ```ts
                    function first<T>(arr: T[]): T | undefined { return arr[0] }
                    const x = first([1, 2, 3])      // 推断为 number | undefined
                    const y = first(["a", "b"])     // 推断为 string | undefined
                    ```

                    类比一下：**普通参数是「值的变量」，泛型是「类型的变量」**。`T` 在函数里就是一个待定的类型。

                    **约束：`extends`**

                    不加约束时 `T` 可以是任何类型，所以**访问不了它的任何属性**。用 `extends` 划定底线：

                    ```ts
                    function pluck<T, K extends keyof T>(items: T[], key: K): T[K][] {
                      return items.map((it) => it[key])
                    }
                    ```
                    `K extends keyof T` 保证传进来的 key 确实存在于 T 上 —— 打错的字段名会被直接拦下。

                    **什么时候不该用泛型**

                    不要把泛型当装饰。下面第一行是噪声：

                    ```ts
                    function double<T extends number>(n: T): T { return (n * 2) as T }   // ❌ 白绕一圈
                    function double(n: number): number { return n * 2 }                  // ✅
                    ```
                    只有「类型之间需要保持关联」时泛型才有意义（入参类型 → 出参类型、容器元素的类型等）。
                    """,
                    """
                    // 泛型：保持输入与输出的类型关系
                    function first<T>(arr: T[]): T | undefined {
                      return arr[0];
                    }

                    // 约束：K 必须是 T 的键 —— 写错字段名会在编译期被拦下
                    function pluck<T, K extends keyof T>(items: T[], key: K): T[K][] {
                      return items.map((it) => it[key]);
                    }

                    const users = [
                      { id: 1, name: "Alice" },
                      { id: 2, name: "Bob" },
                    ];

                    console.log(first([10, 20, 30]));   // 10
                    console.log(pluck(users, "name"));  // ["Alice","Bob"]
                    console.log(pluck(users, "id"));    // [1,2]
                    // pluck(users, "age");             // ❌ 编译错误：age 不是 User 的键
                    """,
                    "typescript",
                    List.of(
                            quiz("`function f<T>(x: T)` 里为什么不能直接写 `x.length`？",
                                    List.of("因为 T 是只读的", "因为没有约束时 T 可以是任何类型，不保证有 length", "因为必须先转成 any", "其实可以"), 1,
                                    "泛型的默认约束是「任意类型」，编译期无法保证它有 `length`。要访问就得加约束，"
                                            + "比如 `<T extends { length: number }>` 或 `<T extends string | unknown[]>`。"),
                            quiz("哪个场景用泛型是**有意义**的？",
                                    List.of("给只接受 number 的函数套上 <T>", "写一个「取数组第一项」并保持返回类型与元素类型一致", "让代码看起来更专业", "给类型起别名"), 1,
                                    "泛型的价值是**保持类型之间的关联** —— 入参元素类型与返回类型挂钩就是典型。"
                                            + "给固定类型的函数套 `<T>` 只会增加阅读成本，属于噪声。"))));

    // --------------------------------------------------------------------------------
    // Lua —— 新增语言（此前 RUNNERS 里已有 fengari 方案，但没有 track）
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> LUA_LESSONS = List.of(
            lesson("lua-01", "变量、1-based 索引与唯一的 table", 15,
                    """
                    Lua 只有**一种**复合数据结构：`table`。数组、字典、对象、模块、命名空间 —— 全是它。

                    ```lua
                    local arr = { 10, 20, 30 }                 -- 数组
                    local map = { name = "Alice", age = 30 }   -- 字典
                    print(arr[1], map.name)                    -- 10    Alice
                    ```

                    ⚠️ **索引从 1 开始**，不是 0。写 `arr[0]` 不报错，只会得到 `nil` ——
                    这类错误不崩不响、静默拿到空值，是 Lua 最常见的 bug 来源。

                    新手必踩的几点：

                    1. **默认是全局变量**。不写 `local` 就丢进全局表 `_G`：除了慢（全局是哈希查找、
                       local 是寄存器），还会泄漏到别的模块。**养成默认写 local 的习惯**
                    2. `#t` 取的是**边界**，不是「元素个数」。表里有 nil 空洞时返回哪个边界是**未定义**的：

                       ```lua
                       local t = { 1, 2, nil, 4 }
                       print(#t)   -- 可能是 4，也可能是 2 —— 手册明确说不要依赖
                       ```
                    3. table 是**引用**：`local b = a` 之后改 `b` 会连带改 `a`。要拷贝得自己写
                    4. 把键设为 `nil` 就是**删除**：之后既读不到，`pairs` 也遍历不到
                    5. 字符串连接是 `..`（两个点），不是 `+`
                    6. `a.b` 与 `a["b"]` **完全等价**，前者只是语法糖
                    """,
                    """
                    -- Lua 的唯一数据结构：table
                    local arr = { 10, 20, 30 }
                    local user = { name = "Alice", age = 30 }

                    print(arr[1], arr[3])         -- 10    30（下标从 1 开始！）
                    print(user.name, user["age"]) -- Alice    30（两种写法等价）

                    -- 把键设为 nil == 删除
                    user.age = nil
                    print(user.age)               -- nil

                    -- 引用语义：b 和 a 是同一张表
                    local a = { 1, 2 }
                    local b = a
                    b[1] = 99
                    print(a[1])                   -- 99（a 也被改了）

                    -- 连接用 .. 而不是 +
                    print("共 " .. #arr .. " 项")  -- 共 3 项
                    """,
                    "lua",
                    List.of(
                            quiz("`local t = {10, 20, 30}` 里 30 的索引是？",
                                    List.of("0", "1", "2", "3"), 3,
                                    "Lua 下标从 **1** 开始：10 是 t[1]、20 是 t[2]、30 是 t[3]。"
                                            + "写成 t[0] 不会报错，只会得到 nil —— 不崩不响，所以最难查。"),
                            quiz("`#t`（长度运算符）在表有 nil 空洞时返回什么？",
                                    List.of("元素的实际个数", "未定义的某个边界值", "报错", "总是返回 0"), 1,
                                    "`#` 返回一个「边界」而非精确计数。`{1, 2, nil, 4}` 可能返回 4、也可能返回 2，"
                                            + "取决于内部实现 —— 手册明确说这是未定义行为。要精确计数得自己遍历。"))),

            lesson("lua-02", "函数：多返回值、变长参数与闭包", 20,
                    """
                    函数是一等公民 —— 可以赋给变量、当参数传、当返回值返回。

                    ```lua
                    local double = function (x) return x * 2 end
                    local function add(a, b) return a + b end   -- 语法糖，等价写法
                    ```

                    **多返回值**是 Lua 很有特色的能力：

                    ```lua
                    local function stats(t)
                      local sum = 0
                      for _, v in ipairs(t) do sum = sum + v end
                      return sum, sum / #t              -- 一次返回两个
                    end

                    local s, avg = stats({ 1, 2, 3 })    -- s=6, avg=2
                    ```

                    ⚠️ 多返回值只在**最后一个位置**展开，出现在表达式中间会被截断成 1 个：

                    ```lua
                    print(stats({ 1, 2, 3 }))           -- 6     2
                    print(stats({ 1, 2, 3 }), "done")   -- 6     done   ← 只剩第一个！
                    ```
                    想显式拿回全部，用 `table.pack(...)` 打包，或用 `select("#", ...)` 取个数。

                    **变长参数**：`...` 在函数体内可以直接配合 `select` 使用

                    ```lua
                    local function sum(...)
                      local total = 0
                      for i = 1, select("#", ...) do
                        total = total + select(i, ...)
                      end
                      return total
                    end
                    sum(1, 2, 3, 4)   -- 10
                    ```

                    **闭包**与 JS 同理：函数记住定义时所在的作用域。

                    顺带一个 Lua 独有的优化：**尾调用**。写成 `return f(x)`（调用是最后一个动作）时
                    不会增长调用栈 —— 所以递归实现状态机、遍历链式结构时写成尾调用就不会栈溢出。
                    """,
                    """
                    -- 多返回值
                    local function stats(t)
                      local sum = 0
                      for _, v in ipairs(t) do sum = sum + v end
                      return sum, sum / #t
                    end

                    local s, avg = stats({ 1, 2, 3 })
                    print(s, avg)                       -- 6    2

                    -- 出现在表达式中间时只剩第一个（经典坑）
                    print(stats({ 1, 2, 3 }), "done")   -- 6    done

                    -- 变长参数
                    local function sum(...)
                      local total = 0
                      for i = 1, select("#", ...) do
                        total = total + select(i, ...)
                      end
                      return total
                    end
                    print(sum(1, 2, 3, 4))              -- 10

                    -- 闭包：函数记住定义时的作用域
                    local function counter()
                      local n = 0
                      return function () n = n + 1; return n end
                    end
                    local next_ = counter()
                    print(next_(), next_())             -- 1    2
                    """,
                    "lua",
                    List.of(
                            quiz("`print(f(), \"x\")` 里 `f` 返回两个值，最终打印几个？",
                                    List.of("3 个（f 的两个值 + \"x\"）", "2 个（f 的第一个值 + \"x\"）", "报错", "取决于 f 定义在哪"), 1,
                                    "多返回值只在**最后一个位置**展开。`f()` 不在末位 → 被截断成 1 个值。"
                                            + "要全保留就写 `print(\"x\", f())`，或先 `local a, b = f()` 再打印。"),
                            quiz("`local n = 0` 去掉 `local` 变成 `n = 0`，后果是？",
                                    List.of("语法错误", "创建/覆盖全局变量：更慢且会污染其它模块", "完全没有区别", "只在当前函数内可见"), 1,
                                    "Lua 里不加 `local` 就是全局变量（存进 `_G`）。除了哈希查找比寄存器访问慢，"
                                            + "更麻烦的是它会泄漏到整个进程 —— 两个模块撞名就互相踩。所以默认写 local。"))),

            lesson("lua-03", "metatable 与「Lua 怎么做面向对象」", 20,
                    """
                    **Lua 没有 class**。面向对象是拿 `table` + `metatable` 搭出来的。

                    每张 table 可以带一个隐藏的关联表（metatable）。当你访问一个**不存在的键**时，
                    Lua 会去 metatable 的 `__index` 字段继续找 —— 这就是继承的机制。

                    ```lua
                    local Animal = {}
                    Animal.__index = Animal          -- ① 自己不存在的键，去 Animal 找

                    function Animal.new(name)
                      return setmetatable({ name = name }, Animal)
                    end

                    function Animal:speak()          -- ② 冒号：隐含 self 参数
                      return self.name .. " 叫了一声"
                    end

                    local a = Animal.new("猫")
                    print(a:speak())                 -- 猫 叫了一声
                    ```
                    关键在于 `__index = Animal`：`a:speak()` 时 `a` 自己没有 `speak`，
                    于是沿 `__index` 找到 `Animal.speak`。

                    **点与冒号的区别**（新手最大的坑）：

                    ```lua
                    obj.method(obj, arg)   -- 点：自己把 self 传进去
                    obj:method(arg)        -- 冒号：自动把 obj 当第一个参数
                    ```
                    定义用冒号（`function Animal:speak()`），函数体里就能直接用 `self`；
                    定义用点，就得自己写 `function Animal.speak(self)`。**两边必须配套**。

                    继承 = 让子类的 `__index` 指向父类：

                    ```lua
                    local Dog = setmetatable({}, { __index = Animal })
                    Dog.__index = Dog
                    -- Dog 的实例先查自己 → 再查 Dog → 再查 Animal
                    ```

                    除 `__index` 外常用的元方法：`__add`（重载 +）、`__tostring`（print 时显示什么）、
                    `__call`（让 table 能像函数一样调用）、`__newindex`（拦截赋值）。
                    它们共同构成 Lua 的元编程能力 —— 很多 Lua 库的「魔法」都来自这里。
                    """,
                    """
                    -- Lua 的 OOP：table + metatable.__index
                    local Animal = {}
                    Animal.__index = Animal

                    function Animal.new(name)
                      return setmetatable({ name = name }, Animal)
                    end

                    function Animal:speak()          -- 冒号 == 隐含 self
                      return self.name .. " 叫了一声"
                    end

                    -- 继承：子类的 __index 指向父类
                    local Dog = setmetatable({}, { __index = Animal })
                    Dog.__index = Dog
                    function Dog.new(name) return setmetatable({ name = name }, Dog) end
                    function Dog:fetch() return self.name .. " 叼回了球" end

                    local a = Animal.new("猫")
                    local d = Dog.new("旺财")

                    print(a:speak())   -- 猫 叫了一声
                    print(d:fetch())   -- 旺财 叼回了球
                    print(d:speak())   -- 旺财 叫了一声（从 Animal 继承来的）

                    -- 元方法：让 table 能像函数一样调用
                    local callable = setmetatable({}, { __call = function (_, x) return x * 2 end })
                    print(callable(21))  -- 42
                    """,
                    "lua",
                    List.of(
                            quiz("`a:speak()` 能找到 `Animal.speak`，靠的是什么？",
                                    List.of("Lua 会自动搜索所有 table", "a 的 metatable 的 __index 指向 Animal", "speak 是全局函数", "只是因为用了冒号语法"), 1,
                                    "访问 `a.speak` 时 a 自己没有这个键 → Lua 转去查 a 的 metatable 的 `__index`，"
                                            + "那里是 Animal → 命中 `Animal.speak`。这就是原型链式继承的全部机制。"),
                            quiz("定义写 `function Animal.speak(self)`、调用写 `a:speak()`，结果是？",
                                    List.of("语法错误", "正常工作（冒号等价于把 a 当第一个参数传进去）", "self 会是 nil", "a 会被重复传两次"), 1,
                                    "`a:speak()` 只是 `Animal.speak(a)` 的语法糖。定义用点+显式 self、调用用冒号，"
                                            + "两者是配套的。真正会出错的是反过来——定义用冒号（已隐含 self）却用点调用。"))));

    // --------------------------------------------------------------------------------
    // Python —— 同构，节选三门语言作为「完整流程」的示范
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> PY_LESSONS = List.of(
            lesson("py-01", "变量、类型与 f-string", 15,
                    """
                    Python 不需要声明类型，赋值即创建变量：

                    ```python
                    name = "Alice"
                    age = 30
                    ratio = 0.85
                    ok = True
                    ```

                    Python 是**强类型**的：`"1" + 1` 会直接报 TypeError，不会像 JS 那样偷偷转换。
                    这其实是好事 —— 错误在出现的地方就暴露了。

                    格式化字符串一律用 f-string（Python 3.6+），比 `%` 和 `.format()` 都清晰：

                    ```python
                    print(f"{name} 今年 {age} 岁")
                    print(f"比率 {ratio:.2%}")      # 保留两位小数的百分比
                    ```

                    数字运算有个新手必知点：`/` 永远是浮点除法，整除要用 `//`。

                    ```python
                    7 / 2   # 3.5
                    7 // 2  # 3
                    7 % 2   # 1
                    ```
                    """,
                    """
                    name = "Alice"
                    age = 30
                    ratio = 0.85

                    print(f"{name} 今年 {age} 岁")          # Alice 今年 30 岁
                    print(f"比率 {ratio:.2%}")               # 比率 85.00%

                    print(7 / 2)    # 3.5   浮点除
                    print(7 // 2)   # 3     整除
                    print(7 % 2)    # 1     取余

                    # 强类型：不会隐式转换
                    try:
                        print("1" + 1)
                    except TypeError as e:
                        print("TypeError:", e)
                    """,
                    "python",
                    List.of(
                            quiz("`7 // 2` 的结果是？",
                                    List.of("3.5", "3", "4", "报错"), 1,
                                    "`//` 是整除（向下取整），`/` 才是浮点除法。"
                                            + "对负数要留意：`-7 // 2` 是 `-4`（向下取整，不是截断）。"),
                            quiz("Python 里 `\"1\" + 1` 会怎样？",
                                    List.of("得到 \"11\"", "得到 2", "抛 TypeError", "得到 \"2\""), 2,
                                    "Python 是强类型语言，不会把数字隐式转成字符串。要拼接得显式写 `\"1\" + str(1)`。"
                                            + "这正是它和 JavaScript 的一个重要差别。"))),

            lesson("py-02", "列表推导式", 20,
                    """
                    列表推导式是 Python 最有辨识度的语法，用一行代替 for 循环 + append：

                    ```python
                    squares = [n * n for n in range(5)]        # [0, 1, 4, 9, 16]
                    evens = [n for n in range(10) if n % 2 == 0]
                    ```

                    结构是：`[表达式 for 变量 in 可迭代对象 if 条件]`。
                    条件可以省略，也可以有多个。

                    遇到嵌套循环时它会变难读，这时宁可写回普通 for：

                    ```python
                    # 可读性差，别这么写
                    flat = [x for row in matrix for x in row]

                    # 换成显式循环更清楚
                    flat = []
                    for row in matrix:
                        for x in row:
                            flat.append(x)
                    ```

                    同样还有字典推导式和集合推导式：

                    ```python
                    {k: v for k, v in pairs}
                    {x % 3 for x in range(10)}
                    ```

                    注意：**生成器表达式**用的是圆括号，它不会立刻算出整个列表，而是惰性求值 ——
                    数据量大时省内存。
                    """,
                    """
                    squares = [n * n for n in range(5)]
                    print(squares)                                  # [0, 1, 4, 9, 16]

                    evens = [n for n in range(10) if n % 2 == 0]
                    print(evens)                                    # [0, 2, 4, 6, 8]

                    # 字典推导式
                    words = ["apple", "banana", "cherry"]
                    lengths = {w: len(w) for w in words}
                    print(lengths)                                  # {'apple': 5, ...}

                    # 生成器：不立刻算，省内存
                    lazy = (n * n for n in range(1_000_000))
                    print(next(lazy))                               # 0
                    """,
                    "python",
                    List.of(
                            quiz("`[n for n in range(10) if n % 2 == 0]` 得到什么？",
                                    List.of("[1,3,5,7,9]", "[0,2,4,6,8]", "[0,1,2,...,9]", "报错"), 1,
                                    "`if` 是筛选条件，只保留余数为 0 的偶数（range(10) 从 0 开始，所以含 0）。"),
                            quiz("生成器表达式和列表推导式的主要差别是？",
                                    List.of("语法不同但没有实际差别", "生成器惰性求值，不占整块内存", "生成器更快但结果不能遍历", "生成器只能用于数字"), 1,
                                    "`(...)` 是生成器：不会一次算出所有元素，用 next/for 时才逐个产生。"
                                            + "代价是**只能遍历一次**，且不能按下标取。"))),

            lesson("py-03", "函数：默认参数与可变参数", 20,
                    """
                    Python 函数的参数很灵活：

                    ```python
                    def greet(name, greeting="你好"):     # 默认参数
                        return f"{greeting}，{name}"

                    greet("Alice")                       # 你好，Alice
                    greet("Bob", "Hello")                # Hello，Bob
                    ```

                    **默认参数只求值一次**，所以绝对不能写可变对象：

                    ```python
                    # ❌ 这个 [] 在所有调用之间共享
                    def add(item, bucket=[]):
                        bucket.append(item)
                        return bucket

                    # ✅ 用 None 占位
                    def add(item, bucket=None):
                        if bucket is None:
                            bucket = []
                        bucket.append(item)
                        return bucket
                    ```

                    这是 Python 最经典的坑之一，面试也常问。

                    可变参数用 `*args`（收集成元组）和 `**kwargs`（收集成字典）：

                    ```python
                    def total(*nums):
                        return sum(nums)

                    total(1, 2, 3)      # 6

                    def show(**info):
                        for k, v in info.items():
                            print(k, v)

                    show(name="Alice", age=30)
                    ```
                    """,
                    """
                    def greet(name, greeting="你好"):
                        return f"{greeting}，{name}"

                    print(greet("Alice"))            # 你好，Alice
                    print(greet("Bob", "Hello"))     # Hello，Bob

                    # ❌ 经典坑：默认参数只求值一次，这个 [] 会跨调用共享
                    def bad(item, bucket=[]):
                        bucket.append(item)
                        return bucket

                    print(bad(1))   # [1]
                    print(bad(2))   # [1, 2]  ← 不是 [2]！

                    # ✅ 正确写法
                    def good(item, bucket=None):
                        if bucket is None:
                            bucket = []
                        bucket.append(item)
                        return bucket

                    print(good(1))  # [1]
                    print(good(2))  # [2]

                    def total(*nums):
                        return sum(nums)

                    print(total(1, 2, 3))            # 6
                    """,
                    "python",
                    List.of(
                            quiz("`def f(x, bucket=[])` 这种写法的问题是什么？",
                                    List.of("性能差", "这个 [] 在所有调用之间共享", "语法错误", "不能传第二个参数"), 1,
                                    "默认值在**函数定义时**求值一次并一直复用同一个对象，于是每次 append 都堆在同一个列表里。"
                                            + "正确做法是用 `None` 占位，函数体内再新建。"),
                            quiz("`**kwargs` 收集到的数据类型是？",
                                    List.of("元组", "列表", "字典", "集合"), 2,
                                    "`*args` 收集成元组，`**kwargs` 收集成字典（键是参数名）。"
                                            + "两者可以组合使用，但顺序必须是普通参数 → *args → **kwargs。"))));

    // --------------------------------------------------------------------------------
    // SQL
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> SQL_LESSONS = List.of(
            lesson("sql-01", "SELECT 与 WHERE", 15,
                    """
                    SQL 的书写顺序和**执行顺序**不一样，这点先记住能省掉很多困惑：

                    写法：`SELECT ... FROM ... WHERE ... GROUP BY ... ORDER BY ... LIMIT`
                    执行：`FROM → WHERE → GROUP BY → SELECT → ORDER BY → LIMIT`

                    所以 `WHERE` 里**不能**用 `SELECT` 起的别名 —— 执行到 WHERE 时别名还不存在。

                    ```sql
                    SELECT name, price
                    FROM products
                    WHERE price > 100 AND category = 'book'
                    ORDER BY price DESC
                    LIMIT 10;
                    ```

                    几个高频陷阱：

                    - `NULL` 不能用 `= NULL` 判断，要用 `IS NULL` / `IS NOT NULL`
                    - `LIKE` 的 `%` 匹配任意长度，`_` 匹配一个字符
                    - 字符串要用单引号（双引号在不同数据库里含义不同，可能是标识符）

                    ```sql
                    SELECT * FROM users WHERE deleted_at IS NULL;
                    SELECT * FROM users WHERE name LIKE '张%';
                    ```
                    """,
                    """
                    CREATE TABLE products (id INTEGER, name TEXT, price REAL, category TEXT);
                    INSERT INTO products VALUES
                      (1, 'Python入门', 59, 'book'),
                      (2, '机械键盘', 399, 'hardware'),
                      (3, 'JS高级程序设计', 128, 'book'),
                      (4, '显示器', 1299, 'hardware');

                    -- 写法顺序 ≠ 执行顺序：FROM → WHERE → SELECT → ORDER BY → LIMIT
                    SELECT name, price
                    FROM products
                    WHERE price > 100 AND category = 'book'
                    ORDER BY price DESC;

                    -- NULL 必须用 IS NULL 判断，= NULL 永远不成立
                    SELECT name FROM products WHERE category IS NOT NULL;

                    -- LIKE：% 匹配任意长度，_ 匹配单个字符
                    SELECT name FROM products WHERE name LIKE '%入门';
                    """,
                    "sql",
                    List.of(
                            quiz("`WHERE price = NULL` 会返回什么？",
                                    List.of("所有 price 为 NULL 的行", "空结果集", "语法错误", "所有行"), 1,
                                    "NULL 表示「未知」，任何与它的比较都得到未知（不是 true），所以永远筛不出行。"
                                            + "必须写 `IS NULL`。"),
                            quiz("为什么不能在 WHERE 里用 SELECT 起的列别名？",
                                    List.of("因为别名太长", "因为 WHERE 比 SELECT 先执行", "因为大部分数据库不支持别名", "因为别名只在 ORDER BY 有效"), 1,
                                    "执行顺序是 FROM → WHERE → GROUP BY → SELECT → ORDER BY。"
                                            + "WHERE 执行时还没轮到 SELECT，别名自然还不存在；ORDER BY 在 SELECT 之后，所以能用。"))),

            lesson("sql-02", "聚合函数与 GROUP BY", 20,
                    """
                    聚合函数把多行压成一个值：`COUNT` / `SUM` / `AVG` / `MAX` / `MIN`。

                    ```sql
                    SELECT COUNT(*) AS total, AVG(price) AS avg_price
                    FROM products;
                    ```

                    `GROUP BY` 则是「分组后再聚合」——先按某列切桶，每个桶算一次聚合值：

                    ```sql
                    SELECT category, COUNT(*) AS cnt, AVG(price) AS avg_price
                    FROM products
                    GROUP BY category;
                    ```

                    有一条必须记住的规则：**SELECT 里出现的非聚合列，必须出现在 GROUP BY 里**。
                    否则结果是不确定的（MySQL 老版本会放行并随机取一行，这是很多"数据对不上"的根源）。

                    ```sql
                    -- ❌ name 既不在 GROUP BY 也不是聚合函数
                    SELECT category, name, COUNT(*) FROM products GROUP BY category;

                    -- ✅ 想要每组里的某一行，用聚合函数明确取
                    SELECT category, MAX(price) AS max_price FROM products GROUP BY category;
                    ```

                    分组后还想筛选用 `HAVING`，不能用 `WHERE`（WHERE 在分组前执行）：

                    ```sql
                    SELECT category, COUNT(*) AS cnt
                    FROM products
                    GROUP BY category
                    HAVING cnt >= 2;
                    ```

                    另外 `COUNT(*)` 和 `COUNT(col)` 不一样：后者**不统计 NULL**。
                    """,
                    """
                    CREATE TABLE products (id INTEGER, name TEXT, price REAL, category TEXT);
                    INSERT INTO products VALUES
                      (1, 'Python入门', 59, 'book'),
                      (2, '机械键盘', 399, 'hardware'),
                      (3, 'JS高级程序设计', 128, 'book'),
                      (4, '显示器', 1299, 'hardware'),
                      (5, 'SQL必知必会', NULL, 'book');

                    -- 全表聚合
                    SELECT COUNT(*) AS total, AVG(price) AS avg_price FROM products;

                    -- 分组聚合
                    SELECT category, COUNT(*) AS cnt, MAX(price) AS max_price
                    FROM products
                    GROUP BY category;

                    -- HAVING 筛分组结果（WHERE 在分组前、HAVING 在分组后）
                    SELECT category, COUNT(*) AS cnt
                    FROM products
                    GROUP BY category
                    HAVING cnt >= 2;

                    -- COUNT(*) 数行数；COUNT(price) 跳过 NULL
                    SELECT COUNT(*) AS rows_all, COUNT(price) AS rows_with_price FROM products;
                    """,
                    "sql",
                    List.of(
                            quiz("分组后想筛选分组结果，应该用哪个？",
                                    List.of("WHERE", "HAVING", "ORDER BY", "LIMIT"), 1,
                                    "WHERE 在 GROUP BY **之前**执行，筛的是原始行；HAVING 在分组之后执行，筛的是分组结果。"
                                            + "要在分组后按聚合值筛选，只能 HAVING。"),
                            quiz("表里 5 行，其中 1 行的 price 是 NULL。`COUNT(*)` 和 `COUNT(price)` 分别是？",
                                    List.of("5 和 5", "5 和 4", "4 和 5", "5 和 0"), 1,
                                    "`COUNT(*)` 数行数（5）；`COUNT(某列)` 只数该列**非 NULL** 的行（4）。"
                                            + "这个差别是「统计口径对不上」的常见来源。"))),

            lesson("sql-03", "JOIN：把两张表连起来", 25,
                    """
                    JOIN 用来把多张表按关联列拼在一起。最常用的是 `INNER JOIN` 和 `LEFT JOIN`：

                    - `INNER JOIN`：只保留**两边都匹配上**的行
                    - `LEFT JOIN`：保留左表全部行，右表没匹配上的补 NULL

                    这个区别在一对多关系里非常关键。举例：查「所有用户及其订单数」，
                    用 INNER JOIN 会把**没有订单的用户整个丢掉**：

                    ```sql
                    -- ❌ 没下过单的用户不会出现在结果里
                    SELECT u.name, COUNT(o.id) AS cnt
                    FROM users u
                    INNER JOIN orders o ON o.user_id = u.id
                    GROUP BY u.id, u.name;

                    -- ✅ 用 LEFT JOIN 才能得到 0
                    SELECT u.name, COUNT(o.id) AS cnt
                    FROM users u
                    LEFT JOIN orders o ON o.user_id = u.id
                    GROUP BY u.id, u.name;
                    ```

                    注意上面用的是 `COUNT(o.id)` 而不是 `COUNT(*)`：
                    左连接没匹配上时右表全是 NULL，`COUNT(*)` 会数成 1（把那一行 NULL 也算上），
                    而 `COUNT(o.id)` 数的是非 NULL，才会正确得到 0。**这是左连接最常见的错误。**

                    别名（`users u`）能让语句短很多，多表连接时基本必用。
                    """,
                    """
                    CREATE TABLE users (id INTEGER, name TEXT);
                    CREATE TABLE orders (id INTEGER, user_id INTEGER, amount REAL);
                    INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Carol');
                    INSERT INTO orders VALUES (101, 1, 30), (102, 1, 50), (103, 2, 20);

                    -- INNER JOIN：Carol 没有订单，直接消失
                    SELECT u.name, COUNT(o.id) AS cnt
                    FROM users u
                    INNER JOIN orders o ON o.user_id = u.id
                    GROUP BY u.id, u.name;

                    -- LEFT JOIN + COUNT(o.id)：Carol 才是 0
                    -- ⚠️ 这里若写 COUNT(*) ，Carol 会被数成 1（那行全是 NULL 也算一行）
                    SELECT u.name, COUNT(o.id) AS cnt
                    FROM users u
                    LEFT JOIN orders o ON o.user_id = u.id
                    GROUP BY u.id, u.name;
                    """,
                    "sql",
                    List.of(
                            quiz("要查「所有用户及其订单数」，包括没有订单的用户，应该用？",
                                    List.of("INNER JOIN", "LEFT JOIN", "CROSS JOIN", "UNION"), 1,
                                    "INNER JOIN 只保留两边都匹配的行，没下过单的用户会被整行丢掉。"
                                            + "LEFT JOIN 保留左表所有行，右表缺失时补 NULL。"),
                            quiz("上面 LEFT JOIN 的例子中，为什么用 `COUNT(o.id)` 而不是 `COUNT(*)`？",
                                    List.of("性能更好", "COUNT(*) 会把没匹配行的那一行也数成 1", "COUNT(o.id) 更短", "两者完全等价"), 1,
                                    "左连接没匹配上时右表字段全是 NULL，但**那依然是一行**，`COUNT(*)` 会数成 1。"
                                            + "`COUNT(o.id)` 只数非 NULL，才能得到正确的 0。"))));

    // --------------------------------------------------------------------------------
    // C / C++ —— heavy 档：内容先写全，运行器（Clang→WASM，首屏 70MB+）待接
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> CPP_LESSONS = List.of(
            lesson("c-01", "指针与内存", 20,
                    """
                    C 最核心的概念是**指针** —— 一个存着内存地址的变量。

                    ```c
                    int x = 42;
                    int *p = &x;            // & 取地址：p 现在「指向 x」
                    printf("%d\\n", *p);    // * 解引用：顺着 p 找到那块内存，读出 42
                    *p = 100;               // 顺着 p 写回去 → x 也变成 100
                    ```

                    理解的关键是把变量看成**内存里的一块地**：`x` 是那块地，`&x` 是它的门牌号，
                    `p` 是记着门牌号的纸条。

                    **栈与堆**

                    ```c
                    int  a = 10;                     // 栈：离开作用域自动回收
                    int *b = malloc(sizeof(int));    // 堆：手动申请
                    *b = 20;
                    free(b);                         // 必须手动释放，不 free 就是泄漏
                    ```

                    **数组名会「退化成指针」**，这解释了几个诡异现象：

                    ```c
                    int arr[5] = {1, 2, 3, 4, 5};
                    printf("%d\\n", *arr);        // 1 —— arr[0] 就是 *(arr + 0)
                    printf("%d\\n", *(arr + 2));  // 3 —— 指针算术加的是「元素个数」，不是字节数
                    ```

                    ⚠️ 但 `sizeof(arr)` **只在声明所在的同一作用域里**才是整个数组大小。
                    一旦把数组传进函数（传进去的是指针），`sizeof` 就只得到指针大小：

                    ```c
                    void f(int arr[]) {
                      printf("%zu\\n", sizeof(arr));   // 8，不是 20 —— 这里的 arr 已是指针
                    }
                    ```
                    **要传长度就得额外传一个参数**，没有别的办法。

                    新手必踩的点：

                    1. **未初始化的指针不能解引用**：`int *p; *p = 1;` 会写到随机地址（段错误）
                    2. `free` 之后不能再用：`free(p); *p = 1;` 是 use-after-free
                    3. `free` 两次是未定义行为
                    4. 打印指针要用 `%p`，用 `%d` 是错的（且编译器多半不报错）
                    """,
                    """
                    #include <stdio.h>
                    #include <stdlib.h>

                    int main(void) {
                      int x = 42;
                      int *p = &x;              // p 指向 x
                      printf("%d\\n", *p);      // 42
                      *p = 100;                 // 顺着指针写回去
                      printf("%d\\n", x);       // 100 —— x 真的被改了

                      // 堆：手动申请、必须手动释放
                      int *heap = malloc(sizeof(int) * 3);
                      heap[0] = 1; heap[1] = 2; heap[2] = 3;
                      printf("%d\\n", heap[1]); // 2

                      // 数组名退化成指针：*(arr + i) 就是 arr[i]
                      int arr[4] = {10, 20, 30, 40};
                      printf("%d\\n", *(arr + 2));    // 30
                      printf("%zu\\n", sizeof(arr));  // 16 —— 在声明作用域内才是整个数组

                      free(heap);
                      return 0;
                    }
                    """,
                    "c",
                    List.of(
                            quiz("`int arr[5]` 传进函数后，函数里 `sizeof(arr)` 得到什么？",
                                    List.of("20", "8（指针大小）", "5", "编译错误"), 1,
                                    "数组作参数时会**退化成指针**，函数里拿到的只是地址，`sizeof` 自然只给指针大小"
                                            + "（64 位下 8 字节）。要长度必须另传一个参数 —— C 最常见的陷阱之一。"),
                            quiz("`free(p)` 之后继续写 `*p = 1` 会怎样？",
                                    List.of("正常写入", "未定义行为（可能静默损坏其它数据）", "编译器一定报错", "会自动重新分配"), 1,
                                    "释放后那块内存已交还分配器，可能马上被另一次 `malloc` 拿去用。再写就是 "
                                            + "use-after-free —— 这类 bug 往往**不在出错点崩**，而是很久之后在别处崩，极难定位。"))),

            lesson("c-02", "字符串与内存管理", 20,
                    """
                    **C 没有字符串类型**。所谓字符串就是一个 `char` 数组，以 `\\0`（数值 0 的字符）结尾。

                    ```c
                    char s[] = "hi";              // 实际是 {'h', 'i', '\\0'} —— 3 个字节
                    printf("%zu\\n", sizeof(s));  // 3（含结尾符）
                    printf("%zu\\n", strlen(s));  // 2（不含结尾符）
                    ```
                    `sizeof` 与 `strlen` 差 1（前者含结尾符）。**这个 1 字节的差别在拷贝时就会变成溢出**。

                    **手动管理内存**是 C 的另一半：

                    ```c
                    char *buf = malloc(64);       // 申请 64 字节
                    if (!buf) return -1;          // ⚠️ 必须判空：申请可能失败
                    strcpy(buf, "hello");
                    free(buf);                    // 与 malloc 配对，且只能在这个原始地址上 free
                    ```
                    `free(buf + 1)` 是错的 —— 它不是 `malloc` 返回的地址。

                    **缓冲区溢出**是 C 最危险的一类 bug：`strcpy` **不检查目标够不够大**。

                    ```c
                    char dst[4];
                    strcpy(dst, "hello");   // 💥 写 6 字节进 4 字节 → 覆盖相邻内存
                    ```
                    安全做法是 `snprintf`（截断并保证以 `\\0` 结尾）：

                    ```c
                    snprintf(dst, sizeof(dst), "%s", "hello");   // 最多写 4 字节（含 \\0）
                    ```
                    ⚠️ `strncpy` 有个坑：源串长度 ≥ n 时它**不补结尾 `\\0`** —— 所以更推荐 `snprintf`。

                    新手必踩的点：

                    1. `malloc(10 * sizeof(int))` 忘了乘 `sizeof` → 只申请了 10 字节
                    2. 传进函数后 `sizeof` 不再是数组大小（见上一节）
                    3. **返回局部数组**：函数返回后栈内存就失效了 → 必须 `malloc` 或让调用方传缓冲区
                    4. 每个 `malloc` 都要先想清楚「谁负责 free、什么时候 free」
                    """,
                    """
                    #include <stdio.h>
                    #include <stdlib.h>
                    #include <string.h>

                    int main(void) {
                      char s[] = "hi";                              // {'h','i','\\0'}
                      printf("%zu %zu\\n", sizeof(s), strlen(s));   // 3 2 —— 差在那个结尾符

                      // 手动管理：申请 → 判空 → 使用 → 释放
                      char *buf = malloc(64);
                      if (!buf) return 1;                 // 内存可能申请失败
                      snprintf(buf, 64, "%s", "hello");   // 有长度上限，不会溢出
                      printf("%s\\n", buf);
                      free(buf);                          // 与 malloc 配对

                      // 安全版「溢出演示」：目标只有 4 字节
                      char small[4];
                      snprintf(small, sizeof(small), "%s", "hello");   // 自动截断成 "hel"
                      printf("%s\\n", small);
                      return 0;
                    }
                    """,
                    "c",
                    List.of(
                            quiz("`char s[] = \"hi\"` 的 `sizeof(s)` 与 `strlen(s)` 分别是？",
                                    List.of("2 和 2", "3 和 2", "2 和 3", "3 和 3"), 1,
                                    "字符串字面量会额外带一个结尾符 `\\0`，所以数组实际占 3 字节；"
                                            + "`strlen` 数到 `\\0` 之前为止，是 2。这个 1 字节的差别在拷贝时就会变成溢出。"),
                            quiz("`strncpy(dst, \"hello\", 5)` 之后，`dst` 一定以 `\\0` 结尾吗？",
                                    List.of("一定", "不一定 —— 源串长度 ≥ n 时不会补结尾符", "取决于编译器", "会报错"), 1,
                                    "`\\0` 只在源串**短于** n 时才补齐（剩余部分填 `\\0`）。源串长度 ≥ n 时 `strncpy` "
                                            + "不保证结尾 —— 这正是它容易被误用的地方，所以推荐用 `snprintf`。"))),

            lesson("c-03", "C++ 的 RAII：为什么 C++ 能少犯内存错", 20,
                    """
                    C++ 保留了 C 的全部能力（包括指针和 `malloc`），但多了一套让内存管理**自动发生**的机制。

                    核心叫 **RAII**（资源获取即初始化）：把资源的生命周期绑在**对象的生命周期**上 ——
                    对象在栈上创建，离开作用域时**自动**调析构函数。

                    ```cpp
                    {
                      std::string s = "hello";     // 构造时分配
                      std::vector<int> v = {1, 2, 3};
                    }                              // 出了这个块：s 和 v 的内存自动释放
                    ```
                    不需要写 `free`，也不会忘 —— 这才是 C++ 比 C 安全的主要来源，
                    而不是「语法更好看」。

                    **同一件事的两种写法**：

                    ```c
                    // C：手动三步，漏一步就泄漏
                    char *buf = malloc(64);
                    strcpy(buf, "hello");
                    free(buf);
                    ```
                    ```cpp
                    // C++：一步，而且异常安全
                    std::string buf = "hello";
                    ```
                    `std::string` 在异常抛出时也能正确释放（栈展开会调析构）—— 手动 `free` 做不到这点，
                    这才是 RAII 真正的价值。

                    **引用 vs 指针**

                    ```cpp
                    int x = 1;
                    int &r = x;      // 引用：x 的别名，必须初始化、不能改指向
                    int *p = &x;     // 指针：可以为空、可以重新指向
                    ```
                    引用的语法更干净（`r` 就当 `x` 用），适合「一定非空」的场景；指针适合「可能为空」。

                    **所有权：`unique_ptr`**

                    ```cpp
                    auto p = std::make_unique<int>(42);   // 独占所有权，不能拷贝
                    // auto q = p;                        // ❌ 编译错误
                    auto q = std::move(p);                // ✅ 移交所有权，此后 p 为空
                    ```
                    `std::move` **不搬运任何数据** —— 它只是把「所有权」转交出去，代价接近零。
                    把 `unique_ptr` 放进容器，就得到「自动释放的一堆对象」，不用再手写循环 `free`。

                    新手必踩的点：

                    1. **`new` 就要 `delete`**，但更该做的是根本不用 `new`（用栈对象或智能指针）
                    2. `delete` 数组要用 `delete[]`，混用是未定义行为
                    3. 悬垂引用：返回局部变量的引用（对象已经没了）
                    4. `std::move` 之后原对象处于「有效但未指明」状态，别再依赖它的值（指针会变空）
                    """,
                    """
                    #include <iostream>
                    #include <memory>
                    #include <string>
                    #include <vector>

                    int main() {
                      // RAII：出作用域自动释放，不需要手写 free/delete
                      {
                        std::string s = "hello";
                        std::vector<int> v = {1, 2, 3};
                        std::cout << s << " " << v.size() << "\\n";   // hello 3
                      }   // 这里 v 与 s 的内存自动释放

                      // 引用是别名，必须初始化
                      int x = 1;
                      int &r = x;
                      r = 9;
                      std::cout << x << "\\n";      // 9

                      // unique_ptr：独占所有权，只能移动不能拷贝
                      auto p = std::make_unique<int>(42);
                      std::cout << *p << "\\n";     // 42
                      auto q = std::move(p);        // 所有权移交，此后 p 为空
                      std::cout << *q << "\\n";     // 42
                      std::cout << (p == nullptr) << "\\n";   // 1
                      return 0;
                    }
                    """,
                    "cpp",
                    List.of(
                            quiz("C++ 的 RAII 为什么在抛异常时也能正确释放内存？",
                                    List.of("因为有垃圾回收", "因为栈展开时会按逆序调析构函数", "因为编译器插入了 free", "不能，异常时一定会泄漏"), 1,
                                    "异常抛出时，栈上已构造的对象会按**构造的逆序**逐个析构（栈展开），资源的释放跟着对象走。"
                                            + "手写 `free` 的代码做不到 —— 抛异常会直接跳过它。"),
                            quiz("`auto q = std::move(p)` 里的 `std::move` 实际做了什么？",
                                    List.of("深拷贝了一份数据", "只移交所有权，不搬运数据（此后 p 为空）", "创建了一个引用", "什么也不做"), 1,
                                    "`std::move` 只是个类型转换（转成右值引用），真正干活的是移动构造：把指针/缓冲区**接过去**，"
                                            + "原对象置空。代价接近零，这就是它比拷贝快的原因。"))));

    // --------------------------------------------------------------------------------
    // Java —— heavy 档：内容先写全，运行器（CheerpJ 不许自托管 / 只到 Java 8）待定
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> JAVA_LESSONS = List.of(
            lesson("java-01", "类型系统、包装类型与 String", 20,
                    """
                    Java 是**静态强类型**语言：每个变量都有确定的类型，不匹配在编译期就报错。

                    **基本类型 vs 包装类型**（Java 特有的分裂）：

                    ```java
                    int a = 1;        // 基本类型：没有方法，存值
                    Integer b = 1;    // 包装类型：是对象，可以为 null
                    ```
                    自动装箱让两者几乎可以互换（`Integer c = a;`），但埋了几个大坑：

                    ⚠️ **`==` 比较包装类型比的是引用，不是值**：

                    ```java
                    Integer x = 1000, y = 1000;
                    System.out.println(x == y);        // false ！不是 true
                    System.out.println(x.equals(y));   // true  ← 这个才是比值
                    ```
                    更要命的是 **`Integer` 有缓存**（-128 ~ 127）：

                    ```java
                    Integer m = 100, n = 100;
                    System.out.println(m == n);    // true   ← 走了缓存，恰好看不出问题
                    Integer p = 1000, q = 1000;
                    System.out.println(p == q);    // false  ← 超出缓存就现原形
                    ```
                    **结论：比较对象一律用 `.equals()`**；`==` 只用来比基本类型。

                    **String 是不可变的**

                    ```java
                    String s = "hello";
                    s.toUpperCase();        // 返回新字符串，s 本身没变
                    System.out.println(s);  // 还是 "hello"
                    ```
                    不可变带来两个后果：① 可以安全共享（字符串常量池就是这么来的）；
                    ② `+` 在循环里拼字符串会创建大量临时对象 —— 那时该用 `StringBuilder`。

                    还有：`null.equals(x)` 会抛空指针，安全写法是把常量写在前面 —— **`"常量".equals(变量)`**。

                    新手必踩的点：

                    1. `==` 比字符串（有时对有时错，取决于是否命中常量池）
                    2. `Integer` 为 `null` 时参与运算 → 自动拆箱 → NPE
                    3. `final` 修饰引用表示「引用不能改指向」，**不是**「对象内容不可变」
                    """,
                    """
                    public class Main {
                      public static void main(String[] args) {
                        // 基本类型 vs 包装类型
                        int a = 1;
                        Integer b = 1;

                        // == 比引用，equals 比值
                        Integer x = 1000, y = 1000;
                        System.out.println(x == y);         // false
                        System.out.println(x.equals(y));    // true

                        // Integer 缓存 -128~127：这段恰好看不出问题
                        Integer m = 100, n = 100;
                        System.out.println(m == n);         // true（命中缓存）

                        // String 不可变
                        String s = "hello";
                        s.toUpperCase();
                        System.out.println(s);              // hello（没变）

                        // 安全的比较：常量写前面，null 也不会 NPE
                        String t = null;
                        System.out.println("hello".equals(t));   // false，而不是抛异常
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("`Integer x = 1000, y = 1000; x == y` 的结果是？",
                                    List.of("true", "false", "编译错误", "抛异常"), 1,
                                    "`==` 比的是引用。1000 超出 `Integer` 缓存范围（-128~127），会各自 new 一个对象 → false。"
                                            + "换成 100 就会是 true（走缓存）—— 所以这个坑「时对时错」，必须统一用 `.equals()`。"),
                            quiz("`String s = \"hello\"; s.toUpperCase();` 之后 `s` 是什么？",
                                    List.of("HELLO", "hello（未变）", "null", "编译错误"), 1,
                                    "`String` 不可变，`toUpperCase()` 返回的是**新对象**，不赋值就丢了。"
                                            + "不可变让字符串能安全共享（常量池），代价是循环里 `+` 拼接会产生大量临时对象 —— 那时用 StringBuilder。"))),

            lesson("java-02", "集合框架", 20,
                    """
                    Java 集合分三个主干接口：

                    | 接口 | 用途 | 常用实现 |
                    |---|---|---|
                    | `List` | 有序、可重复 | `ArrayList`（随机访问快）、`LinkedList` |
                    | `Set` | 去重 | `HashSet`、`LinkedHashSet`（保留插入序） |
                    | `Map` | 键值对 | `HashMap`、`LinkedHashMap` |

                    **怎么选**：日常默认 `ArrayList` —— 随机访问 O(1)、尾部追加也快。
                    `LinkedList` 只在「频繁在头部/中间插入删除」时才有优势，实际项目里很少用。

                    ⚠️ **`HashMap` 的键必须正确实现 `hashCode()` 和 `equals()`**

                    用自定义对象当键时，只重写 `equals`、不重写 `hashCode`，会导致「放进去取不出来」：

                    ```java
                    class Point { int x, y; /* 只写了 equals，没写 hashCode */ }

                    Map<Point, String> map = new HashMap<>();
                    map.put(new Point(1, 2), "a");
                    map.get(new Point(1, 2));    // null ！逻辑上相等，却落在不同的桶
                    ```
                    契约是：**`equals` 相等 ⇒ `hashCode` 必须相等**（反过来不要求）。

                    ⚠️ **泛型擦除**：`List<String>` 的类型信息只在**编译期**存在，运行时统一是 `List`。
                    所以不能 `new T[]`、不能对泛型做 `instanceof` —— 这是 Java 泛型为兼容老代码付出的代价。

                    ⚠️ **遍历时删除元素**必须交给迭代器：

                    ```java
                    for (String s : list) {
                      if (s.isEmpty()) list.remove(s);   // 💥 ConcurrentModificationException
                    }
                    ```
                    增强 for 底层就是迭代器；直接改 list 会让迭代器检测到「被外部修改」而抛错。
                    正确写法：`list.removeIf(s -> s.isEmpty())`（内部走迭代器）。
                    """,
                    """
                    import java.util.*;

                    public class Main {
                      public static void main(String[] args) {
                        // List：有序可重复
                        List<String> list = new ArrayList<>(List.of("a", "b", "a"));
                        System.out.println(list.size());          // 3

                        // Set：去重
                        Set<String> set = new HashSet<>(list);
                        System.out.println(set.size());           // 2

                        // Map：键值对，带默认值取值
                        Map<String, Integer> map = new HashMap<>();
                        map.put("a", 1);
                        System.out.println(map.getOrDefault("z", 0));   // 0

                        // 遍历时删除：removeIf 内部走迭代器，安全
                        List<String> names = new ArrayList<>(List.of("bo", "", "alice"));
                        names.removeIf(String::isEmpty);
                        System.out.println(names);                // [bo, alice]

                        // 泛型擦除：两种不同参数化的 List，运行时是同一个类
                        List<Integer> nums = new ArrayList<>();
                        List<String> strs = new ArrayList<>();
                        System.out.println(nums.getClass() == strs.getClass());   // true
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("自定义类当 `HashMap` 的键，只重写 `equals` 不重写 `hashCode`，会怎样？",
                                    List.of("完全正常工作", "能 put 但 get 不到（哈希不同 → 落在不同的桶）", "编译错误", "抛异常"), 1,
                                    "`HashMap` 先按 `hashCode` 定位桶、再在桶内用 `equals` 比。哈希值不同就根本不在同一个桶，"
                                            + "永远找不到，还会重复插入。契约是「`equals` 相等则 `hashCode` 必须相等」。"),
                            quiz("增强 for 循环里 `list.remove(x)` 为什么抛 `ConcurrentModificationException`？",
                                    List.of("因为多线程并发", "因为增强 for 底层是迭代器，它检测到集合被外部改了", "因为 list 不可变", "只有 HashMap 会这样"), 1,
                                    "增强 for 会被编译成 `Iterator` 遍历。迭代器记着 `modCount`，发现集合被 `list.remove` "
                                            + "直接改了就立刻抛错 —— **单线程也会发生**，与并发无关。替代：`removeIf(...)`。"))),

            lesson("java-03", "面向对象与异常", 20,
                    """
                    **接口 vs 抽象类** —— 最常被问的选择题：

                    | | 接口 interface | 抽象类 abstract class |
                    |---|---|---|
                    | 多继承 | ✅ 可实现多个 | ❌ 只能继承一个 |
                    | 实例字段 | ❌ 只能放常量 | ✅ 可以 |
                    | 方法实现 | Java 8+ 有 default 方法 | 可以有 |
                    | 何时用 | 定义「能做什么」（能力契约） | 共享「是什么」的公共实现 |

                    经验法则：**优先用接口**。只有子类之间确有共享状态/实现要复用时，才考虑抽象类。

                    **多态的口诀：编译看左边，运行看右边**

                    ```java
                    Animal a = new Dog();
                    a.speak();     // 调的是 Dog 的实现（运行期按实际类型决定）
                    // a.fetch();  // ❌ 编译错误：编译期只看 Animal 有没有这个方法
                    ```

                    ⚠️ **`@Override` 一定要写**。少了它，想重写的方法一旦签名写错
                    （比如参数类型不对），编译器不会报错 —— 你会得到一个**永远不会被调用**的方法，
                    排查起来非常费劲。加上注解，写错立刻就报。

                    **异常分两类**

                    ```java
                    // 受检异常（checked）：必须处理，否则编译不过
                    try { readFile(); } catch (IOException e) { /* ... */ }

                    // 非受检异常（RuntimeException 及其子类）：编译器不强制处理
                    int x = 1 / 0;   // ArithmeticException，运行时才炸
                    ```
                    设计上的取舍：**受检异常**适合「调用方能合理恢复」的场景（文件不存在可以重试）；
                    **非受检异常**适合「程序 bug」（空指针、数组越界）—— 这类该改代码，而不是到处 catch。

                    **资源一律用 `try-with-resources`**

                    ```java
                    try (var in = new FileInputStream("a.txt")) {
                      // 用完自动 close，即使抛异常也会关
                    }
                    ```
                    手写 `finally { in.close(); }` 容易漏，而且 try 里抛异常时 `close` 自己也可能抛 ——
                    `try-with-resources` 连「抑制异常」都替你处理了。
                    """,
                    """
                    import java.io.*;

                    class Animal {
                      String name;
                      Animal(String name) { this.name = name; }
                      String speak() { return name + " 叫了一声"; }
                    }

                    class Dog extends Animal {
                      Dog(String name) { super(name); }
                      @Override
                      String speak() { return name + " 汪了一声"; }   // 重写
                    }

                    public class Main {
                      public static void main(String[] args) {
                        // 多态：编译看左边（Animal），运行看右边（Dog）
                        Animal a = new Dog("旺财");
                        System.out.println(a.speak());      // 旺财 汪了一声

                        // 非受检异常不强制处理
                        try {
                          int x = 1 / 0;
                        } catch (ArithmeticException e) {
                          System.out.println("捕获：" + e.getMessage());   // / by zero
                        }

                        // try-with-resources：自动关闭
                        try (var w = new StringWriter()) {
                          w.write("hi");
                          System.out.println(w.toString());   // hi
                        } catch (IOException e) {
                          System.out.println("这里不会发生");
                        }
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("`Animal a = new Dog(); a.speak();` 调用的是哪个 `speak`？",
                                    List.of("Animal 的", "Dog 的（运行期按实际类型决定）", "编译错误", "两个都会调"), 1,
                                    "这就是多态：**编译看左边**（`a` 声明为 Animal，所以只能调用 Animal 有的方法），"
                                            + "**运行看右边**（实际对象是 Dog，所以调 Dog 重写后的版本）。"),
                            quiz("什么时候该用受检异常（checked）？",
                                    List.of("所有异常都该用受检", "调用方有可能合理恢复的场景（如文件不存在可重试）", "程序 bug（如空指针）", "性能敏感的地方"), 1,
                                    "受检异常**强制调用方显式处理**，适合「有合理应对手段」的情况。而空指针、数组越界这类是"
                                            + "程序 bug，应该改代码而不是到处 catch —— 所以设计成非受检。"))));

    public static final List<Map<String, Object>> BASE_TRACKS = List.of(
            track("javascript", "JavaScript", "🟨",
                    "从变量、函数到数组处理，把语言核心语法走一遍。浏览器里可以直接跑，改完立刻看结果。",
                    "worker",
                    List.of(stage("js-basic", "入门", "能读懂并写出日常的函数与数组处理代码", JS_LESSONS))),
            track("python", "Python", "🐍",
                    "强类型动态语言，语法干净、生态庞大。教程里的每段代码都能在浏览器里真跑（Pyodide）。",
                    "pyodide",
                    List.of(stage("py-basic", "入门", "掌握变量、列表推导式与函数参数这两个最常用的语言特性", PY_LESSONS))),
            track("sql", "SQL", "🗄️",
                    "关系数据库查询语言。用 sql.js 在浏览器里建临时库，每条语句都能真跑真看结果。",
                    "sqljs",
                    List.of(stage("sql-basic", "入门", "能独立写出联表、分组、排序的查询，并知道常见陷阱", SQL_LESSONS))),
            track("typescript", "TypeScript", "🔷",
                    "在 JS 之上加静态类型：类型只在编译期存在、运行时全被擦除。浏览器里可直接转译运行（只去类型、不做类型检查）。",
                    "worker",
                    List.of(stage("ts-basic", "入门", "理解类型标注、判别联合与泛型分别在解决什么问题", TS_LESSONS))),
            track("lua", "Lua", "🌙",
                    "极简的嵌入式脚本语言：只有一种数据结构（table），索引从 1 开始。运行方案体积在这几门里最小（Fengari 约 300KB）。",
                    "fengari",
                    List.of(stage("lua-basic", "入门", "掌握 table 的引用语义，以及用 metatable 搭出面向对象", LUA_LESSONS))),
            // 下面几门暂时只有骨架：运行方案要么代价大、要么得走后端，先把位置与说明留出来
            track("c", "C / C++", "⚙️",
                    "指针、内存与 C++ 的 RAII。内容已写全；运行需要把 Clang 编译成 WASM，首屏要下几十兆 —— 适合做成「点了才下载」的可选能力。",
                    "clang-wasm",
                    List.of(stage("c-basic", "入门", "理解指针与内存，以及 RAII 为什么让 C++ 少犯内存错", CPP_LESSONS))),
            track("java", "Java", "☕",
                    "类型系统、集合框架与面向对象。内容已写全；浏览器里只有 CheerpJ（社区版不许自托管、且只到 Java 8），要学现代 Java 得走后端沙箱。",
                    "cheerpj",
                    List.of(stage("java-basic", "入门", "掌握包装类型陷阱、集合选型与异常分类", JAVA_LESSONS))),
            track("go", "Go", "🐹",
                    "编译链太重，浏览器里没有实用方案：官方 playground 也是把源码发到后端编译。",
                    "server",
                    List.of(stage("go-basic", "入门", "goroutine 与 channel", List.of()))),
            track("rust", "Rust", "🦀",
                    "同上，需要后端 rustc。浏览器内只能跑预编译好的 WASM 产物，不能现场编译源码。",
                    "server",
                    List.of(stage("rust-basic", "入门", "所有权与借用", List.of()))))
    ;

    // ==================================================================================
    // 便捷读取
    // ==================================================================================

    /** 运行方案 id 清单（白名单：track.runner 必须落在其中，否则会被落成 none） */
    public static List<String> runnerIds() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : RUNNERS) out.add(String.valueOf(r.get("id")));
        return out;
    }
}
