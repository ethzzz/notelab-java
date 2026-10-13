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

    // --------------------------------------------------------------------------------
    // JavaScript · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> JS_ADV_LESSONS = List.of(
            lesson("js-04", "对象与解构：数据是怎么组织的", 20,
                    """
                    对象是 JS 里最常用的数据结构，本质是**键值对集合**；日常处理数据几乎都在跟它打交道。

                    **解构**是从对象/数组里取值的简写：

                    ```js
                    const user = { name: "Alice", age: 30, city: "深圳" }
                    const { name, age } = user        // 取两个字段
                    const { city: hometown } = user    // 取出来重命名
                    const { email = "未填" } = user    // 默认值
                    ```

                    **展开运算符 `...`** 用来复制或合并，注意它是**浅拷贝**：

                    ```js
                    const next = { ...user, age: 31 }  // 复制其余字段、只改 age
                    ```

                    ⚠️ 两个高频坑：

                    1. `{ ...user }` 只复制第一层 —— 嵌套对象仍是**同一个引用**，改内层会同时改到原对象
                    2. 解构默认值只在值为 `undefined` 时生效，`null` 不会触发默认值（它是「有值」）

                    数组解构结合 `...` 也很常用：`const [first, ...rest] = list`。
                    应用场景：**不可变更新** —— 改一个字段时不直接改对象，而是造一个新对象，
                    这是 React / Redux 里更新状态的标准做法。
                    """,
                    """
                    const user = { name: "Alice", age: 30 };
                    const { name, age } = user;
                    const { email = "未填" } = user;
                    console.log(name, age, email);          // Alice 30 未填

                    const next = { ...user, age: 31 };       // 浅拷贝 + 覆盖
                    console.log(next.age, user.age);         // 31 30 —— 原对象没变

                    // 浅拷贝的坑：嵌套对象还是同一个引用
                    const a = { nested: { n: 1 } };
                    const b = { ...a };
                    b.nested.n = 99;
                    console.log(a.nested.n);                 // 99 —— 原对象被改到了

                    const [first, ...rest] = [1, 2, 3];
                    console.log(first, rest);                // 1 [2,3]
                    """,
                    "javascript",
                    List.of(
                            quiz("`const { a = 1 } = { a: null }` 里 `a` 的值是？",
                                    List.of("1", "null", "undefined", "报错"), 1,
                                    "解构默认值**只在值为 undefined 时生效**。`null` 是「我主动置空」，属于有值，不会用默认值。"
                                            + "这是 `??`（空值合并）和 `||` 的差别被搬进解构后最容易踩的一处。"),
                            quiz("`const b = { ...a }` 之后修改 `b.nested.x`，`a` 会受影响吗？",
                                    List.of("不会，展开是深拷贝", "会，展开只复制第一层", "取决于 nested 是不是数组", "只有 Map 才不受影响"), 1,
                                    "展开运算符做的是**浅拷贝**：第一层的键值被复制，但值如果是对象，复制的是引用。"
                                            + "要真正隔离得逐层展开，或用 `structuredClone`。"))),

            lesson("js-05", "异步：Promise 与 async/await", 25,
                    """
                    JS 是**单线程**的，但网络请求、定时器不能把线程堵住干等 —— 于是有了异步。

                    早期用回调，一层套一层就是「回调地狱」；现在统一用 **Promise**，
                    再包一层 `async/await` 就能写成看起来同步的代码。

                    ```js
                    const delay = (ms) => new Promise((r) => setTimeout(r, ms));

                    async function task() {
                      console.log("开始")
                      await delay(500)      // 等 500ms，但主线程不阻塞
                      console.log("结束")
                    }
                    ```

                    三个必须记住的点：

                    1. `async` 函数**一定返回 Promise**；`await` 只能写在 async 函数里
                    2. `await` 拿到的是结果，出错要用 `try/catch` 接
                    3. 互不依赖的多个异步要**并发**：`await Promise.all([a, b])`，而不是连着 `await a; await b`

                    ⚠️ 最常见的坑：`array.forEach(async (x) => { await f(x) })` **不会等待** ——
                    forEach 不认 async 回调，循环会立刻走完。要串行就用 `for...of`。
                    """,
                    """
                    const delay = (ms) => new Promise((r) => setTimeout(r, ms));

                    async function main() {
                      console.log("开始");
                      await delay(100);
                      console.log("100ms 后");

                      // 并发：两个一起等，总耗时约 100ms，而不是 200ms
                      const t0 = Date.now();
                      await Promise.all([delay(100), delay(100)]);
                      console.log("并发耗时约", Date.now() - t0, "ms");

                      // 出错用 try/catch
                      try {
                        await Promise.reject(new Error("boom"));
                      } catch (e) {
                        console.log("捕获到:", e.message);
                      }
                    }
                    main();
                    """,
                    "javascript",
                    List.of(
                            quiz("两个各耗时 1 秒、互不依赖的请求，`await a; await b` 与 `await Promise.all([a, b])` 总耗时分别是？",
                                    List.of("都是 1 秒", "都是 2 秒", "前者约 2 秒，后者约 1 秒", "前者约 1 秒，后者约 2 秒"), 2,
                                    "连着 `await` 是**串行**，一个等完再等下一个；`Promise.all` 是**并发**，几个一起跑。"
                                            + "判断依据只有一条：这两个任务之间有没有依赖。没有就别串行。"),
                            quiz("`[1,2,3].forEach(async (n) => { await delay(10) })` 之后立刻打印，会怎样？",
                                    List.of("等三个都跑完才打印", "立刻打印 —— forEach 不等 async 回调", "抛异常", "只等第一个"), 1,
                                    "forEach 只是同步遍历，回调返回的 Promise 被丢掉了，它不会等待。"
                                            + "要点：**要顺序等待就用 `for...of` + await**，要并发就用 `Promise.all` 配 map。"))),

            lesson("js-06", "this、原型与 class", 20,
                    """
                    `this` 指向**调用时的上下文**，不是定义时。四种情况快速记：

                    1. `obj.fn()` → this 是 obj
                    2. `fn()` 单独调用 → 严格模式下是 `undefined`，非严格是全局对象
                    3. `new Fn()` → this 是新建的那个对象
                    4. 箭头函数 → 没有自己的 this，取**定义时**外层的

                    **原型**是 JS 的继承机制：每个对象有一个 `__proto__` 指向原型，
                    查属性时找不到就顺着这条链往上找，直到 `null`。

                    `class` 只是这套原型机制的**语法糖**，写起来更清楚：

                    ```js
                    class Animal {
                      constructor(name) { this.name = name }
                      speak() { return this.name + " 叫了一声" }
                    }
                    class Dog extends Animal {
                      speak() { return this.name + " 汪" }   // 重写
                    }
                    ```

                    应用场景：**把方法摘出来当回调传**是最容易丢 this 的地方
                    （`const f = dog.speak; f()`）。解法是 `f.bind(dog)` 或直接写成箭头函数。
                    """,
                    """
                    class Animal {
                      constructor(name) { this.name = name; }
                      speak() { return this.name + " 叫了一声"; }
                    }
                    class Dog extends Animal {
                      speak() { return this.name + " 汪"; }
                    }

                    const d = new Dog("旺财");
                    console.log(d.speak());               // 旺财 汪
                    console.log(d instanceof Animal);     // true —— 原型链上有 Animal

                    // 把方法摘出来单独调用 → this 丢失
                    const f = d.speak;
                    try {
                      f();
                    } catch (e) {
                      console.log("丢了 this:", e.constructor.name);
                    }

                    // 绑定之后正常
                    const bound = d.speak.bind(d);
                    console.log(bound());                 // 旺财 汪
                    """,
                    "javascript",
                    List.of(
                            quiz("`class Dog extends Animal` 之后，`new Dog()` 的 `instanceof Animal` 是？",
                                    List.of("false", "true", "报错", "undefined"), 1,
                                    "extends 会把 `Dog.prototype` 的原型链接到 `Animal.prototype` 上，"
                                            + "所以顺着链能查到 Animal —— 这就是「class 是原型语法糖」的直接体现。"),
                            quiz("`const f = obj.method; f()` 里的 `this` 指向？",
                                    List.of("obj", "undefined（严格模式）", "永远是 window", "调用 f 的上一个函数"), 1,
                                    "this 由**调用方式**决定：`obj.method()` 里有 obj，摘出来后就是普通函数调用。"
                                            + "ES Module / class 体默认是严格模式，所以是 undefined 而不是全局对象。"))));

    // --------------------------------------------------------------------------------
    // JavaScript · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> JS_PRO_LESSONS = List.of(
            lesson("js-07", "DOM 与事件：把数据变成界面", 20,
                    """
                    前面讲的是语言本身，这一节开始「用 JS 干活」—— 最常见的活就是操作页面（DOM）。

                    ```js
                    const box = document.querySelector("#list")
                    const li = document.createElement("li")
                    li.textContent = "新条目"
                    box.appendChild(li)
                    ```

                    **事件**让页面能响应操作，核心是「注册回调」：

                    ```js
                    button.addEventListener("click", (e) => { console.log(e.target) })
                    ```

                    三条业务要点：

                    1. **事件委托**：给父元素挂一个监听，用 `e.target.closest("li")` 判断点的是谁。
                       列表再长也只绑一次，动态新增的项自动生效 —— 这是长列表的标准做法
                    2. 塞用户输入用 `textContent`，**不要用 `innerHTML`** —— 后者会把输入当 HTML 执行（XSS）
                    3. 频繁改 DOM 很慢，**先拼好再一次性插入**，别在循环里逐个 append

                    正是这三件事让「数据驱动」的框架（React / Vue）成为主流：
                    **你只描述界面长什么样，DOM 怎么改交给框架**。
                    """,
                    """
                    // 数据 → 界面：先拼字符串再一次性插入，比循环里逐个插入快得多
                    const users = [
                      { id: 1, name: "Alice", vip: true },
                      { id: 2, name: "Bob", vip: false },
                    ];

                    const html = users
                      .map((u) => '<li data-id="' + u.id + '">' + u.name + (u.vip ? " ⭐" : "") + "</li>")
                      .join("");
                    console.log(html);

                    // 事件委托的判定逻辑（浏览器里换成 e.target.closest("li").dataset.id）
                    function handleClick(el) {
                      const id = el && el.dataset ? Number(el.dataset.id) : NaN;
                      return Number.isFinite(id) ? "点击了用户 " + id : "点了空白处";
                    }
                    console.log(handleClick({ dataset: { id: "2" } }));
                    console.log(handleClick({ dataset: {} }));
                    """,
                    "javascript",
                    List.of(
                            quiz("为什么长列表推荐用「事件委托」而不是给每一项都绑监听？",
                                    List.of("事件委托能阻止冒泡", "只绑一次、内存占用小，且动态新增的项自动生效", "事件委托执行更快，因为不冒泡", "没有区别，只是写法不同"), 1,
                                    "给 1000 个 li 各绑一个监听要 1000 个函数和 1000 次注册；委托只在父级绑一次，"
                                            + "新增节点无需重新绑定。代价是要自己判断 `e.target` 是谁。"),
                            quiz("把用户输入的昵称显示到页面上，应该用？",
                                    List.of("innerHTML", "textContent", "insertAdjacentHTML", "document.write"), 1,
                                    "`innerHTML` 会把字符串当 HTML 解析并执行其中的脚本，用户输入 `<img onerror=...>` 就能执行代码。"
                                            + "`textContent` 只当纯文本，是显示用户内容的默认选择。"))),

            lesson("js-08", "模块化与 fetch：组装一个真实页面", 25,
                    """
                    真实项目不是一个巨大文件，而是拆成模块再组合。ESM（ES Module）是标准写法：

                    ```js
                    // math.js
                    export const add = (a, b) => a + b
                    export default function multiply(a, b) { return a * b }

                    // main.js
                    import multiply, { add } from "./math.js"
                    ```

                    `fetch` 是请求数据的标准 API，返回 Promise：

                    ```js
                    const res = await fetch("/api/users")
                    if (!res.ok) throw new Error("HTTP " + res.status)   // ⚠️ 别漏
                    const data = await res.json()
                    ```

                    ⚠️ 三个坑：

                    1. `fetch` **只有网络层错误才 reject**，404 / 500 也算「成功到达」——
                       必须自己检查 `res.ok`，否则会把错误页当成正常数据解析
                    2. 解析响应体也是异步的（`await res.json()`），而且**只能读一次**
                    3. 需要带 cookie / token 时要显式配 `credentials` 与 `headers`

                    `export default` 导出时导入可任意命名、不加花括号；具名导出要加花括号、名字必须一致。
                    到这里，「取数据 → 处理 → 渲染」这条主干就通了 —— 这就是一个前端应用的最小闭环。
                    """,
                    """
                    // 用对象模拟一个 Response（浏览器里换成真 fetch）
                    const fakeRes = {
                      ok: true,
                      status: 200,
                      json: async () => [{ id: 1, name: "Alice" }, { id: 2, name: "Bob" }],
                    };

                    async function loadNames() {
                      const res = await fakeRes;
                      // ⚠️ fetch 不会因为 404 抛错，必须自己判断
                      if (!res.ok) throw new Error("HTTP " + res.status);
                      const users = await res.json();
                      return users.map((u) => u.name);
                    }

                    loadNames().then((names) => console.log("用户:", names));

                    const bad = { ok: false, status: 404 };
                    console.log("404 时 ok =", bad.ok, "→ 得靠这行拦住错误");
                    """,
                    "javascript",
                    List.of(
                            quiz("`fetch(\"/not-exist\")` 返回 404 时会怎样？",
                                    List.of("Promise 被 reject，走 catch", "Promise 正常 resolve，需要自己检查 res.ok", "返回 undefined", "自动重试"), 1,
                                    "fetch 的设计是「只有网络中断/DNS 失败这类才 reject」，HTTP 错误码属于「成功收到响应」。"
                                            + "所以 `if (!res.ok) throw` 这一行是必须的，漏了就会拿错误页去解析。"),
                            quiz("ESM 里 `export default fn` 与 `export const fn` 的区别是？",
                                    List.of("没有区别", "默认导出导入时不用花括号、可任意命名；具名导出要花括号且名字一致", "默认导出只能是函数", "具名导出一个模块只能有一个"), 1,
                                    "一个模块只能有一个 default，导入时可随意起名（`import anything from ...`）；"
                                            + "具名导出可以有多个，导入必须用 `{ }` 且名字对得上（或 `as` 改名）。"))));

    // --------------------------------------------------------------------------------
    // TypeScript · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> TS_ADV_LESSONS = List.of(
            lesson("ts-04", "工具类型：Partial / Pick / Omit / Record", 20,
                    """
                    现实里最常写的类型，不是从零定义，而是**从已有类型里改造出来**。
                    TS 内置了一批工具类型干这件事：

                    ```ts
                    interface User { id: number; name: string; email: string }

                    type UpdateUser = Partial<User>          // 所有字段变可选
                    type UserName   = Pick<User, "name">     // 只留一个字段
                    type NoEmail    = Omit<User, "email">    // 去掉一个字段
                    type CountById  = Record<string, number> // 键值映射
                    type Frozen     = Readonly<User>         // 全部只读
                    ```

                    ⚠️ `Partial` 是**浅层**的：嵌套对象的内部字段不会自动变可选。
                    要深层可选得自己递归（或引 `type-fest` 这类库）。

                    为什么用它们而不是重新写一个 interface？因为**改一处会自动传导**：
                    给 `User` 加个字段，`Omit<User, "email">` 跟着更新，重写的那份不会。

                    应用场景：后台接口常见的「创建 / 更新 / 详情」三种形态，
                    数据其实是同一份，用 `Pick` / `Omit` / `Partial` 派生出来最省维护成本。
                    """,
                    """
                    interface User { id: number; name: string; email: string }

                    type UpdateUser = Partial<User>;
                    const patch: UpdateUser = { name: "Alice" };   // 只传一个字段也合法
                    console.log(patch);

                    type NoEmail = Omit<User, "email">;
                    const brief: NoEmail = { id: 1, name: "Alice" };
                    console.log(brief);

                    type CountById = Record<string, number>;
                    const stats: CountById = { a: 1, b: 2 };
                    console.log(stats.a + stats.b);
                    """,
                    "typescript",
                    List.of(
                            quiz("`Partial<User>` 对**嵌套对象**里的字段也生效吗？",
                                    List.of("生效，会递归变可选", "不生效，它只处理第一层", "取决于 strict 模式", "只对数组生效"), 1,
                                    "`Partial` 是浅层的 —— 它把第一层的每个字段加上 `?`，嵌套对象的类型本身没变。"
                                            + "要深层可选必须自己写递归类型。这是它最常见的误解。"),
                            quiz("用 `Omit<User, \"email\">` 而不是手写一个新 interface，最大的好处是？",
                                    List.of("运行更快", "User 变了派生类型自动跟着变，不会漏改", "体积更小", "能阻止 User 被修改"), 1,
                                    "手抄一份的类型是**死代码**：源类型加字段时不会同步。派生类型把「关系」写进了类型系统，"
                                            + "这是类型复用比复制粘贴强的地方。"))),

            lesson("ts-05", "类型守卫：把宽类型收窄成具体类型", 20,
                    """
                    一个变量声明得比较宽（如 `string | number`），用之前必须**收窄**到具体类型。
                    这个过程叫类型收窄，靠的是「类型守卫」。

                    四种常用守卫：

                    ```ts
                    typeof x === "string"          // 基本类型
                    "name" in obj                   // 对象有没有某个键
                    arr instanceof Date             // 是不是某个类
                    isUser(x)                       // 自定义谓词，见下
                    ```

                    自定义谓词的关键是**返回类型写成 `x is T`**，而不是 `boolean`：

                    ```ts
                    function isUser(v: unknown): v is { name: string } {
                      return typeof v === "object" && v !== null && "name" in v
                    }
                    ```

                    ⚠️ 写 `: boolean` 就白写了 —— 收窄不会传到外面，调用方还得再判一次。

                    应用场景：处理**外部数据**（接口响应、localStorage、消息）时，
                    先按 `unknown` 接住，再用守卫逐层收窄 —— 这正是「运行时校验」类的库
                    （zod / valibot）在类型层面做的事。
                    """,
                    """
                    type Shape =
                      | { kind: "circle"; r: number }
                      | { kind: "rect"; w: number; h: number };

                    function area(s: Shape): number {
                      // 判别联合 + 收窄：进到每个分支后，能访问的字段就确定了
                      if (s.kind === "circle") return 3.14 * s.r * s.r;
                      return s.w * s.h;
                    }
                    console.log(area({ kind: "circle", r: 2 }));
                    console.log(area({ kind: "rect", w: 2, h: 3 }));

                    // 自定义类型守卫：返回 x is T，收窄才会传到调用方
                    function isText(v: unknown): v is string {
                      return typeof v === "string";
                    }
                    const raw: unknown = "hello";
                    if (isText(raw)) console.log("长度:", raw.length);
                    """,
                    "typescript",
                    List.of(
                            quiz("自定义类型守卫写成 `function f(x): boolean` 会有什么问题？",
                                    List.of("会报错", "没影响", "收窄不会传递出去，调用方拿不到具体类型", "运行时更慢"), 2,
                                    "类型守卫的魔力全在返回类型 `x is T` 上 —— 它告诉编译器「返回 true 时 x 就是 T」。"
                                            + "写 `boolean` 只是个普通判断，外面依然不知道 x 的具体类型。"),
                            quiz("处理接口返回的 JSON，最稳妥的入门做法是？",
                                    List.of("直接断言成目标类型 as User", "先接成 unknown，再用类型守卫逐层收窄", "声明成 any", "不写类型"), 1,
                                    "断言 `as` 只是让编译器闭嘴、运行时不做任何检查，数据不对照样崩。"
                                            + "先 `unknown` + 守卫校验，才是「类型与运行时一致」的做法。"))),

            lesson("ts-06", "条件类型与映射类型：在类型层面编程", 25,
                    """
                    TS 的类型系统是**图灵完备**的 —— 你可以写「如果 A 能赋值给 B，就取 X，否则取 Y」。
                    这就是条件类型：

                    ```ts
                    type IsString<T> = T extends string ? "yes" : "no"
                    type A = IsString<"a">    // "yes"
                    type B = IsString<1>      // "no"
                    ```

                    配合 `infer` 还能把类型「拆开」：

                    ```ts
                    type ElementOf<T> = T extends (infer U)[] ? U : never
                    type E = ElementOf<string[]>   // string
                    ```

                    **映射类型**则是对每个键做变换，工具类型其实都是它写出来的：

                    ```ts
                    type MyPartial<T> = { [K in keyof T]?: T[K] }
                    ```

                    ⚠️ 这一节的价值不是让你到处写炫技类型，而是**读懂别人的类型定义**：
                    axios、React 的泛型签名、各种库的 `.d.ts` 全是这套写法。
                    业务代码里，够用就好 —— 类型太花会把人绕晕，维护成本反而更高。
                    """,
                    """
                    type IsString<T> = T extends string ? "yes" : "no";
                    type A = IsString<"a">;   // "yes"
                    type B = IsString<1>;     // "no"

                    // infer：把数组的元素类型取出来
                    type ElementOf<T> = T extends (infer U)[] ? U : never;
                    const first = <T,>(arr: ElementOf<T[]>[]): ElementOf<T[]> => arr[0];

                    console.log(first(["a", "b"]));

                    // 映射类型：手写一个 Partial
                    type MyPartial<T> = { [K in keyof T]?: T[K] };
                    type P = MyPartial<{ a: number; b: string }>;
                    const p: P = { a: 1 };
                    console.log(p);
                    """,
                    "typescript",
                    List.of(
                            quiz("`type E = string[] extends (infer U)[] ? U : never` 里 E 是什么？",
                                    List.of("string[]", "string", "never", "unknown"), 1,
                                    "`infer U` 是「先占个位置，匹配成功后反推出来」。数组匹配 `(infer U)[]` 时 U 就是元素类型，"
                                            + "这正是 `ReturnType`、`Parameters` 这些工具类型的实现基础。"),
                            quiz("业务代码里应该大量使用条件类型吗？",
                                    List.of("应该，越抽象越好", "不应该 —— 够用就好，类型太花会显著抬高维护成本", "必须用，否则没发挥 TS 优势", "只有写库时才允许"), 1,
                                    "类型是给人读的。过度抽象的泛型/条件类型会让同事看不懂、跳转后一头雾水，"
                                            + "而它换来的收益往往只是省了几个字。读懂它（尤其是库的类型）比会写它更重要。"))));

    // --------------------------------------------------------------------------------
    // TypeScript · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> TS_PRO_LESSONS = List.of(
            lesson("ts-07", "类型安全的请求层：泛型封装 API", 25,
                    """
                    项目里最容易「类型虚假安全」的地方就是请求层：接口返回什么，
                    编译期其实一无所知，全靠你手动断言。

                    一个基础的泛型封装长这样：

                    ```ts
                    async function request<T>(url: string, init?: RequestInit): Promise<T> {
                      const res = await fetch(url, init)
                      if (!res.ok) throw new Error("HTTP " + res.status)
                      return (await res.json()) as T      // ⚠️ 这里是唯一的「信任点」
                    }

                    const users = await request<User[]>("/api/users")   // users 推断为 User[]
                    ```

                    关键认识：**`as T` 是骗编译器，不是校验**。运行时数据不符不会报错。
                    要让类型与运行时真正对齐，得在返回前加一层校验（zod 这类）：

                    ```ts
                    const schema = z.array(z.object({ id: z.number(), name: z.string() }))
                    return schema.parse(await res.json())     // 不符合就抛错
                    ```

                    ⚠️ 别把校验做成摆设：只在校验、到处断言，等于把 `any` 换了个写法。

                    应用场景：所有数据入口（HTTP、WebSocket、localStorage、URL 参数）
                    都应该在**边界处**校验一次 —— 内部代码就可以放心地用类型。
                    """,
                    """
                    interface User { id: number; name: string }

                    // 泛型封装：调用方声明期望的类型，返回值自动推断
                    async function request<T>(fakeJson: unknown): Promise<T> {
                      return fakeJson as T;   // ⚠️ 只是断言，运行时不会检查
                    }

                    async function main() {
                      const users = await request<User[]>([
                        { id: 1, name: "Alice" },
                        { id: 2, name: "Bob" },
                      ]);
                      // users 现在是 User[]，能自动补全 name / id
                      console.log(users.map((u) => u.name));

                      // 运行时给错数据，断言不会拦（这正是要在边界做校验的原因）
                      const bad = await request<User[]>([{ nope: 1 }]);
                      console.log("断言后的第一个元素:", bad[0]);
                    }
                    main();
                    """,
                    "typescript",
                    List.of(
                            quiz("`const data = (await res.json()) as User` 这行的实际保护力度是？",
                                    List.of("运行时会校验数据是不是 User", "没有任何运行时保护，只是让编译器不再报错", "会自动补默认值", "会在开发模式校验"), 1,
                                    "`as` 是**类型断言**，编译后完全消失。数据不符合 User 也不会报错，"
                                            + "直到某处访问 `data.name` 拿到 undefined 才炸 —— 而且报错点离源头很远。"),
                            quiz("为什么把校验放在「边界」（请求返回处）而不是到处校验？",
                                    List.of("因为边界处性能更好", "边界校验一次后，内部代码可以信任类型，避免重复判断", "因为 TS 只支持在边界校验", "没有区别"), 1,
                                    "这是类型系统的核心思路：**入口收敛、内部信任**。"
                                            + "在边界处把 unknown 校验成具体类型，之后整条调用链都能用类型安全换掉防御代码。"))),

            lesson("ts-08", "工程配置：tsconfig 与严格模式", 15,
                    """
                    同一份代码，`tsconfig.json` 不同，TS 能帮你抓出的问题数量完全不同。

                    最该打开的一个开关是 `strict`：

                    ```json
                    {
                      "compilerOptions": {
                        "strict": true,
                        "noUncheckedIndexedAccess": true,
                        "noImplicitOverride": true
                      }
                    }
                    ```

                    `strict` 是**一组开关的集合**，其中最重要的两个：

                    - `strictNullChecks`：`null` / `undefined` 不再能赋给任意类型 ——
                      「十亿美元的错误」就是从这里开始被挡住的
                    - `noImplicitAny`：推断不出来时必须显式写类型，不能悄悄变成 `any`

                    `noUncheckedIndexedAccess` 是新项目值得单开的一条：
                    它让 `arr[0]` 的类型变成 `T | undefined`，逼你处理「数组可能是空的」。

                    ⚠️ 两个实践建议：

                    1. **老项目别一次性全开** —— 先开 `strict`，把报错按目录逐步清零，否则会产生一堆 `// @ts-ignore`
                    2. `any` 可以暂时用，但要**有注释说明为什么**；`// @ts-ignore` 尽量换成 `// @ts-expect-error`
                       （后者在错误消失时会反过来报错，不会留下失效的忽略）

                    应用场景：CI 里跑 `tsc --noEmit` 做类型检查（不产文件），
                    这是「把类型问题挡在合并之前」的最低成本手段。
                    """,
                    """
                    // strict 模式下的两个典型差异
                    function pick(arr: number[], i: number): number {
                      // 打开 noUncheckedIndexedAccess 后，这行会提示可能是 undefined
                      return arr[i];
                    }
                    console.log(pick([10, 20], 1));

                    // strictNullChecks：null / undefined 不能乱赋值
                    let maybe: string | null = null;
                    maybe = "ok";
                    console.log(maybe.toUpperCase());

                    // 处理可选值必须显式收窄
                    function len(s: string | null): number {
                      return s === null ? 0 : s.length;
                    }
                    console.log(len(null), len("abc"));
                    """,
                    "typescript",
                    List.of(
                            quiz("`strictNullChecks` 打开后，`let s: string = null` 会怎样？",
                                    List.of("编译通过", "编译报错：null 不能赋给 string", "运行时抛错", "自动变成 string | null"), 1,
                                    "这正是它存在的意义 —— 把「可能为 null」逼到类型上显式表达，"
                                            + "而不是等到运行时读属性才崩。TS 官方称之为「避免十亿美元的错误」。"),
                            quiz("`// @ts-expect-error` 与 `// @ts-ignore` 的关键区别是？",
                                    List.of("没有区别，只是别名", "如果下方真的没有错误，@ts-expect-error 会反过来报错", "前者能忽略运行时错误", "后者只能在 .d.ts 里用"), 1,
                                    "@ts-expect-error 的语义是「我**预期**这里有错」，所以错误消失时它会报「未使用的 expect-error」，"
                                            + "等于自带过期提醒；@ts-ignore 则会无声地一直留在那里。"))));

    // --------------------------------------------------------------------------------
    // Python · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> PY_ADV_LESSONS = List.of(
            lesson("py-04", "字典与集合：哈希表能做什么", 20,
                    """
                    列表靠下标找元素，**字典（dict）和集合（set）靠哈希**，查找是 O(1) ——
                    这是 Python 里最该先建立的数据结构直觉。

                    ```python
                    counts = {}                 # 空字典
                    counts["a"] = counts.get("a", 0) + 1    # 计数惯用法
                    seen = set()                # 集合：只关心「有没有」
                    ```

                    三种典型用途：

                    1. **计数 / 分组**：遍历一遍建 `{key: [...]}` 或 `{key: n}`
                    2. **去重**：`set(items)` 一步搞定（但会丢掉顺序）
                    3. **成员判断**：`if x in seen` —— 用集合而不是列表，数据量大时差距是量级的

                    ⚠️ 三个必须知道的点：

                    - 字典的键必须**可哈希**（不可变）：数字、字符串、元组可以，列表不行
                    - `d[k]` 取不存在的键会抛 `KeyError`，不确定时用 `d.get(k)` 或 `d.get(k, 默认值)`
                    - `collections.Counter` 直接把计数做完：`Counter("aabbbc")` → `{"b": 3, "a": 2, "c": 1}`

                    应用场景：**词频统计、按字段分组、判断重复提交** —— 几乎每个数据处理任务都会用到。
                    """,
                    """
                    from collections import Counter

                    # 计数：手写版
                    counts = {}
                    for ch in "aabbbc":
                        counts[ch] = counts.get(ch, 0) + 1
                    print(counts)

                    # 标准库版，一步到位
                    print(Counter("aabbbc"))

                    # 分组
                    by_first = {}
                    for w in ["apple", "avocado", "banana"]:
                        by_first.setdefault(w[0], []).append(w)
                    print(by_first)

                    # 去重 + 成员判断
                    seen = set(["x", "y", "x"])
                    print(seen, "x" in seen)

                    # 取不存在的键：get 不会抛，d[k] 会
                    print(counts.get("z"), counts.get("z", 0))
                    """,
                    "python",
                    List.of(
                            quiz("为什么 `if x in some_set` 比 `if x in some_list` 快得多？",
                                    List.of("集合底层是哈希表，查找是 O(1)；列表要逐个比，是 O(n)", "集合是 C 实现的，列表不是", "列表会自动排序", "其实一样快"), 0,
                                    "集合/字典用哈希定位，元素越多优势越明显。"
                                            + "把「成员判断」和「去重」从列表换成集合，是最常见的一处低成本性能优化。"),
                            quiz("`d = {}; d[\"a\"]` 会怎样？",
                                    List.of("返回 None", "返回空字符串", "抛 KeyError", "自动创建并返回 0"), 2,
                                    "方括号是「一定存在」的语义，取不到就抛 KeyError。"
                                            + "不确定时用 `d.get(\"a\")`（返回 None）或 `d.get(\"a\", 0)` 给默认值。"))),

            lesson("py-05", "类与魔术方法：让对象像内建类型一样工作", 25,
                    """
                    类是把**数据**和**操作数据的方法**绑在一起。除了 `__init__`，
                    一组以双下划线包起来的「魔术方法」决定你的对象能不能像内建类型那样使用。

                    ```python
                    class Money:
                        def __init__(self, amount):
                            self.amount = amount

                        def __repr__(self):                      # print / 调试时显示什么
                            return f"Money({self.amount})"

                        def __add__(self, other):                # 支持 m1 + m2
                            return Money(self.amount + other.amount)

                        def __eq__(self, other):                 # 支持 ==
                            return self.amount == other.amount
                    ```

                    几个最常用的：

                    | 魔术方法 | 让它能这样写 |
                    |---|---|
                    | `__repr__` | `print(obj)`、调试时看得懂 |
                    | `__eq__` | `a == b` |
                    | `__len__` | `len(obj)` |
                    | `__iter__` | `for x in obj` |
                    | `__enter__` / `__exit__` | `with obj:` |

                    ⚠️ 两个坑：

                    1. 只写 `__eq__` 不写 `__hash__`，对象会**变得不可哈希**（不能放进 set / 当字典键）
                    2. `__repr__` 和 `__str__` 不一样：前者给开发者看（要能重建对象），后者给用户看

                    应用场景：写业务模型（订单、金额、配置）时，实现 `__eq__` / `__repr__`
                    能让调试和比较都省事 —— `print(order)` 直接看到内容，而不是一串内存地址。
                    """,
                    """
                    class Money:
                        def __init__(self, amount):
                            self.amount = amount

                        def __repr__(self):
                            return "Money(" + str(self.amount) + ")"

                        def __add__(self, other):
                            return Money(self.amount + other.amount)

                        def __eq__(self, other):
                            return isinstance(other, Money) and self.amount == other.amount

                    a = Money(10)
                    b = Money(20)
                    print(a + b)          # Money(30) —— 靠 __add__
                    print(a == Money(10)) # True     —— 靠 __eq__
                    print(a)              # Money(10) —— 靠 __repr__
                    """,
                    "python",
                    List.of(
                            quiz("只定义了 `__eq__` 而没有定义 `__hash__`，对象会怎样？",
                                    List.of("没什么影响", "变成不可哈希，不能放进 set 或当字典键", "自动用内存地址当哈希", "`__eq__` 会失效"), 1,
                                    "Python 的约定是「可比较相等」就要「可哈希」，两者必须一致。"
                                            + "只重写 `__eq__` 时 Python 会把 `__hash__` 置为 None，于是对象不能进集合 —— 要一起重写。"),
                            quiz("`__repr__` 和 `__str__` 的分工是？",
                                    List.of("完全等价", "repr 给开发者调试（尽量可重建对象），str 给用户看", "str 性能更好", "repr 只用于日志"), 1,
                                    "`repr` 面向开发者：`Money(10)`、`User(id=1)` 这种能直接看懂结构的写法；"
                                            + "`str` 面向终端用户，可以更友好。没定义 `__str__` 时默认回落到 `__repr__`。"))),

            lesson("py-06", "异常与文件：让程序在出错时优雅收场", 20,
                    """
                    Python 的哲学是「**请求原谅比请求许可容易**」：
                    与其到处 `if 文件存在`，不如直接做、出错再处理。

                    ```python
                    try:
                        n = int(text)
                    except ValueError:
                        n = 0            # 只处理「能预料到」的那一种
                    ```

                    完整的 try 有四个分支，各有用处：

                    ```python
                    try:
                        f = open(path)
                    except FileNotFoundError:
                        ...        # 出错时
                    else:
                        ...        # 没出错才走（放「依赖成功」的逻辑）
                    finally:
                        f.close()  # 无论如何都走（收尾）
                    ```

                    ⚠️ 三条纪律：

                    1. **不要裸 `except:`** —— 它会连键盘中断都吞掉，且掩盖真实 bug。只捕具体异常
                    2. 文件一律用 `with`：`with open(p, encoding="utf-8") as f:` —— 离开缩进块自动关闭，
                       即使中途抛异常也会关（这就是上面 `finally` 的常见替代）
                    3. 读文件要**显式写 `encoding="utf-8"`**：Windows 默认用 GBK，中文会直接乱码
                       （本项目就踩过这个坑）

                    应用场景：解析用户输入、读配置、调外部接口 —— 这些「可能失败」的地方都要处理，
                    但只处理你**知道怎么应对**的那种错误。
                    """,
                    """
                    # 只捕获能预料到的异常
                    def to_int(text):
                        try:
                            return int(text)
                        except ValueError:
                            return 0

                    print(to_int("42"), to_int("abc"))

                    # else / finally 的分工
                    def load(path, content):
                        try:
                            data = content
                        except Exception:
                            print("读取出错")
                            return None
                        else:
                            print("读取成功")
                            return data
                        finally:
                            print("收尾（无论如何都执行）")

                    print(load("x.txt", "hello"))
                    """,
                    "python",
                    List.of(
                            quiz("为什么不应该写裸 `except:`？",
                                    List.of("性能不好", "会连 KeyboardInterrupt 这类都吞掉，并掩盖真实 bug", "语法已废弃", "会导致内存泄漏"), 1,
                                    "裸 except 把「我能处理的错误」和「我完全没预料到的 bug」一视同仁地吞掉，"
                                            + "结果是程序带着坏状态继续跑，排查时毫无线索。永远捕获具体异常类型。"),
                            quiz("用 `with open(p)` 相比手动 `f = open(p)` 的核心好处是？",
                                    List.of("读得更快", "无论是否抛异常都会自动关闭文件", "自动创建文件", "支持并发读"), 1,
                                    "`with` 靠上下文管理器保证 `__exit__` 一定被调用 —— 即使块里抛异常也会关文件。"
                                            + "手动 open 一旦忘了 close（或中途抛错），文件句柄就会泄漏。"))));

    // --------------------------------------------------------------------------------
    // Python · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> PY_PRO_LESSONS = List.of(
            lesson("py-07", "标准库三件套：json / re / datetime", 20,
                    """
                    日常脚本里出现频率最高的三个标准库，不用装任何东西就能用。

                    **json** —— 与接口/配置打交道：

                    ```python
                    import json
                    data = json.loads('{"a": 1}')        # 字符串 → 对象
                    text = json.dumps(data, ensure_ascii=False)   # 对象 → 字符串
                    ```

                    ⚠️ 中文一定要写 `ensure_ascii=False`，否则会变成 `\\u4e2d` 这样的转义。

                    **re** —— 从文本里捞内容：

                    ```python
                    import re
                    re.findall("[0-9]+", "订单 12 件，单价 345 元")   # ['12', '345']
                    ```

                    ⚠️ 正则串一律加 `r` 前缀（`r"[0-9]+"`），否则反斜杠会被 Python 先解释一遍。

                    **datetime** —— 处理时间（也是最容易出错的地方）：

                    ```python
                    from datetime import datetime, timedelta
                    now = datetime.now()
                    yesterday = now - timedelta(days=1)
                    stamp = now.strftime("%Y-%m-%d %H:%M")
                    ```

                    ⚠️ 能用 `datetime` 就别自己拿字符串算日期；跨时区要用带时区的对象，
                    不然线上会出现「差 8 小时」这类经典问题。

                    应用场景：写数据清洗、日志分析、接口对接脚本 —— 这三个库基本能覆盖八成需求。
                    """,
                    """
                    import json
                    import re
                    from datetime import datetime, timedelta

                    # json：中文不要被转义
                    data = json.loads('{"name": "张三", "age": 30}')
                    print(json.dumps(data, ensure_ascii=False))

                    # re：用 r 前缀，避免反斜杠被二次解释
                    nums = re.findall(r"[0-9]+", "订单 12 件，单价 345 元")
                    print(nums, [int(n) for n in nums])

                    # datetime：别用字符串算日期
                    now = datetime(2026, 10, 13, 12, 0)
                    print((now - timedelta(days=1)).strftime("%Y-%m-%d %H:%M"))
                    """,
                    "python",
                    List.of(
                            quiz("`json.dumps` 不写 `ensure_ascii=False` 时，中文会变成什么？",
                                    List.of("原样输出", "变成 \\u 开头的转义序列", "报错", "变成问号"), 1,
                                    "默认 `ensure_ascii=True` 会把所有非 ASCII 字符转成转义，可读性极差。"
                                            + "给接口返回或写文件时基本都要显式关掉它。"),
                            quiz("正则字符串前加 `r`（如 `r\"[0-9]+\"`）的作用是？",
                                    List.of("表示正则表达式", "让反斜杠不被 Python 提前转义，原样交给 re 模块", "提高匹配速度", "开启多行模式"), 1,
                                    "普通字符串里 `\\n`、`\\d` 会先被 Python 解释；`r` 前缀（raw）关掉这层处理，"
                                            + "让正则引擎看到你真正想写的模式。写正则时几乎是默认习惯。"))),

            lesson("py-08", "虚拟环境与数据处理场景", 20,
                    """
                    这一节把前面学的串起来 —— 一个典型的「数据处理」流程，以及它的工程前提。

                    **工程前提：每个项目一个虚拟环境**。全局装包迟早会版本冲突：

                    ```bash
                    python -m venv .venv          # 建一个隔离环境
                    .venv/Scripts/pip install requests   # Windows（Linux 是 .venv/bin/pip）
                    ```

                    装完把依赖冻下来，别人才能复现：

                    ```bash
                    pip freeze > requirements.txt
                    ```

                    **数据处理的关键选择：列表 vs 生成器**。

                    ```python
                    [x * 2 for x in range(10**7)]        # 列表：立刻算完，占内存
                    (x * 2 for x in range(10**7))        # 生成器：惰性、省内存
                    ```

                    ⚠️ 数据量大时用生成器：它不一次性建出所有元素，而是用的时候才算一个。
                    代价是**只能遍历一次**，且不能下标访问。

                    一个完整的小流程：读文件 → 逐行解析 → 用字典聚合 → 输出。
                    用生成器逐行读，几百万行也不会把内存吃满：

                    ```python
                    def parse(lines):
                        for line in lines:
                            key, value = line.split(",")
                            yield key, int(value)
                    ```

                    应用场景：日志统计、报表导出、数据清洗 —— 这是 Python 最主流的用途之一。
                    """,
                    """
                    # 生成器：惰性求值，不一次性占内存
                    def parse(text):
                        for line in text.strip().split(chr(10)):
                            key, value = line.split(",")
                            yield key, int(value)

                    raw = "apple,3" + chr(10) + "banana,5" + chr(10) + "apple,2"

                    total = {}
                    for key, value in parse(raw):      # 逐条处理
                        total[key] = total.get(key, 0) + value
                    print(total)

                    # 列表 vs 生成器的内存差别（这里用小数字演示语义）
                    gen = (x * 2 for x in range(5))
                    print(list(gen))                   # 生成器只能遍历一次
                    print(list(gen))                   # 第二次已经是空的
                    """,
                    "python",
                    List.of(
                            quiz("生成器表达式 `(x for x in data)` 相比列表推导 `[x for x in data]` 的核心优势是？",
                                    List.of("跑得更快", "惰性求值、不一次性占用内存", "结果更准确", "支持随机访问"), 1,
                                    "生成器用一个算一个，适合大数据量或流式处理。"
                                            + "代价是只能遍历一次、也不能用下标访问 —— 需要多次用就还是得转成列表。"),
                            quiz("为什么要给每个项目建独立的虚拟环境（venv）？",
                                    List.of("能提升运行速度", "把依赖隔离在各项目内，避免全局装包导致版本冲突", "Python 强制要求", "方便打包发布"), 1,
                                    "两个项目分别依赖 A 库的 1.x 和 2.x 时，全局环境只能留一个版本 —— 必然有一个跑不起来。"
                                            + "venv 让每个项目的依赖各管各的，也让 `pip freeze` 出来的清单可复现。"))));

    // --------------------------------------------------------------------------------
    // SQL · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> SQL_ADV_LESSONS = List.of(
            lesson("sql-04", "子查询与 CTE：把复杂查询拆开", 25,
                    """
                    一个查询里嵌套另一个查询，就是子查询。三种常见位置：

                    ```sql
                    -- 1) 在 WHERE 里当条件
                    SELECT name FROM users
                    WHERE id IN (SELECT user_id FROM orders WHERE amount > 100)

                    -- 2) 在 FROM 里当临时表（必须起别名）
                    SELECT t.city, t.cnt FROM (
                      SELECT city, COUNT(*) AS cnt FROM users GROUP BY city
                    ) t WHERE t.cnt > 10

                    -- 3) 在 SELECT 里当标量（只能返回一个值）
                    SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_cnt
                    FROM users u
                    ```

                    **CTE（WITH）**是对付复杂查询的利器 —— 把每一步命名，从上往下读：

                    ```sql
                    WITH paid AS (
                      SELECT * FROM orders WHERE status = 'paid'
                    ),
                    per_user AS (
                      SELECT user_id, SUM(amount) AS total FROM paid GROUP BY user_id
                    )
                    SELECT u.name, p.total
                    FROM per_user p JOIN users u ON u.id = p.user_id
                    WHERE p.total > 1000
                    ```

                    ⚠️ 两个要点：

                    1. `IN + 子查询` 里如果子查询可能返回 `NULL`，`NOT IN` 会**全部为假**（NULL 参与比较结果是 unknown）——
                       这种场景改用 `NOT EXISTS`
                    2. CTE 比层层嵌套的子查询**可读性高一个量级**，优先用它；但要注意它不一定能提升性能
                       （有的数据库会把 CTE 当优化屏障）

                    应用场景：多步聚合、先过滤再关联的报表查询 —— 用 CTE 把「每一层在干什么」写清楚。
                    """,
                    """
                    WITH paid AS (
                      SELECT * FROM orders WHERE status = 'paid'
                    ),
                    per_user AS (
                      SELECT user_id, SUM(amount) AS total FROM paid GROUP BY user_id
                    )
                    SELECT u.name, p.total
                    FROM per_user p JOIN users u ON u.id = p.user_id
                    WHERE p.total > 100;

                    -- 对照：不用 CTE 的嵌套写法（能跑，但难读）
                    SELECT u.name, t.total
                    FROM (SELECT user_id, SUM(amount) AS total FROM orders
                          WHERE status = 'paid' GROUP BY user_id) t
                    JOIN users u ON u.id = t.user_id;
                    """,
                    "sql",
                    List.of(
                            quiz("`WHERE id NOT IN (SELECT user_id FROM orders)` 在子查询结果含 NULL 时会怎样？",
                                    List.of("正常返回所有未下单用户", "一行都不返回 —— NOT IN 遇上 NULL 恒为 unknown", "报语法错误", "只返回第一条"), 1,
                                    "`x NOT IN (1, NULL)` 等价于 `x != 1 AND x != NULL`，而任何值与 NULL 比较结果都是 unknown（不为真），"
                                            + "于是整行被过滤掉。这种场景要用 `NOT EXISTS`。"),
                            quiz("把嵌套子查询改写成 CTE（WITH）的主要好处是？",
                                    List.of("一定更快", "可读性 —— 每一步有名字、从上往下读", "能省内存", "能自动加索引"), 1,
                                    "CTE 的价值首先是**可维护**：复杂查询拆成命名步骤，改一层不影响其它层。"
                                            + "性能上视数据库而定，有些会把 CTE 物化反而更慢，别默认「用了 CTE 就快了」。"))),

            lesson("sql-05", "窗口函数：分组后还想保留明细", 25,
                    """
                    `GROUP BY` 会把多行压成一行 —— 但你经常想「既要分组排名，又要保留每一行明细」，
                    这时就要用**窗口函数**：

                    ```sql
                    SELECT name, city, salary,
                           ROW_NUMBER() OVER (PARTITION BY city ORDER BY salary DESC) AS rn,
                           RANK()       OVER (PARTITION BY city ORDER BY salary DESC) AS rk,
                           AVG(salary)  OVER (PARTITION BY city) AS city_avg
                    FROM employees
                    ```

                    `OVER (...)` 里两件事：

                    - `PARTITION BY`：按什么分组（不写就是整张表一个窗口）
                    - `ORDER BY`：组内怎么排

                    三个排名函数的区别常考：

                    | 函数 | 并列时 |
                    |---|---|
                    | `ROW_NUMBER` | 给不同编号（1,2,3） |
                    | `RANK` | 并列同名，**跳号**（1,1,3） |
                    | `DENSE_RANK` | 并列同名，**不跳号**（1,1,2） |

                    ⚠️ 窗口函数**不能写在 WHERE 里** —— WHERE 比窗口计算先执行。
                    要按排名过滤，得套一层子查询或 CTE：

                    ```sql
                    SELECT * FROM (
                      SELECT *, ROW_NUMBER() OVER (PARTITION BY city ORDER BY salary DESC) AS rn
                      FROM employees
                    ) t WHERE t.rn = 1        -- 每个城市工资最高的那位
                    ```

                    应用场景：**组内 Top N**（每个分类销量前三）、**同比环比**、**累计求和** —— 报表
                    场景的刚需。
                    """,
                    """
                    -- 每个城市工资最高的员工（组内 Top 1）
                    SELECT * FROM (
                      SELECT name, city, salary,
                             ROW_NUMBER() OVER (PARTITION BY city ORDER BY salary DESC) AS rn
                      FROM employees
                    ) t
                    WHERE t.rn = 1;

                    -- 排名函数并列时的差别
                    SELECT name, salary,
                           ROW_NUMBER() OVER (ORDER BY salary DESC) AS rn,
                           RANK()       OVER (ORDER BY salary DESC) AS rk,
                           DENSE_RANK() OVER (ORDER BY salary DESC) AS dr
                    FROM employees;
                    """,
                    "sql",
                    List.of(
                            quiz("按工资降序排列时，`RANK()` 与 `DENSE_RANK()` 遇到并列第二会分别给出什么？",
                                    List.of("都给 2,2,3", "RANK 给 1,1,3；DENSE_RANK 给 1,1,2", "RANK 给 1,2,3；DENSE_RANK 给 1,2,2", "完全一样"), 1,
                                    "RANK 并列后**跳号**（下一个是 3），DENSE_RANK 不跳号（下一个是 2）；"
                                            + "ROW_NUMBER 则不管并列不给重复编号（1,2,3）。要「第几名」用 RANK/DENSE_RANK，要「取几条」用 ROW_NUMBER。"),
                            quiz("`WHERE ROW_NUMBER() OVER (ORDER BY salary DESC) = 1` 能跑吗？",
                                    List.of("可以", "不行 —— 窗口函数在 WHERE 之后才计算，必须套子查询/CTE", "只有 MySQL 不行", "需要加 PARTITION BY"), 1,
                                    "SQL 的执行顺序是 FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY（窗口函数在 SELECT 阶段算），"
                                            + "所以 WHERE 时它还不存在。包一层子查询把结果当作普通列再筛即可。"))),

            lesson("sql-06", "索引与执行计划：为什么查询会慢", 20,
                    """
                    数据量小的时候怎么写都快，一到几百万行就会现原形。先建立一条直觉：

                    **没有索引 = 全表扫描**（逐行比对），有索引 = 先查目录再定位。

                    ```sql
                    CREATE INDEX idx_orders_user ON orders(user_id);
                    EXPLAIN QUERY PLAN SELECT * FROM orders WHERE user_id = 42;
                    ```

                    看执行计划时找关键词：出现 `SCAN`（全表扫）通常是问题，`SEARCH ... USING INDEX` 才是走了索引。

                    ⚠️ 五条最实用的规矩：

                    1. **索引建在「用在 WHERE / JOIN / ORDER BY 的列」上**，不是越多越好 ——
                       每个索引都会拖慢写入，并占空间
                    2. **联合索引遵循「最左前缀」**：建了 `(a, b)`，则 `WHERE a = 1` 能用、`WHERE b = 2` **不能**
                    3. 在索引列上做运算/函数会**失效**：`WHERE YEAR(created_at) = 2026` 用不到索引，
                       改成范围比较 `WHERE created_at >= '2026-01-01'`
                    4. `LIKE '%abc'`（前置通配）用不到索引，`LIKE 'abc%'` 可以
                    5. 查询返回的列如果都在索引里（覆盖索引），可以不再回表

                    应用场景：接口慢查询排查的固定套路 —— 先 `EXPLAIN`，再看有没有走索引，
                    最后才考虑改 SQL 或加索引。
                    """,
                    """
                    CREATE INDEX idx_orders_user ON orders(user_id);

                    -- 走索引：能从 EXPLAIN 输出里看到 USING INDEX
                    EXPLAIN QUERY PLAN
                    SELECT * FROM orders WHERE user_id = 42;

                    -- 反面例子：在列上用函数，索引失效
                    EXPLAIN QUERY PLAN
                    SELECT * FROM orders WHERE YEAR(created_at) = 2026;

                    -- 改成范围比较就能用上索引
                    EXPLAIN QUERY PLAN
                    SELECT * FROM orders WHERE created_at >= '2026-01-01';
                    """,
                    "sql",
                    List.of(
                            quiz("建了联合索引 `(a, b)`，下面哪个查询用不上它？",
                                    List.of("WHERE a = 1", "WHERE a = 1 AND b = 2", "WHERE b = 2", "WHERE a = 1 ORDER BY b"), 2,
                                    "联合索引像一本按「先姓后名」排的电话簿：只给「名」（b）是查不了的。"
                                            + "这就是**最左前缀**规则 —— 要单独按 b 查，就得为 b 再建一个索引。"),
                            quiz("`WHERE YEAR(created_at) = 2026` 为什么通常用不到 created_at 上的索引？",
                                    List.of("因为 YEAR 是保留字", "列被函数包住后索引里存的原始值对不上了，只能逐行算", "因为 created_at 是日期类型", "其实能用"), 1,
                                    "索引存的是列的原始值，一旦套上函数，数据库没法用「按值定位」的目录去查。"
                                            + "改成 `created_at >= '2026-01-01' AND created_at < '2027-01-01'` 就能走索引。"))));

    // --------------------------------------------------------------------------------
    // SQL · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> SQL_PRO_LESSONS = List.of(
            lesson("sql-07", "经典业务查询：分页、去重、留存", 25,
                    """
                    这一节把语法用在真实需求上 —— 三个几乎每个项目都会遇到的花样。

                    **① 分页**。`LIMIT offset, size` 直观，但**深分页会越来越慢**：

                    ```sql
                    SELECT * FROM orders ORDER BY id LIMIT 20 OFFSET 100000;   -- 翻到第 5000 页
                    ```

                    数据库仍要先把前 100020 行扫出来再丢掉。优化思路是用**游标（keyset）分页**：
                    记住上一页最后一行的 id，从那里往后取：

                    ```sql
                    SELECT * FROM orders WHERE id > 100020 ORDER BY id LIMIT 20;
                    ```

                    ⚠️ 前提是排序字段唯一且有序 —— 这正好是主键。

                    **② 去重**。要「每个用户的最近一条订单」：

                    ```sql
                    SELECT * FROM (
                      SELECT *, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC) AS rn
                      FROM orders
                    ) t WHERE t.rn = 1
                    ```

                    **③ 留存**。要「今天下单、且昨天也下单的用户」：

                    ```sql
                    SELECT DISTINCT a.user_id
                    FROM orders a JOIN orders b
                      ON a.user_id = b.user_id
                     AND b.order_date = date(a.order_date, '-1 day')
                    ```

                    ⚠️ 三处常见疏漏：分页没固定排序（结果会重复/丢失）、
                    去重只按一个字段 `GROUP BY`（拿到的是「某个值」而不是「那一整行」）、
                    自连接忘了加时间条件（变成笛卡尔积）。

                    应用场景：后台列表、报表、用户行为分析 —— 这三条基本覆盖日常查询的八成。
                    """,
                    """
                    -- ① 深分页：游标法（比 OFFSET 大数字快得多）
                    SELECT * FROM orders WHERE id > 100020 ORDER BY id LIMIT 20;

                    -- ② 每个用户最近一条订单
                    SELECT * FROM (
                      SELECT *, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC) AS rn
                      FROM orders
                    ) t WHERE t.rn = 1;

                    -- ③ 连续两天都下单的用户
                    SELECT DISTINCT a.user_id
                    FROM orders a
                    JOIN orders b ON a.user_id = b.user_id
                     AND b.order_date = date(a.order_date, '-1 day');
                    """,
                    "sql",
                    List.of(
                            quiz("为什么 `LIMIT 20 OFFSET 100000` 会越来越慢？",
                                    List.of("因为 LIMIT 本身慢", "数据库仍要扫描并丢弃前面 10 万行", "OFFSET 不支持索引", "因为没加 ORDER BY"), 1,
                                    "OFFSET 的语义是「先数过去再取」，扫描量随页码线性增长。"
                                            + "游标分页（记住上一页末位的 id，用 `WHERE id > ?` 往后取）能把每页代价拉回常数级。"),
                            quiz("用 `SELECT user_id, MAX(created_at) FROM orders GROUP BY user_id` 想拿「每个用户最近那**一整行**」，问题在哪？",
                                    List.of("语法错误", "只能拿到聚合出来的字段，拿不到那一行的其它列（如金额）", "性能太差", "没有 PARTITION BY"), 1,
                                    "`GROUP BY` 后其它列要么被聚合、要么值不确定，所以只能拿到时间，拿不到那笔订单的金额。"
                                            + "要「整行」得用窗口函数 `ROW_NUMBER() ... = 1` 或自连接。"))),

            lesson("sql-08", "事务与并发：别把数据写坏", 20,
                    """
                    转账要「扣 A 加 B」，两步必须**一起成功或一起失败** —— 这就是事务。

                    ```sql
                    BEGIN;
                    UPDATE accounts SET balance = balance - 100 WHERE id = 1;
                    UPDATE accounts SET balance = balance + 100 WHERE id = 2;
                    COMMIT;      -- 出错就 ROLLBACK
                    ```

                    事务的四个特性（ACID）：原子性（全做或全不做）、一致性（约束不被破坏）、
                    隔离性（并发互不干扰）、持久性（提交后不丢）。

                    **隔离级别**是「性能」和「正确性」的旋钮，级别越低并发越好、越容易出怪现象：

                    | 级别 | 挡住的怪现象 |
                    |---|---|
                    | 读未提交 | 基本不挡 |
                    | 读已提交 | 脏读 |
                    | 可重复读 | 脏读 + 不可重复读（MySQL 默认，靠 MVCC） |
                    | 串行化 | 以上全部（含幻读），但并发最差 |

                    ⚠️ 并发写时的经典问题：**丢失更新**（两个人同时读到 100，各自 +10，最后是 110 不是 120）。
                    两种解法：

                    1. **原子更新**：`UPDATE ... SET n = n + 1`（让数据库在一条语句里读改写）
                    2. **乐观锁**：加版本号 `UPDATE ... SET n = ?, version = version + 1 WHERE id = ? AND version = ?`，
                       影响行数为 0 说明被别人改过，重试

                    应用场景：下单扣库存、余额变动、状态机流转 —— 凡是「读出来再写回去」都有并发风险。
                    """,
                    """
                    -- 事务：要么全成功、要么全回滚
                    BEGIN;
                    UPDATE accounts SET balance = balance - 100 WHERE id = 1;
                    UPDATE accounts SET balance = balance + 100 WHERE id = 2;
                    COMMIT;

                    -- 原子更新：把「读改写」压在一条语句里，避免丢失更新
                    UPDATE counters SET n = n + 1 WHERE id = 7;

                    -- 乐观锁：版本号不匹配就更新 0 行，由应用层决定重试
                    UPDATE counters
                       SET n = 5, version = version + 1
                     WHERE id = 7 AND version = 3;
                    """,
                    "sql",
                    List.of(
                            quiz("两个人同时执行「读到 100，各自加 10，再写回」，最终结果通常是 110 而不是 120，这叫什么？",
                                    List.of("脏读", "幻读", "丢失更新", "死锁"), 2,
                                    "两次「读-改-写」交错，后写的覆盖了先写的。"
                                            + "解法：把运算放进一条 SQL（`SET n = n + 1`，数据库自己保证原子），或用乐观锁/悲观锁。"),
                            quiz("「可重复读」隔离级别下，MySQL 主要靠什么机制实现，让同一事务里两次读到的数据一致？",
                                    List.of("全局加锁", "MVCC（多版本并发控制）", "把数据复制一份", "禁用并发写"), 1,
                                    "MVCC 给每行保留多个版本，读的时候按事务开始的时间点取对应快照，所以读不阻塞写、写也不阻塞读。"
                                            + "这是 MySQL 默认级别比「串行化」并发好得多的原因。"))));

    // --------------------------------------------------------------------------------
    // Lua · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> LUA_ADV_LESSONS = List.of(
            lesson("lua-04", "迭代器与泛型 for：pairs 和 ipairs 的区别", 15,
                    """
                    遍历 table 有两种写法，选错了会**悄悄少遍历元素**：

                    ```lua
                    for i, v in ipairs(t) do end    -- 从 1 开始连续取，遇到 nil 就停
                    for k, v in pairs(t)  do end    -- 遍历所有键值对（顺序不保证）
                    ```

                    ⚠️ 关键差别：

                    - `ipairs` 从 1 一直取到**第一个 nil** 就停 —— 表中间有空洞时后面的元素全看不到
                    - `pairs` 遍历全部键，但**顺序是哈希顺序**，不要依赖它（每次运行可能不同）

                    ```lua
                    local t = { "a", nil, "c" }
                    print(#t)                      -- 1 或 3（未定义行为）
                    for i, v in ipairs(t) do print(i, v) end   -- 只打印 1 a
                    ```

                    **自定义迭代器**：泛型 for 期待的第一个返回值就是迭代函数。写一个「按步长取偶数」的：

                    ```lua
                    local function evens(n)
                      local i = 0
                      return function()       -- 迭代函数
                        i = i + 2
                        if i <= n then return i end
                      end
                    end
                    for v in evens(10) do io.write(v, " ") end   -- 2 4 6 8 10
                    ```

                    应用场景：读配置表（`pairs` 遍历所有项）、按顺序处理数组（`ipairs`）。
                    **只要表可能是稀疏的，就用 `pairs`**。
                    """,
                    """
                    -- ipairs 遇 nil 就停
                    local sparse = { "a", nil, "c" }
                    for i, v in ipairs(sparse) do
                      print("ipairs", i, v)      -- 只输出第 1 项
                    end

                    -- pairs 全部遍历，但顺序不保证
                    local conf = { host = "localhost", port = 8080 }
                    for k, v in pairs(conf) do
                      print("pairs", k, v)
                    end

                    -- 自定义迭代器：泛型 for 的第一个返回值就是迭代函数
                    local function evens(n)
                      local i = 0
                      return function()
                        i = i + 2
                        if i <= n then return i end
                      end
                    end
                    for v in evens(10) do print("even", v) end
                    """,
                    "lua",
                    List.of(
                            quiz("`local t = { \"a\", nil, \"c\" }` 用 `ipairs(t)` 会遍历到几个元素？",
                                    List.of("3 个", "1 个 —— 遇到第一个 nil 就停", "2 个", "报错"), 1,
                                    "`ipairs` 的定义就是从下标 1 递增、遇到 nil 终止。表中间有空洞时，nil 之后的元素都取不到。"
                                            + "这种表要用 `pairs`（代价是顺序不保证）。"),
                            quiz("`pairs` 遍历的顺序可以依赖吗？",
                                    List.of("可以，总是按插入顺序", "可以，总是按键升序", "不可以 —— 哈希顺序，不保证稳定", "取决于 Lua 版本"), 2,
                                    "`pairs` 走的是表的哈希部分，顺序与哈希分布有关，既不是插入顺序也不是排序顺序。"
                                            + "需要稳定顺序就得先把键取出来排序（`table.sort`）再遍历。"))),

            lesson("lua-05", "错误处理与协程：pcall 与 coroutine", 20,
                    """
                    Lua 的错误处理很轻：出问题用 `error` 抛出，用 `pcall` 捕获。
                    **没有 try/catch**，取而代之的是「返回状态 + 结果」的写法。

                    ```lua
                    local ok, err = pcall(function()
                      error("出错了")
                    end)
                    print(ok, err)      -- false   ...: 出错了
                    ```

                    `pcall` 返回两个值：**第一个是布尔**（成功与否），后面跟返回值或错误信息。
                    这正是上一节「多返回值」的典型用法。

                    ⚠️ 三条要点：

                    1. `pcall` 保护的是**整个调用**，用它包住可能失败的边界（解析配置、调外部接口）
                    2. `xpcall` 可以传一个错误处理函数，在栈展开前拿到更多信息（如 traceback）
                    3. `error("msg")` 里的 msg 若是**非字符串**（比如 table），抛出时原样传回，
                       适合带结构化错误信息

                    **协程（coroutine）**是独立于错误处理的另一件事：让函数能「暂停 / 恢复」。
                    最典型的用途是**用同步写法表达异步流程**，以及生产者-消费者：

                    ```lua
                    local co = coroutine.create(function()
                      for i = 1, 3 do coroutine.yield(i) end
                    end)
                    print(coroutine.resume(co))   -- true 1
                    print(coroutine.resume(co))   -- true 2
                    ```

                    ⚠️ `resume` 同样返回 `(ok, value)`，失败时第二个值是错误信息，别直接当成结果用。

                    应用场景：游戏脚本里等待动画/网络结果 —— 写起来像阻塞，实际不卡主循环。
                    """,
                    """
                    -- pcall：返回 (是否成功, 结果或错误)
                    local ok, err = pcall(function()
                      error("配置读取失败")
                    end)
                    print("pcall:", ok, err)

                    -- 成功时第二个值是正常返回值
                    local ok2, v = pcall(function() return 42 end)
                    print("成功:", ok2, v)

                    -- 错误对象可以是 table，便于带结构化信息
                    local ok3, e = pcall(function() error({ code = 500 }) end)
                    print("结构化错误:", ok3, e.code)

                    -- 协程：可暂停可恢复
                    local co = coroutine.create(function()
                      for i = 1, 3 do coroutine.yield(i) end
                    end)
                    print("co1:", coroutine.resume(co))
                    print("co2:", coroutine.resume(co))
                    """,
                    "lua",
                    List.of(
                            quiz("`pcall` 的返回值是什么？",
                                    List.of("只有错误信息", "第一个是布尔（是否成功），后面跟返回值或错误", "只有返回值", "一个协程对象"), 1,
                                    "这是 Lua 的惯用法：`local ok, result = pcall(f)`。"
                                            + "因为返回多值，所以不成功时 result 里装的是错误信息 —— 用之前一定要先判断 ok。"),
                            quiz("`coroutine.resume(co)` 返回的第二个值可能是什么？",
                                    List.of("永远是正确的返回值", "可能是协程 yield 的值，也可能是错误信息（看第一个值是否为 true）", "永远是 nil", "协程的 id"), 1,
                                    "resume 也是「状态 + 值」的结构：第一个值为 true 时第二个是 yield 出来的值，"
                                            + "为 false 时第二个是错误信息。直接当结果用是常见 bug。"))));

    // --------------------------------------------------------------------------------
    // Lua · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> LUA_PRO_LESSONS = List.of(
            lesson("lua-06", "模块与 require：把代码组织起来", 15,
                    """
                    Lua 没有内建 class，也**没有强制性的模块语法** —— 约定是「一个文件返回一个 table」。

                    ```lua
                    -- mathx.lua
                    local M = {}
                    function M.add(a, b) return a + b end
                    function M.sub(a, b) return a - b end
                    return M
                    ```

                    用的时候：

                    ```lua
                    local mathx = require("mathx")
                    print(mathx.add(1, 2))
                    ```

                    ⚠️ 四条实战约定：

                    1. **每个模块文件最后 `return` 一个 table** —— 这是唯一的约定，Lua 就这样认
                    2. `require` 有**缓存**：同一个模块只会执行一次，多次 require 拿到的是同一份
                       （所以模块里的状态天然是「单例」）
                    3. `local M = {}` 前面的 `local` 别省 —— 写成全局的 `M = {}` 会污染全局命名空间
                    4. 局部变量要 `local`，否则默认全局：这是 Lua 最该养成的习惯

                    **组合起来就是面向对象**：模块返回「类」，类的实例用上一节学的 metatable 创建。

                    应用场景：把配置、工具函数、业务逻辑拆成文件 —— 这也是为什么游戏引擎（如 Love2D、
                    OpenResty）的 Lua 代码都是「一堆 require」。
                    """,
                    """
                    -- 模拟一个模块文件 mathx.lua 的内容
                    local M = {}
                    function M.add(a, b) return a + b end
                    function M.sub(a, b) return a - b end

                    -- require 有缓存：这里直接复用同一张表
                    local mathx = M
                    print(mathx.add(3, 4), mathx.sub(9, 2))

                    -- ⚠️ 不写 local 就是全局变量（下面这行会污染全局，仅作演示）
                    -- total = 0

                    -- 模块里用 local 保存私有状态
                    local counter = dofile_counter and dofile_counter or 0
                    local function bump() counter = counter + 1 return counter end
                    print("bump:", bump(), bump())
                    """,
                    "lua",
                    List.of(
                            quiz("Lua 模块的约定是？",
                                    List.of("用 module 关键字声明", "文件最后 return 一个 table", "必须叫同名的全局变量", "用 export 导出"), 1,
                                    "Lua 只认「`require(名字)` 返回什么」——默认按文件名找到文件并执行，取它的返回值。"
                                            + "所以「一个文件 return 一个 table」就是全部约定，没有任何语法糖。"),
                            quiz("两次 `require(\"config\")` 会执行几次模块文件？",
                                    List.of("2 次", "1 次 —— require 有缓存，之后直接返回同一份结果", "取决于文件是否变化", "0 次"), 1,
                                    "require 把已加载模块记在 `package.loaded` 里，第二次直接取缓存。"
                                            + "这带来一个副作用：**模块里的变量天然是单例**，可以当共享状态用（也要小心被意外共享）。"))),

            lesson("lua-07", "元表实战：只读表、默认值与 __index 链", 20,
                    """
                    上一节学过 metatable 的 `__index` 是「找不到就往上找」。这一节看它怎么解决真实问题。

                    **① 给表设默认值**：让缺失的键返回 0，而不是 nil。

                    ```lua
                    local function withDefault(t, d)
                      return setmetatable(t, { __index = function() return d end })
                    end
                    local conf = withDefault({}, 0)
                    print(conf.retry)      -- 0（表里没有，走默认）
                    ```

                    **② 只读表**：用 `__newindex` 拦住写入。

                    ```lua
                    local function readonly(t)
                      return setmetatable({}, {
                        __index = t,
                        __newindex = function() error("这张表是只读的") end,
                      })
                    end
                    local cfg = readonly({ host = "localhost" })
                    print(cfg.host)        -- localhost
                    -- cfg.host = "x"      -- 抛错
                    ```

                    ⚠️ 两个要点：

                    1. **`__newindex` 只在键「不存在」时触发** —— 要拦住对已有键的修改，得让代理表本身为空
                       （上面就是这么做的：数据放在被代理的 `t` 里）
                    2. `__index` 可以是函数，也可以是**另一张表**。是表时，查找会沿这条链继续往下 ——
                       这就是「原型链」式的继承

                    应用场景：**配置对象**（缺省值 + 防误改）是元表最实用的落地场景之一。
                    元方法还有 `__call`（对象可当函数调）、`__tostring`（自定义打印）、
                    `__add`（重载 `+`）等，需要时再查即可。
                    """,
                    """
                    -- ① 默认值 —— 缺的键返回 0 而不是 nil
                    local function withDefault(d)
                      return setmetatable({}, { __index = function() return d end })
                    end
                    local conf = withDefault(0)
                    print("默认值:", conf.retry)     -- 0

                    -- ② 只读表 —— 写入就报错
                    local function readonly(t)
                      return setmetatable({}, {
                        __index = t,
                        __newindex = function() error("这张表是只读的") end,
                      })
                    end
                    local cfg = readonly({ host = "localhost", port = 8080 })
                    print("读:", cfg.host, cfg.port)

                    local ok, err = pcall(function() cfg.host = "hacked" end)
                    print("写:", ok, err)
                    """,
                    "lua",
                    List.of(
                            quiz("`__newindex` 在什么时候被触发？",
                                    List.of("每次写任意键时", "只在写的键**不存在**于原表时", "只在读的时候", "只在表被销毁时"), 1,
                                    "已存在的键会直接赋值、不经过 `__newindex`。所以「只读代理」的写法是让代理表本身为空，"
                                            + "数据全放在被代理的表里，这样任何写入都是「不存在的键」→ 必然触发拦截。"),
                            quiz("`__index` 设成**一张表**而不是函数时，会发生什么？",
                                    List.of("报错", "查找会沿着这张表继续往下找，形成链式的继承结构", "只查一层就停", "表和函数不能互换"), 1,
                                    "正是这个「链」让 Lua 能用 metatable 搭出面向对象：实例的 `__index` 指向类表，"
                                            + "类表的 `__index` 又可以指向父类 —— 属性查找就沿链一路上溯。"))));

    // --------------------------------------------------------------------------------
    // C / C++ · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> CPP_ADV_LESSONS = List.of(
            lesson("c-04", "结构体与内存布局", 20,
                    """
                    结构体是把几个字段打包成一个整体 —— 但它在内存里**不一定是你写的那个顺序大小**。

                    ```c
                    struct Point { int x; int y; };      /* 8 字节 */
                    struct Bad { char c; int n; };        /* 你以为是 5，其实是 8 */
                    ```

                    `Bad` 里 `char` 占 1 字节、`int` 占 4 字节，但编译器会在 `c` 后面塞 3 字节的
                    **填充（padding）**，让 `n` 落在 4 的倍数地址上 —— 这叫**内存对齐**。

                    为什么要有对齐？因为 CPU 读 4 字节整数时，对齐地址只需要一次总线操作。
                    **不对齐要么更慢，要么直接不被允许**（某些架构上会崩）。

                    ⚠️ 三条实战要点：

                    1. `sizeof(struct)` **包含填充**，别拿它当「字段大小之和」
                    2. **把字段按从大到小排**能减少填充。`{ char; int; char; }` 是 12 字节，
                       改成 `{ int; char; char; }` 只要 8 字节 —— 同样的字段，省三分之一
                    3. `#pragma pack(1)` 能关掉填充（做协议/文件格式时常用），但会牺牲访问速度，
                       且在某些平台无法直接访问成员

                    应用场景：**跨进程通信、网络协议、二进制文件读写**时，内存布局就是协议本身，
                    差一个字节就对不上 —— 这也是为什么这些场景都要显式指定对齐。
                    """,
                    """
                    #include <stdio.h>

                    struct Point { int x; int y; };
                    struct Bad   { char c; int n; };
                    struct Good  { int n; char a; char b; };

                    int main(void) {
                      puts("sizeof 包含填充，不等于字段之和：");
                      printf("Point = %zu（2 个 int）\\n", sizeof(struct Point));
                      printf("Bad   = %zu（char + int，你以为是 5）\\n", sizeof(struct Bad));
                      printf("Good  = %zu（换个顺序就变小了）\\n", sizeof(struct Good));

                      struct Point p = { 3, 4 };
                      printf("字段访问：%d %d\\n", p.x, p.y);
                      return 0;
                    }
                    """,
                    "c",
                    List.of(
                            quiz("`struct { char c; int n; }` 的 `sizeof` 通常是 8 而不是 5，为什么？",
                                    List.of("char 也占 4 字节", "编译器插入了 3 字节填充以满足内存对齐", "编译器有 bug", "int 是 8 字节"), 1,
                                    "对齐要求 `int` 落在 4 的倍数地址上，所以 `c` 后面补 3 个空字节。"
                                            + "这解释了「明明只有 5 字节数据，结构体却占 8 字节」这个高频疑问。"),
                            quiz("把结构体字段按「从大到小」排列能带来什么好处？",
                                    List.of("读取更快", "减少填充字节，结构体更小、更省内存与带宽", "自动对齐", "可以让字段变公有"), 1,
                                    "填充是为了对齐而插的空洞，把大字段放前面能显著减少空洞。"
                                            + "在高频创建的结构体（如游戏实体、网络包）上，这一项能省下可观的内存。"))),

            lesson("c-05", "编译、链接与头文件", 20,
                    """
                    从 `.c` 到可执行文件经过四步：**预处理 → 编译 → 汇编 → 链接**。
                    大多数「莫名奇妙的错误」都出在第一步和最后一步。

                    **头文件（.h）放声明，源文件（.c）放定义**：

                    ```c
                    /* mathx.h —— 只声明「有什么」 */
                    int add(int a, int b);

                    /* mathx.c —— 定义「怎么做」 */
                    #include "mathx.h"
                    int add(int a, int b) { return a + b; }

                    /* main.c —— 用 */
                    #include "mathx.h"
                    ```

                    ⚠️ 五个必须知道的东西：

                    1. `#include` 是**文本替换**：把文件内容原样粘进来。所以头文件里要写**头文件保护**
                       （`#ifndef` / `#pragma once`），否则重复包含会「重复定义」
                    2. `""` 先找当前目录，`<>` 只找系统路径 —— 引自己的头文件用双引号
                    3. **声明 vs 定义**：声明只是告诉编译器「有这个东西」（可以出现多次），
                       定义是真正分配存储（一个程序里只能有一次）
                    4. 编译报 **`undefined reference`** 是**链接**错误 —— 说明声明有、定义没被链进来
                       （文件没加入编译、或库没加 `-l`）
                    5. `static` 修饰全局变量/函数 → **只在本文件可见**；修饰函数内局部变量 → 生命周期变成整个程序

                    应用场景：多文件项目的组织方式。理解这一步之后再看到
                    「undefined reference」「multiple definition」就知道分别该往哪查。
                    """,
                    """
                    #include <stdio.h>

                    /* 头文件保护：避免同一个头被包含多次导致重复定义 */
                    #ifndef MATHX_H
                    #define MATHX_H

                    /* 声明：告诉编译器「有这么个函数」，不分配存储 */
                    static int add(int a, int b);

                    #endif

                    /* 定义：真正实现（static 让它在本文件可见即可） */
                    static int add(int a, int b) { return a + b; }

                    int main(void) {
                      printf("add(3,4) = %d\\n", add(3, 4));

                      /* 函数内 static：只初始化一次，且生命周期是整个程序 */
                      static int call_count = 0;
                      call_count++;
                      printf("call_count = %d\\n", call_count);
                      return 0;
                    }
                    """,
                    "c",
                    List.of(
                            quiz("编译出现 `undefined reference to 'add'` 属于哪个阶段的问题？",
                                    List.of("预处理", "编译", "链接 —— 声明有但定义没被链进来", "运行"), 2,
                                    "编译器看到声明就放行，链接器才负责把符号和实现对上。"
                                            + "报这个错要查：定义那个 .c 有没有参与编译、库有没有用 `-l` 加上、函数签名是否一致。"),
                            quiz("头文件里为什么要写 `#ifndef ... #define ... #endif`（或 `#pragma once`）？",
                                    List.of("提升编译速度", "防止同一个头文件被多次包含导致重复定义", "让函数变成静态的", "压缩代码体积"), 1,
                                    "`#include` 是纯文本粘贴，A 和 B 都包含 C 时 C 的内容就出现两次 —— "
                                            + "类型/宏/内联函数会因此「重复定义」。头文件保护是把这个粘进来的动作限定为一次。"))),

            lesson("c-06", "C++ 模板与 STL 容器", 25,
                    """
                    C++ 和 C 最大的分工差异之一：**C++ 有标准库，很多轮子不用自己造**。

                    **模板**让同一份代码适配多种类型，编译期展开（所以没有运行时开销）：

                    ```cpp
                    template <typename T>
                    T maxOf(T a, T b) { return a > b ? a : b; }

                    maxOf(3, 4);          // T = int
                    maxOf(2.5, 1.5);      // T = double
                    ```

                    **STL 容器**是日常主力，先记四个和它们的取舍：

                    | 容器 | 特点 | 常用场景 |
                    |---|---|---|
                    | `std::vector` | 连续内存、随机访问快 | 默认选择，绝大多数情况 |
                    | `std::string` | 自动管理内存的字符串 | 替代 C 的 `char*` |
                    | `std::unordered_map` | 哈希表，查找 O(1) | 按 key 查 |
                    | `std::map` | 红黑树，**有序**，O(log n) | 需要按序遍历 |

                    ```cpp
                    std::vector<int> v = { 3, 1, 2 };
                    v.push_back(4);
                    std::sort(v.begin(), v.end());
                    for (int x : v) std::cout << x << ' ';
                    ```

                    ⚠️ 三个高频坑：

                    1. `vector` 扩容后**之前的指针/引用/迭代器全部失效**（内存被重新分配了）
                    2. `v[i]` **不做边界检查**，越界是未定义行为；要安全用 `v.at(i)`（越界抛异常）
                    3. `map` 和 `unordered_map` 用 `m[k]` 取不存在的键会**默认插入**一个 —— 只查用 `find` / `count`

                    应用场景：**先想「STL 里有没有现成的」再动手**。C++ 里手写链表/动态数组，
                    九成情况是在重复造一个更差的轮子。
                    """,
                    """
                    #include <iostream>
                    #include <vector>
                    #include <algorithm>
                    #include <unordered_map>

                    template <typename T>
                    T maxOf(T a, T b) { return a > b ? a : b; }

                    int main() {
                      std::cout << maxOf(3, 4) << " " << maxOf(2.5, 1.5) << std::endl;

                      std::vector<int> v = { 3, 1, 2 };
                      v.push_back(4);
                      std::sort(v.begin(), v.end());
                      for (int x : v) std::cout << x << ' ';
                      std::cout << std::endl;

                      std::unordered_map<std::string, int> age;
                      age["alice"] = 30;

                      // 只查不要用 m[k]，会默认插入一个 0
                      auto it = age.find("bob");
                      std::cout << "bob 存在？" << (it != age.end()) << std::endl;
                      std::cout << "size 仍是 " << age.size() << std::endl;
                      return 0;
                    }
                    """,
                    "cpp",
                    List.of(
                            quiz("`std::vector` 扩容之后，之前保存的元素指针还能用吗？",
                                    List.of("可以", "不能 —— 内存被重新分配，旧指针/迭代器全部失效", "只有第一个失效", "取决于元素类型"), 1,
                                    "vector 为保证连续存储，扩容时会申请一块更大的内存并搬过去，原地址随即释放。"
                                            + "这就是「遍历时 push_back」容易崩的原因 —— 要预留就用 `reserve`。"),
                            quiz("只想查询 `map` 里某个键是否存在，应该用？",
                                    List.of("m[key]", "m.at(key)", "m.find(key) != m.end()", "m[key] == nullptr"), 2,
                                    "`m[key]` 在键不存在时会**默认构造并插入**一个值，既改了容器又可能掩盖问题（还让 `size()` 变大）。"
                                            + "纯查询用 `find` 或 `count`。"))));

    // --------------------------------------------------------------------------------
    // C / C++ · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> CPP_PRO_LESSONS = List.of(
            lesson("c-07", "内存 bug 排查：段错误与内存泄漏", 25,
                    """
                    C/C++ 最花时间的不是写功能，而是排查内存问题。先能**认脸**：

                    | 现象 | 通常是 |
                    |---|---|
                    | Segmentation fault | 解引用空指针/野指针、越界写 |
                    | 程序跑一会儿内存涨 | new/malloc 了但没 delete/free |
                    | 结果时对时错 | **未定义行为**（越界、用已释放的内存） |
                    | double free | 同一块内存释放两次 |

                    **三类经典错误**：

                    ```c
                    int *p;            /* 未初始化 → 野指针 */
                    *p = 1;            /* 崩 */

                    int *q = malloc(sizeof(int));
                    free(q);
                    *q = 2;            /* 释放后使用（use-after-free） */

                    char buf[4];
                    strcpy(buf, "hello");   /* 越界写：会踩坏相邻内存 */
                    ```

                    ⚠️ 最坑的是「时对时错」：越界/释放后使用属于**未定义行为**，
                    今天跑通了不代表对，换个编译器或数据就崩。**别用「试了没问题」当证据。**

                    **正确姿势是用工具，而不是靠 printf**：

                    - **AddressSanitizer**（编译器自带）：`gcc -fsanitize=address -g main.c`
                      能直接指出越界、use-after-free 发生在哪一行，且给出调用栈
                    - **Valgrind**：`valgrind ./a.out`，擅长找内存泄漏与未初始化读
                    - 编译时开 `-Wall -Wextra`：很多低级错误编译期就会警告

                    应用场景：任何用 C/C++ 写的常驻服务，**上线前跑一遍 ASan/Valgrind 是标准动作** ——
                    它比线上崩了再啃 core dump 便宜得多。
                    """,
                    """
                    #include <stdio.h>
                    #include <stdlib.h>

                    int main(void) {
                      /* 正确做法：初始化 + 检查 malloc 是否成功 */
                      int *p = (int *)malloc(sizeof(int));
                      if (p == NULL) {
                        puts("分配失败");
                        return 1;
                      }
                      *p = 42;
                      printf("值 = %d\\n", *p);

                      /* 释放后立刻置空，避免误用（free(NULL) 是安全的） */
                      free(p);
                      p = NULL;

                      /* 上面这些「看起来没问题」的代码，
                         真正的检查手段是编译时加 -fsanitize=address 跑一遍 */
                      puts("提示：发布前用 gcc -fsanitize=address -g 再跑一次");
                      return 0;
                    }
                    """,
                    "c",
                    List.of(
                            quiz("程序「有时正常有时崩」，最可能是哪类问题？",
                                    List.of("编译器优化", "未定义行为（越界、释放后使用、未初始化）", "内存不够", "CPU 过热"), 1,
                                    "未定义行为的结果取决于内存里恰好是什么 —— 数据一变、编译器一换结果就不同。"
                                            + "所以「本机跑通了」不能作为证据，必须用 ASan/Valgrind 这类工具验证。"),
                            quiz("`free(p)` 之后马上写 `p = NULL`，好处是？",
                                    List.of("释放得更彻底", "避免之后误用这个已释放的指针（free(NULL) 本身是安全的）", "能防止内存泄漏", "可以提升性能"), 1,
                                    "悬空指针是最难查的 bug 之一。置空之后，万一还有代码去解引用它会立刻崩（好定位），"
                                            + "而不是悄悄地改坏一块已经属于别人的内存。"))),

            lesson("c-08", "性能：缓存友好与移动语义", 20,
                    """
                    写完能跑之后，下一步是「跑得快」。C/C++ 的性能优化里，**最容易被忽视的是内存访问模式**。

                    **① 缓存友好**：CPU 读内存是按「缓存行」（通常 64 字节）整块读的。
                    访问连续内存能白拿后面几十个字节，跳跃访问则每次都白跑一趟。

                    ```c
                    for (int i = 0; i < N; i++)
                      for (int j = 0; j < N; j++)
                        sum += a[i][j];        /* 行优先：顺着内存走，快 */
                    ```

                    把内外层循环对调（`a[j][i]`）结果一样，但速度可能差几倍 ——
                    因为 C 的二维数组是**行优先**存储，按列访问等于每次跳一行。

                    **② C++ 的移动语义**：拷贝大对象很贵，`std::move` 把资源「移交」而不是复制。

                    ```cpp
                    std::vector<int> makeBig() {
                      std::vector<int> v(1000000, 1);
                      return v;                  // 编译器会做 RVO / 移动，不会真的拷 100 万个 int
                    }
                    std::vector<int> a = makeBig();     // 移交所有权
                    ```

                    ⚠️ 三个要点：

                    1. `std::move` **本身不移动任何东西**，它只是把值转成「右值引用」，让移动构造函数有机会被调用
                    2. 被 move 之后的对象处于**有效但未指定**的状态 —— 别再假设里面的内容还在
                    3. 返回值优化（RVO）通常已经足够，**不要为了「性能」到处写 move**，
                       它有时反而会阻止编译器优化

                    应用场景：先拿 profiler 找到热点，再考虑这两件事。
                    **凭直觉优化往往优化错了地方**。
                    """,
                    """
                    #include <stdio.h>
                    #include <stdlib.h>

                    #define N 2000

                    int main(void) {
                      static int a[N][N];

                      /* 行优先遍历：顺着内存走，缓存命中高 */
                      long long s1 = 0;
                      for (int i = 0; i < N; i++)
                        for (int j = 0; j < N; j++)
                          s1 += a[i][j];

                      /* 列优先遍历：每次跳一行，缓存命中低（结果一样，慢得多） */
                      long long s2 = 0;
                      for (int j = 0; j < N; j++)
                        for (int i = 0; i < N; i++)
                          s2 += a[i][j];

                      printf("两种顺序结果相同：%lld %lld\\n", s1, s2);
                      puts("差别只在内存访问模式 —— 用 profiler 验证，别凭直觉");
                      return 0;
                    }
                    """,
                    "c",
                    List.of(
                            quiz("同样是「先拿 profiler 找热点」这个建议，为什么它比凭经验优化更可靠？",
                                    List.of("profiler 更准，且人的直觉经常猜错热点在哪", "因为 profiler 会自动改代码", "因为编译器要求这样做", "其实凭经验更快"), 0,
                                    "性能瓶颈常常出现在没人想到的地方（一次隐式的字符串拷贝、一个没走索引的查询）。"
                                            + "先测量、再优化，是避免「优化了半天、热点根本不在那」的唯一办法。"),
                            quiz("`std::move(x)` 本身做了什么？",
                                    List.of("把 x 的资源搬到新对象", "只是把 x 转成右值引用，让移动构造有机会被调用", "删除 x", "深拷贝 x"), 1,
                                    "`std::move` 是个类型转换（准确说是 `static_cast<T&&>`），不产生任何搬运动作。"
                                            + "真正干活的是随后被选中的移动构造/移动赋值函数。"))));

    // --------------------------------------------------------------------------------
    // Java · 进阶
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> JAVA_ADV_LESSONS = List.of(
            lesson("java-04", "泛型与类型擦除", 20,
                    """
                    泛型让容器「装什么类型」写进类型系统，编译期就能发现类型错误：

                    ```java
                    List<String> names = new ArrayList<>();
                    names.add("Alice");
                    // names.add(1);          // 编译期就报错
                    String first = names.get(0);   // 不用强转
                    ```

                    但没有泛型的年代，`List` 里什么都装、取出来都是 `Object`，
                    要靠强转，错误只能等运行时暴露 —— 泛型把这个问题提前到了编译期。

                    ⚠️ **最关键的一条：Java 的泛型是「类型擦除」**。
                    编译之后 `List<String>` 和 `List<Integer>` 是**同一个类**，泛型信息没了。
                    这带来三个后果：

                    1. **不能 `new T()`**、不能 `new T[]` —— 运行时不知道 T 是什么
                    2. **不能对泛型做 `instanceof`**：`x instanceof List<String>` 编译不过
                    3. 正因为擦除，**Java 的泛型是编译期的**；C# 的泛型会保留到运行时，这是两者一个大差异

                    通配符是配对使用的规则（PECS）：
                    - `? extends T`（上界）：只能**读**，读出来是 T
                    - `? super T`（下界）：只能**写**，可以写 T 进去

                    ```java
                    void printAll(List<? extends Number> list) {   // 只读
                      for (Number n : list) System.out.println(n);
                    }
                    ```

                    应用场景：写工具方法、框架 API 时用泛型把「类型契约」表达清楚；
                    业务里最常见的就是 `List<T>` / `Map<K, V>` / 自定义的 `Result<T>`。
                    """,
                    """
                    import java.util.ArrayList;
                    import java.util.List;

                    public class Main {
                      // 泛型方法：类型由调用时推断
                      static <T> T firstOf(List<T> list) {
                        return list.isEmpty() ? null : list.get(0);
                      }

                      // 上界通配符：只读安全
                      static double sum(List<? extends Number> list) {
                        double s = 0;
                        for (Number n : list) s += n.doubleValue();
                        return s;
                      }

                      public static void main(String[] args) {
                        List<String> names = new ArrayList<>();
                        names.add("Alice");
                        System.out.println(firstOf(names));

                        List<Integer> nums = new ArrayList<>();
                        nums.add(1);
                        nums.add(2);
                        System.out.println(sum(nums));

                        // 擦除的证据：两者的运行时类相同
                        System.out.println(names.getClass() == nums.getClass());
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("为什么 Java 里不能写 `new T()` 或 `T[] arr = new T[10]`？",
                                    List.of("语法不允许而已", "类型擦除 —— 运行时拿不到 T 的实际类型，无法创建实例/数组", "因为泛型只能是引用类型", "因为要配合反射"), 1,
                                    "编译后 T 被擦成它的上界（默认 Object），运行时不存在 T 这个信息，"
                                            + "所以既不能 `new` 也不能建数组。要创建得把 `Class<T>` 作为参数传进来。"),
                            quiz("`List<String>` 与 `List<Integer>` 在运行时是？",
                                    List.of("两个不同的类", "同一个类（都是 List），泛型信息被擦除了", "父类与子类", "接口与实现"), 1,
                                    "擦除后都是 `List`，这也是「泛型不能重载」的原因 —— `f(List<String>)` 和 `f(List<Integer>)` "
                                            + "擦除后签名一样，编译会报重复定义。"))),

            lesson("java-05", "并发基础：线程、线程池与锁", 25,
                    """
                    Java 的并发不用直接 `new Thread` —— 生产代码基本都用**线程池**。

                    ```java
                    ExecutorService pool = Executors.newFixedThreadPool(4);
                    Future<Integer> f = pool.submit(() -> 1 + 1);
                    System.out.println(f.get());     // 阻塞等结果
                    pool.shutdown();
                    ```

                    ⚠️ 三条最实用的：

                    1. **别用 `Executors.newXxx()` 建池**（面试与规范都这么说）：它用无界队列，
                       任务堆积时会 OOM。用 `new ThreadPoolExecutor(...)` 自己指定**有界队列 + 拒绝策略**
                    2. `Future.get()` 会**阻塞**，也会把内部异常包成 `ExecutionException` 抛出，要拆开看
                    3. 共享可变状态**必须同步**，否则结果不可预测

                    **锁与内存可见性**：

                    ```java
                    private int count = 0;
                    public synchronized void inc() { count++; }      // 方法级锁
                    private final AtomicInteger n = new AtomicInteger();
                    public void inc2() { n.incrementAndGet(); }      // 无锁原子类
                    ```

                    ⚠️ `count++` **不是原子的**（读-改-写三步），并发下会丢更新 ——
                    这和 SQL 里的「丢失更新」是同一个问题。简单累加优先用 `AtomicInteger`。

                    另外 `volatile` 只保证**可见性**（一个线程改了另一个马上能看到），
                    **不保证原子性** —— 用它来保护 `count++` 是无效的。

                    应用场景：批量任务并行、接口聚合、定时任务。
                    写并发代码的第一原则是**尽量不共享状态**（线程池 + 无共享的任务），而不是想办法加锁。
                    """,
                    """
                    import java.util.concurrent.*;
                    import java.util.concurrent.atomic.AtomicInteger;

                    public class Main {
                      private static final AtomicInteger counter = new AtomicInteger();

                      public static void main(String[] args) throws Exception {
                        // ⚠️ 生产上要用 ThreadPoolExecutor 指定有界队列与拒绝策略
                        ExecutorService pool = new ThreadPoolExecutor(
                            4, 4, 0L, TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(100),
                            new ThreadPoolExecutor.CallerRunsPolicy());

                        for (int i = 0; i < 100; i++) {
                          pool.submit(counter::incrementAndGet);
                        }
                        pool.shutdown();
                        pool.awaitTermination(5, TimeUnit.SECONDS);

                        System.out.println("计数 = " + counter.get());

                        // Future 阻塞取结果，异常会被包成 ExecutionException
                        Future<Integer> ok = pool.isShutdown()
                            ? CompletableFuture.completedFuture(42)
                            : null;
                        System.out.println("结果 = " + (ok != null ? ok.get() : "n/a"));
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("为什么规范建议不要用 `Executors.newFixedThreadPool()`？",
                                    List.of("性能差", "它用无界队列，任务堆积时容易 OOM", "不能复用线程", "它不返回 ExecutorService"), 1,
                                    "`newFixedThreadPool` 内部用的是 `LinkedBlockingQueue`（无界），任务来得比消费快时队列无限增长，"
                                            + "最终 `OutOfMemoryError`。正确做法是 `new ThreadPoolExecutor` 显式指定有界队列和拒绝策略。"),
                            quiz("用 `volatile int count` 保护 `count++` 为什么不行？",
                                    List.of("volatile 性能太差", "volatile 只保证可见性，`count++` 是读-改-写三步、非原子", "volatile 只能修饰对象", "其实可行"), 1,
                                    "两个线程可能同时读到 5、各自加 1 写回 6 —— 丢了一次更新。"
                                            + "要原子性用 `synchronized` 或 `AtomicInteger`；`volatile` 解决的是「一个线程改了另一个看不见」。"))),

            lesson("java-06", "IO 与 NIO：文件与流", 20,
                    """
                    Java 的文件读写分两代：老的 **IO（流式）** 和新的 **NIO（通道 + 缓冲区）**。
                    日常读个小文件，用 `java.nio.file.Files` 最省事：

                    ```java
                    Path p = Path.of("data.txt");
                    List<String> lines = Files.readAllLines(p);              // 一次读全部
                    String text = Files.readString(p);                        // 读成一个字符串
                    Files.writeString(p, "hello");                            // 写
                    ```

                    大文件不能一次读进内存，要**流式处理**：

                    ```java
                    try (BufferedReader r = Files.newBufferedReader(p)) {
                      String line;
                      while ((line = r.readLine()) != null) { ... }
                    }
                    ```

                    ⚠️ 四条要点：

                    1. **流一律用 try-with-resources**（`try (资源)`）：离开块自动关闭，
                       即使抛异常也会关 —— 忘了关文件句柄会泄漏，用久了就是「too many open files」
                    2. **必须显式指定字符集**：`Files.readString(p, StandardCharsets.UTF_8)`。
                       不指定就用平台默认，Windows 上中文会乱码（这个坑在 Python 那边也一样）
                    3. IO 是**阻塞**的：一个线程读文件时干不了别的。NIO 的 `Channel` + `Selector`
                       能用少量线程管理大量连接 —— 这是 Netty 这类框架的基础
                    4. 写文件也有缓冲区：`BufferedWriter` 比每次 `write` 都落盘快得多

                    应用场景：读配置、导出报表、解析日志。**先看 `Files` 有没有现成方法，
                    再考虑 `FileInputStream` 那一套老 API。**
                    """,
                    """
                    import java.nio.charset.StandardCharsets;
                    import java.nio.file.*;
                    import java.util.List;

                    public class Main {
                      public static void main(String[] args) throws Exception {
                        Path p = Path.of("demo.txt");

                        // 写：显式指定 UTF-8，不依赖平台默认编码
                        Files.writeString(p, "第一行" + System.lineSeparator() + "第二行",
                                StandardCharsets.UTF_8);

                        // 小文件：一次读全部
                        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                        System.out.println("行数 = " + lines.size());

                        // 大文件：流式逐行读（try-with-resources 保证关闭）
                        try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                          String line;
                          while ((line = reader.readLine()) != null) {
                            System.out.println("读到: " + line);
                          }
                        }

                        Files.deleteIfExists(p);
                        System.out.println("文件大小 = " + Files.size(p));
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("为什么读写文件必须显式指定 `StandardCharsets.UTF_8`？",
                                    List.of("性能更好", "否则用平台默认编码，跨平台（尤其 Windows）中文会乱码", "UTF-8 是唯一合法的", "为了安全"), 1,
                                    "不指定时走系统默认字符集：Linux 常是 UTF-8，Windows 常是 GBK —— 同一份代码换个环境就乱码。"
                                            + "显式写死编码，是让行为可预期的唯一办法。"),
                            quiz("`try (BufferedReader r = ...) { }` 这种写法相比手动 close 的核心好处？",
                                    List.of("读得更快", "无论是否抛异常都会自动关闭资源", "可以同时读多个文件", "能提升缓冲大小"), 1,
                                    "try-with-resources 会在离开代码块时（含异常路径）自动调用 `close()`。"
                                            + "手动 close 一旦漏掉或在异常处提前返回，文件句柄就泄漏了。"))));

    // --------------------------------------------------------------------------------
    // Java · 实战
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> JAVA_PRO_LESSONS = List.of(
            lesson("java-07", "Spring Boot 入门：写一个 REST 接口", 25,
                    """
                    后端 Java 的实际工作，绝大多数是在写 Spring Boot。它的核心是**依赖注入**与**约定优于配置**。

                    一个最小的接口：

                    ```java
                    @RestController
                    @RequestMapping("/api/users")
                    public class UserController {

                      private final UserService service;

                      // 构造器注入：依赖从外面给，而不是自己 new
                      public UserController(UserService service) {
                        this.service = service;
                      }

                      @GetMapping("/{id}")
                      public User get(@PathVariable long id) {
                        return service.find(id);
                      }

                      @PostMapping
                      public User create(@RequestBody CreateUser body) {
                        return service.create(body);
                      }
                    }
                    ```

                    ⚠️ 五条入门要点：

                    1. **`@RestController` = `@Controller` + 返回值直接写进响应体**（默认序列化成 JSON）
                    2. **构造器注入优于字段注入**（`@Autowired` 写在字段上）：依赖不可变、便于测试、能暴露循环依赖
                    3. `@Service` / `@Repository` / `@Component` 都是把类交给容器管理，语义上有分工
                    4. **HTTP 状态码要用对**：查不到返回 404、参数错返回 400、建成功返回 201，别一律 200
                    5. 配置写在 `application.yml`，敏感项（密码/密钥）**走环境变量**，别提交进仓库

                    应用场景：这就是「后端」的日常 —— 定义资源 + 路由 + 参数校验 + 调 service + 返回。
                    前端会用到的那套 REST 约定，在这一侧就是这些注解。
                    """,
                    """
                    import org.springframework.web.bind.annotation.*;
                    import java.util.*;
                    import java.util.concurrent.ConcurrentHashMap;

                    @RestController
                    @RequestMapping("/api/notes")
                    public class NoteController {

                      private final Map<Long, String> store = new ConcurrentHashMap<>();
                      private long seq = 0;

                      @GetMapping
                      public Collection<String> list() {
                        return store.values();
                      }

                      @GetMapping("/{id}")
                      public String get(@PathVariable long id) {
                        String v = store.get(id);
                        if (v == null) throw new NoSuchElementException("不存在: " + id);
                        return v;
                      }

                      @PostMapping
                      public String create(@RequestBody String body) {
                        long id = ++seq;
                        store.put(id, body);
                        return "created " + id;
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("`@RestController` 相比 `@Controller` 的主要差别是？",
                                    List.of("性能更好", "方法返回值直接写入响应体（默认序列化成 JSON），不需要 @ResponseBody", "只能处理 GET", "必须搭配 @Service"), 1,
                                    "`@RestController` 是 `@Controller` + `@ResponseBody` 的组合注解。"
                                            + "返回对象会被消息转换器自动序列化成 JSON —— 这就是「写 Java 也能给前端返回 JSON」的机制。"),
                            quiz("为什么推荐**构造器注入**而不是把 `@Autowired` 写在字段上？",
                                    List.of("性能更好", "依赖可以在构造时确定、字段可为 final，也更容易在测试里替换", "字段注入已被废弃", "可以少写一行"), 1,
                                    "字段注入让对象在「没被容器管理」时无法创建（依赖为 null，只能反射硬塞），也掩盖了循环依赖。"
                                            + "构造器注入把依赖变成显式的、不可变的，这是 Spring 官方推荐的做法。"))),

            lesson("java-08", "JVM 内存与 GC 基础", 20,
                    """
                    理解 JVM 内存，是为了看懂两类线上问题：**内存溢出**和**频繁 GC 导致卡顿**。

                    运行时数据区大致分几块（重点记住前两块）：

                    | 区域 | 放什么 | 会不会回收 |
                    |---|---|---|
                    | 堆（Heap） | 所有 `new` 出来的对象 | 会（GC 的主战场） |
                    | 栈（Stack） | 每个线程的方法调用、局部变量 | 方法结束自动释放 |
                    | 方法区/元空间 | 类信息、常量 | 很少回收 |

                    **GC 的核心思路是分代**：绝大多数对象「朝生夕死」，所以把堆分成
                    **新生代**（频繁小回收，快）和**老年代**（不常回收，慢）。

                    对象怎么进老年代？不是看年龄到了就进那么简单 —— 关键是**晋升**：
                    在新生代躲过若干次回收后（默认 15 次）会被移到老年代。

                    ⚠️ 四条实践要点：

                    1. `OutOfMemoryError: Java heap space` = **堆不够或真的泄漏了**。
                       先怀疑「有集合一直加元素没清理」（缓存没上限是重灾区）
                    2. 不要靠 `System.gc()` 解决问题 —— 它只是「建议」，且会触发 Full GC 让服务卡住
                    3. **内存泄漏 ≠ 内存没被引用**：Java 里的泄漏通常是「对象还被引用着，但你已经不需要它了」
                       （静态集合、监听器没注销、ThreadLocal 没 remove）
                    4. 排查用工具：`jmap -heap <pid>` 看各区占用，`jstack` 看线程栈，
                       或加上 `-XX:+HeapDumpOnOutOfMemoryError` 让 OOM 时自动 dump 堆

                    应用场景：服务跑着跑着变慢、重启后恢复 —— 十有八九是内存问题，
                    先看 GC 日志与堆占用，而不是急着调大 `-Xmx`。
                    """,
                    """
                    import java.util.ArrayList;
                    import java.util.List;

                    public class Main {
                      public static void main(String[] args) {
                        Runtime rt = Runtime.getRuntime();

                        // 观察堆占用变化（真实场景用 jmap / GC 日志）
                        List<byte[]> hold = new ArrayList<>();
                        System.out.println("初始已用内存(MB) = "
                            + (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);

                        for (int i = 0; i < 20; i++) {
                          hold.add(new byte[1024 * 1024]);
                        }
                        System.out.println("持有 20MB 后已用(MB) = "
                            + (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);

                        // 典型泄漏写法：静态集合只增不减
                        hold.clear();
                        System.out.println("清空引用后仍要等 GC —— 泄漏的本质是「还被引用着」");
                      }
                    }
                    """,
                    "java",
                    List.of(
                            quiz("Java 里的「内存泄漏」通常是什么形态？",
                                    List.of("对象没有被任何变量引用", "对象还被引用着，但程序已经不再需要它（如无上限的静态缓存）", "虚拟内存不足", "栈溢出"), 1,
                                    "Java 有 GC，所以泄漏不是「忘了 free」，而是**引用没释放**："
                                            + "静态集合一直加、监听器没注销、ThreadLocal 没 remove —— GC 看着它有引用，自然不能回收。"),
                            quiz("遇到 `OutOfMemoryError: Java heap space`，第一步应该做什么？",
                                    List.of("直接调大 -Xmx", "先定位是谁在占用内存（堆 dump / GC 日志），判断是泄漏还是容量真不够", "调用 System.gc()", "重启服务"), 1,
                                    "调大堆只是把问题推迟 —— 如果是泄漏，加了内存照样会再次 OOM。"
                                            + "先拿证据（`-XX:+HeapDumpOnOutOfMemoryError` 出的堆 dump）再决定是修代码还是扩容。"))));

    // --------------------------------------------------------------------------------
    // Go · 入门（runner=server：浏览器里跑不了，代码仅作讲解）
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> GO_LESSONS = List.of(
            lesson("go-01", "变量、类型与多返回值", 15,
                    """
                    Go 的语法刻意做得很少，一眼能看完全部。变量有两种写法：

                    ```go
                    var age int = 30      // 完整写法
                    name := "Alice"        // 短变量声明（函数内可用，类型自动推断）
                    ```

                    ⚠️ Go 有两条「强制」的规矩，一开始会不习惯，但能省掉大量争论：

                    1. **声明了就必须用** —— 没用的变量/导入会**编译报错**，不是警告
                    2. **没有隐式类型转换** —— `int` 和 `int64` 相加必须显式转换，不会自动兼容

                    **多返回值**是 Go 的标志性设计，最典型的用途就是「结果 + 错误」一起返回：

                    ```go
                    func div(a, b float64) (float64, error) {
                      if b == 0 {
                        return 0, errors.New("除数不能为 0")
                      }
                      return a / b, nil
                    }

                    v, err := div(10, 2)
                    if err != nil { /* 处理 */ }
                    ```

                    ⚠️ 注意这个惯用法：**错误是普通返回值**，`err != nil` 必须当场处理，
                    Go 里没有异常机制（`panic` 只用于「不该发生」的情况）。

                    应用场景：写返回值的函数时，「值 + error」是两个返回值 —— 这正是 Go 与其它语言
                    最不一样的地方，也是它代码里满屏 `if err != nil` 的原因。
                    """,
                    """
                    package main

                    import (
                    	"errors"
                    	"fmt"
                    )

                    func div(a, b float64) (float64, error) {
                    	if b == 0 {
                    		return 0, errors.New("除数不能为 0")
                    	}
                    	return a / b, nil
                    }

                    func main() {
                    	name := "Alice"          // 短变量声明
                    	var age int = 30
                    	fmt.Println(name, age)

                    	v, err := div(10, 4)
                    	if err != nil {
                    		fmt.Println("出错:", err)
                    		return
                    	}
                    	fmt.Println("结果:", v)

                    	// 错误作为普通返回值，必须显式判断
                    	if _, err := div(1, 0); err != nil {
                    		fmt.Println("预期内的错误:", err)
                    	}
                    }
                    """,
                    "go",
                    List.of(
                            quiz("Go 里声明了一个变量却没用，会怎样？",
                                    List.of("编译器警告", "编译报错，必须删掉或使用", "运行时崩溃", "自动忽略"), 1,
                                    "Go 把「未使用」当成错误而不是警告，从语言层面杜绝了死代码与漏用的变量。"
                                            + "同理，导入了包没用也会编译失败。"),
                            quiz("Go 处理错误的惯用方式是？",
                                    List.of("try/catch 捕获异常", "错误作为函数的普通返回值，调用方显式判断 err != nil", "断言 assert", "全局错误处理器"), 1,
                                    "Go 刻意不做异常（`panic` 只留给「程序不该继续」的场景），把错误变成返回值后，"
                                            + "**编译器能保证你不会「忘记处理」**（因为不处理就拿不到值）——代价是 `if err != nil` 写得很多。"))),

            lesson("go-02", "goroutine 与 channel：并发的核心", 25,
                    """
                    Go 的并发口号是「**不要通过共享内存来通信，而要通过通信来共享内存**」。
                    两个关键字就够了：`go` 和 `chan`。

                    ```go
                    go doWork()        // 起一个 goroutine（比线程轻得多，几 KB 栈）

                    ch := make(chan int)
                    go func() { ch <- 42 }()    // 发送
                    v := <-ch                    // 接收（会阻塞直到有值）
                    ```

                    **channel 是带类型的管道**，天然解决「两个 goroutine 之间怎么传数据」。

                    ⚠️ 五条必须记住的：

                    1. **无缓冲 channel 是同步的**：发送方会一直阻塞，直到有人接收 ——
                       这本身就是一种「交接完成」的信号
                    2. **有缓冲的 channel**（`make(chan int, 10)`）在缓冲没满时发送不阻塞，
                       适合「生产者快、消费者慢」的削峰
                    3. **向已关闭的 channel 发送会 panic**；接收已关闭的会立刻返回零值 + `ok=false`
                    4. **只有发送方该关闭 channel**，接收方不该关（否则多个接收者会双重关闭）
                    5. `main` 函数结束 = 程序退出，**不会等 goroutine 跑完** ——
                       要么用 `WaitGroup` 等，要么用 channel 收结果

                    ```go
                    var wg sync.WaitGroup
                    wg.Add(1)
                    go func() { defer wg.Done(); doWork() }()
                    wg.Wait()
                    ```

                    应用场景：并发请求多个下游再汇总、任务池、超时控制（`select` + `time.After`）。
                    """,
                    """
                    package main

                    import (
                    	"fmt"
                    	"sync"
                    )

                    func main() {
                    	// 简单的生产者/消费者
                    	ch := make(chan int, 3)
                    	go func() {
                    		for i := 1; i <= 3; i++ {
                    			ch <- i
                    		}
                    		close(ch)          // 只有发送方关闭
                    	}()

                    	for v := range ch {     // 关闭后自动结束循环
                    		fmt.Println("收到:", v)
                    	}

                    	// WaitGroup：等所有 goroutine 跑完（main 不会自动等）
                    	var wg sync.WaitGroup
                    	for i := 0; i < 3; i++ {
                    		wg.Add(1)
                    		go func(n int) {
                    			defer wg.Done()
                    			fmt.Println("任务", n)
                    		}(i)
                    	}
                    	wg.Wait()
                    	fmt.Println("全部完成")
                    }
                    """,
                    "go",
                    List.of(
                            quiz("向一个**已关闭**的 channel 发送数据会怎样？",
                                    List.of("静默丢弃", "panic", "返回错误", "阻塞直到重新打开"), 1,
                                    "关闭表示「不会再发了」，再发就是逻辑错误，Go 直接 panic。"
                                            + "而**接收**已关闭的 channel 是安全的：会立刻返回零值和 `ok=false`，`range` 也会正常结束。"),
                            quiz("`main` 函数返回后，还没跑完的 goroutine 会怎样？",
                                    List.of("继续跑到结束", "程序直接退出，它们被一起终止", "自动转成后台线程", "阻塞 main 等待"), 1,
                                    "goroutine 不是「后台服务」，主函数一结束整个程序就退出。"
                                            + "所以要收结果就用 channel，要等完成就用 `sync.WaitGroup`。"))),

            lesson("go-03", "结构体、接口与 error 处理", 20,
                    """
                    Go 没有 class，用**结构体 + 方法**来表达对象：

                    ```go
                    type User struct {
                      Name string
                      Age  int
                    }

                    // 值接收者：不修改原对象
                    func (u User) Hello() string { return "hi " + u.Name }

                    // 指针接收者：能修改原对象
                    func (u *User) Birthday() { u.Age++ }
                    ```

                    ⚠️ 接收者的选择有实际影响：**需要修改 或 结构体较大时用指针接收者**，
                    否则每次调用都会复制一份。

                    **接口是隐式实现的** —— 这是 Go 最特别的设计：

                    ```go
                    type Speaker interface { Speak() string }

                    // 没有 implements 关键字：只要方法签名对得上，就自动满足接口
                    func (u User) Speak() string { return "我是 " + u.Name }
                    ```

                    ⚠️ 好处是**解耦**：接口可以定义在调用方（而不是实现方），
                    实现方甚至不知道自己满足了这个接口。这也是 Go 生态「小接口」风格（如 `io.Reader`）的来源。

                    **error 处理**：错误是普通值，可以包装与判断：

                    ```go
                    if err != nil {
                      return fmt.Errorf("读取配置失败: %w", err)   // %w 包装，保留原因
                    }
                    ```

                    ⚠️ 用 `%w` 包装后，上层仍能用 `errors.Is(err, 目标错误)` 判断出根因 ——
                    这是「加日志上下文」和「保留可判断性」兼得的写法。

                    应用场景：定义领域模型（`User` / `Order`）、用 `error` 把失败原因逐层带上去。
                    """,
                    """
                    package main

                    import (
                    	"errors"
                    	"fmt"
                    )

                    type User struct {
                    	Name string
                    	Age  int
                    }

                    func (u User) Speak() string  { return "我是 " + u.Name }
                    func (u *User) Birthday()     { u.Age++ }

                    // 隐式接口：没有 implements，方法签名对上就行
                    type Speaker interface{ Speak() string }

                    var ErrNotFound = errors.New("未找到")

                    func find(name string) (User, error) {
                    	if name == "" {
                    		return User{}, fmt.Errorf("查询用户: %w", ErrNotFound)
                    	}
                    	return User{Name: name, Age: 20}, nil
                    }

                    func main() {
                    	var s Speaker = User{Name: "Alice"}
                    	fmt.Println(s.Speak())

                    	u := User{Name: "Bob", Age: 20}
                    	u.Birthday()                   // 指针接收者，能改到原对象
                    	fmt.Println(u.Age)

                    	// %w 包装后仍能用 errors.Is 判断根因
                    	_, err := find("")
                    	fmt.Println("是 ErrNotFound 吗:", errors.Is(err, ErrNotFound))
                    }
                    """,
                    "go",
                    List.of(
                            quiz("Go 的接口是「隐式实现」意味着什么？",
                                    List.of("必须用 implements 声明", "只要方法签名匹配就自动满足接口，不需要显式声明", "接口不能有多个实现", "接口必须定义在实现方"), 1,
                                    "这正是 Go「小接口」风格的基础：接口可以定义在**使用方**，实现方无须知道它的存在。"
                                            + "带来的好处是解耦和可组合 —— `io.Reader` 这类接口小到只有一个方法，所以到处都能满足。"),
                            quiz("`fmt.Errorf(\"...: %w\", err)` 里的 `%w` 有什么作用？",
                                    List.of("把错误转成字符串", "包装错误并保留原因，上层可用 errors.Is / errors.As 判断根因", "打印错误堆栈", "忽略这个错误"), 1,
                                    "`%w` 会建立一个「错误链」：既加上下文（哪一步失败的），又保留原始错误，"
                                            + "所以 `errors.Is(err, ErrNotFound)` 仍然成立 —— 这是 Go 处理错误分层的标准做法。"))));

    // --------------------------------------------------------------------------------
    // Rust · 入门（runner=server：浏览器里跑不了，代码仅作讲解）
    // --------------------------------------------------------------------------------

    private static final List<Map<String, Object>> RUST_LESSONS = List.of(
            lesson("rust-01", "变量、可变性与所有权", 20,
                    """
                    Rust 的变量**默认不可变**：

                    ```rust
                    let x = 5;
                    // x = 6;          // 编译错误
                    let mut y = 5;
                    y = 6;             // 加 mut 才行
                    ```

                    这是刻意的：默认不可变让编译器能帮你排除大量意外修改。

                    **所有权（ownership）**是 Rust 最核心、也最区别于其它语言的设计。三条规则：

                    1. 每个值有且只有一个**所有者**
                    2. 同一时刻只能有一个所有者
                    3. 所有者离开作用域，值就被释放（不需要 GC，也不需要手动 free）

                    ```rust
                    let s1 = String::from("hello");
                    let s2 = s1;               // 所有权**移动**给 s2
                    // println!("{}", s1);      // 编译错误：s1 已经失效
                    ```

                    ⚠️ 注意这里是「移动」不是「浅拷贝」：`s1` 之后完全不能用了。
                    这就是 Rust 能在没有 GC 的前提下保证内存安全的原因 —— **在编译期就查出了悬空引用**。

                    想保留原来的值就用 `.clone()`（显式深拷贝，代价看得见）。

                    应用场景：Rust 的学习曲线基本全在这套规则上。过了这一关，
                    你换来的是「编译通过 ≈ 没有内存错误和数据竞争」。
                    """,
                    """
                    fn main() {
                        // 默认不可变
                        let x = 5;
                        println!("x = {}", x);

                        let mut y = 6;
                        y += 1;
                        println!("y = {}", y);

                        // 所有权移动：s1 交给 s2 后就不能再用
                        let s1 = String::from("hello");
                        let s2 = s1;
                        println!("s2 = {}", s2);
                        // println!("{}", s1);   // 打开这行会编译失败

                        // 想保留就显式 clone
                        let s3 = String::from("world");
                        let s4 = s3.clone();
                        println!("s3 = {}, s4 = {}", s3, s4);

                        // 栈上的简单类型实现了 Copy，赋值是复制不是移动
                        let a = 5;
                        let b = a;
                        println!("a = {}, b = {}", a, b);
                    }
                    """,
                    "rust",
                    List.of(
                            quiz("`let s2 = s1;` 之后继续使用 `s1` 会怎样？",
                                    List.of("正常使用", "编译错误 —— String 的所有权已移动", "运行时 panic", "自动复制一份"), 1,
                                    "String 持有堆内存，赋值时**移动**所有权（避免两个所有者重复释放）。"
                                            + "而 `i32` 这类栈上、实现了 `Copy` 的类型则是复制 —— 所以 `let b = a;` 之后 a 还能用。"),
                            quiz("Rust 不需要 GC 却能做到内存安全，靠的是什么？",
                                    List.of("引用计数", "编译期的所有权与借用检查", "运行时标记清除", "手动 free"), 1,
                                    "所有权规则让「谁负责释放」在编译期就唯一确定（所有者出作用域即释放），"
                                            + "借用检查则保证不会出现悬空引用与数据竞争。代价是编译期要过更严的检查。"))),

            lesson("rust-02", "借用与生命周期", 25,
                    """
                    如果每次传参都转移所有权，代码会很难写。**借用**解决了这个问题：
                    只借出去用一下，所有权还在原处。

                    ```rust
                    let s = String::from("hello");
                    let len = calc(&s);        // &s 是「借用」：借出去，不转移
                    println!("{} {}", s, len);  // s 仍然可用
                    ```

                    规则只有两条，但要记住：

                    1. 同一时刻，**要么有多个不可变借用 `&T`，要么只有一个可变借用 `&mut T`** —— 二者不能同时存在
                    2. 借用的**生命周期不能超过**所有者

                    ⚠️ 第一条就是「数据竞争」的解药：允许多个读者，或一个写者，但绝不允许「边读边写」。
                    这些在其它语言里是运行时偶发的 bug，在 Rust 里是**编译不过**。

                    ```rust
                    let mut v = vec![1, 2, 3];
                    let first = &v[0];
                    // v.push(4);        // 编译错误：v 已被不可变借用，不能再可变借用
                    println!("{}", first);
                    ```

                    **生命周期标注**（`<'a>`）不是让你手动管理内存 ——
                    它是在告诉编译器「这几个引用的存活关系」，用于函数返回引用等编译器推不出来的场景：

                    ```rust
                    fn longest<'a>(a: &'a str, b: &'a str) -> &'a str {
                      if a.len() > b.len() { a } else { b }
                    }
                    ```

                    应用场景：**99% 的代码不需要写生命周期标注** —— 编译器有「生命周期省略」规则，
                    只有返回引用且来源不明确时才需要手写。
                    """,
                    """
                    // 借用：所有权不转移
                    fn calc_len(s: &str) -> usize {
                        s.len()
                    }

                    // 可变借用：能改，但同一时刻只能有一个
                    fn push_item(v: &mut Vec<i32>, n: i32) {
                        v.push(n);
                    }

                    // 需要返回引用时才要标生命周期
                    fn longest<'a>(a: &'a str, b: &'a str) -> &'a str {
                        if a.len() > b.len() { a } else { b }
                    }

                    fn main() {
                        let s = String::from("hello");
                        let len = calc_len(&s);
                        println!("{} 长度 {}", s, len);     // s 还能用

                        let mut v = vec![1, 2, 3];
                        push_item(&mut v, 4);
                        println!("{:?}", v);

                        let r = longest("abcd", "xy");
                        println!("更长的是 {}", r);
                    }
                    """,
                    "rust",
                    List.of(
                            quiz("Rust 中同一时刻的借用规则是？",
                                    List.of("可以任意多个可变借用", "要么多个不可变借用 &T，要么一个可变借用 &mut T，两者不能共存", "可变借用和不可变借用可以共存", "没有限制"), 1,
                                    "这条规则从编译期就消灭了「边读边写」导致的数据竞争。"
                                            + "它让 Rust 的并发代码在编译后就是安全的 —— 这正是 `Send`/`Sync` 那些约束的基础。"),
                            quiz("生命周期标注 `<'a>` 的作用是？",
                                    List.of("手动申请和释放内存", "告诉编译器多个引用的存活关系，不改变运行时行为", "延长变量的生命周期", "开启垃圾回收"), 1,
                                    "生命周期标注是**给编译器的提示**（签注），编译后完全消失、不产生任何运行时动作。"
                                            + "大多数情况编译器能自己推断，只有返回引用的来源不明确时才需要手写。"))),

            lesson("rust-03", "结构体、枚举与模式匹配", 20,
                    """
                    Rust 用 **struct** 建模数据，用 **enum** 建模「几种可能之一」——
                    后者的表达力比多数语言的枚举强得多，因为**每个变体可以带不同的数据**。

                    ```rust
                    struct User { name: String, age: u32 }

                    enum Shape {
                      Circle(f64),                  // 带一个值
                      Rect { w: f64, h: f64 },      // 带字段
                    }
                    ```

                    **模式匹配（match）** 是配套的消费方式，而且**必须穷尽所有情况**，
                    编译器会强制你处理完：

                    ```rust
                    fn area(s: Shape) -> f64 {
                      match s {
                        Shape::Circle(r) => 3.14 * r * r,
                        Shape::Rect { w, h } => w * h,
                      }   // 少写一个分支 → 编译报错
                    }
                    ```

                    ⚠️ 这是 Rust 一个很值钱的特性：**漏处理分支根本编译不过**。
                    其它语言里「新增了一个枚举值，某处 switch 忘了改」是典型的生产事故。

                    最经典的用法是标准库的 `Option` 与 `Result`：

                    ```rust
                    match maybe {
                      Some(v) => println!("有值: {}", v),
                      None => println!("没有"),
                    }
                    // Option 强制你处理「可能没有」，从语言层面消除了空指针
                    ```

                    方法用 `impl` 块定义：

                    ```rust
                    impl User {
                      fn greet(&self) -> String { format!("hi {}", self.name) }
                    }
                    ```

                    应用场景：用 enum 表达状态机（`Pending / Paid / Refunded` 各自带不同数据），
                    配合 match 保证「每个状态都被处理」—— 这就是「让非法状态无法表示」在 Rust 里的形态。
                    """,
                    """
                    struct User { name: String, age: u32 }

                    impl User {
                        fn greet(&self) -> String {
                            format!("hi {}", self.name)
                        }
                    }

                    enum Shape {
                        Circle(f64),
                        Rect { w: f64, h: f64 },
                    }

                    fn area(s: &Shape) -> f64 {
                        match s {
                            Shape::Circle(r) => 3.14 * r * r,
                            Shape::Rect { w, h } => w * h,
                        }
                    }

                    fn main() {
                        let u = User { name: String::from("Alice"), age: 30 };
                        println!("{}", u.greet());

                        println!("{}", area(&Shape::Circle(2.0)));
                        println!("{}", area(&Shape::Rect { w: 2.0, h: 3.0 }));

                        // Option 强制处理「可能没有」
                        let maybe: Option<i32> = None;
                        match maybe {
                            Some(v) => println!("有值 {}", v),
                            None => println!("没有值"),
                        }
                    }
                    """,
                    "rust",
                    List.of(
                            quiz("Rust 的 `match` 漏写了某个分支会怎样？",
                                    List.of("运行时报错", "编译错误 —— 必须穷尽所有可能", "自动走默认分支", "返回 None"), 1,
                                    "穷尽性检查让「新增枚举变体后忘了处理」在编译期暴露。"
                                            + "这也是 `Option`/`Result` 能替代空指针与异常的基础：不处理就编译不过。"),
                            quiz("Rust 的 enum 和其它语言（如 C/Java）的枚举相比，最大的不同是？",
                                    List.of("性能更好", "每个变体可以携带不同类型的数据，能直接当状态机用", "不能有方法", "必须是数字"), 1,
                                    "Rust 的 enum 是「和类型」（几种可能之一，各带各自的数据），"
                                            + "所以能用一个类型精确表达「进行中 / 成功(带结果) / 失败(带原因)」这类状态 —— 这是它比传统枚举强的地方。"))));

    public static final List<Map<String, Object>> BASE_TRACKS = List.of(
            track("javascript", "JavaScript", "🟨",
                    "从变量、函数到数组处理，再走到异步、原型与 DOM —— 浏览器里可以直接跑，改完立刻看结果。",
                    "worker",
                    List.of(
                            stage("js-basic", "入门", "读懂并写出日常的函数与数组处理代码", JS_LESSONS),
                            stage("js-adv", "进阶", "掌握对象解构、异步编程与 this / 原型的真实行为", JS_ADV_LESSONS),
                            stage("js-pro", "实战", "把数据变成界面：DOM、事件委托与模块化请求", JS_PRO_LESSONS))),
            track("python", "Python", "🐍",
                    "强类型动态语言，语法干净、生态庞大。教程里的每段代码都能在浏览器里真跑（Pyodide）。",
                    "pyodide",
                    List.of(
                            stage("py-basic", "入门", "掌握变量、列表推导式与函数参数这两个最常用的语言特性", PY_LESSONS),
                            stage("py-adv", "进阶", "用字典集合做数据组织，用类与异常写出可维护的代码", PY_ADV_LESSONS),
                            stage("py-pro", "实战", "标准库三件套与数据处理流程，跑通一个真实的清洗脚本", PY_PRO_LESSONS))),
            track("sql", "SQL", "🗄️",
                    "关系数据库查询语言。用 sql.js 在浏览器里建临时库，每条语句都能真跑真看结果。",
                    "sqljs",
                    List.of(
                            stage("sql-basic", "入门", "能独立写出联表、分组、排序的查询，并知道常见陷阱", SQL_LESSONS),
                            stage("sql-adv", "进阶", "用 CTE 与窗口函数表达复杂逻辑，理解索引为什么影响性能", SQL_ADV_LESSONS),
                            stage("sql-pro", "实战", "分页、去重、留存与事务 —— 业务查询与并发写的高频场景", SQL_PRO_LESSONS))),
            track("typescript", "TypeScript", "🔷",
                    "在 JS 之上加静态类型：类型只在编译期存在、运行时全被擦除。浏览器里可直接转译运行（只去类型、不做类型检查）。",
                    "worker",
                    List.of(
                            stage("ts-basic", "入门", "理解类型标注、判别联合与泛型分别在解决什么问题", TS_LESSONS),
                            stage("ts-adv", "进阶", "用工具类型与类型守卫把「运行时数据」收窄成确定的类型", TS_ADV_LESSONS),
                            stage("ts-pro", "实战", "封装类型安全的请求层，并把 tsconfig 的严格档打开", TS_PRO_LESSONS))),
            track("lua", "Lua", "🌙",
                    "极简的嵌入式脚本语言：只有一种数据结构（table），索引从 1 开始。运行方案体积在这几门里最小（Fengari 约 300KB）。",
                    "fengari",
                    List.of(
                            stage("lua-basic", "入门", "掌握 table 的引用语义，以及用 metatable 搭出面向对象", LUA_LESSONS),
                            stage("lua-adv", "进阶", "迭代器与协程：把遍历、错误处理与「可暂停的流程」讲清楚", LUA_ADV_LESSONS),
                            stage("lua-pro", "实战", "用模块组织代码，用元表做出只读配置与默认值", LUA_PRO_LESSONS))),
            // 下面几门 runner 不是 ready：能跑但要权衡（体积/外部依赖），或只能走后端沙箱 —— 内容照写，页面上如实标注
            track("c", "C / C++", "⚙️",
                    "指针、内存与 C++ 的 RAII。内容已写全；运行需要把 Clang 编译成 WASM，首屏要下几十兆 —— 适合做成「点了才下载」的可选能力。",
                    "clang-wasm",
                    List.of(
                            stage("c-basic", "入门", "理解指针与内存，以及 RAII 为什么让 C++ 少犯内存错", CPP_LESSONS),
                            stage("c-adv", "进阶", "结构体布局、编译链接与 STL —— 把 C/C++ 的工程面补齐", CPP_ADV_LESSONS),
                            stage("c-pro", "实战", "内存 bug 排查与性能：用工具定位，而不是凭直觉", CPP_PRO_LESSONS))),
            track("java", "Java", "☕",
                    "类型系统、集合框架与面向对象。内容已写全；浏览器里只有 CheerpJ（社区版不许自托管、且只到 Java 8），要学现代 Java 得走后端沙箱。",
                    "cheerpj",
                    List.of(
                            stage("java-basic", "入门", "掌握包装类型陷阱、集合选型与异常分类", JAVA_LESSONS),
                            stage("java-adv", "进阶", "泛型擦除、线程池与 IO —— 服务端开发绕不开的三块", JAVA_ADV_LESSONS),
                            stage("java-pro", "实战", "写一个 Spring Boot 接口，并看懂 JVM 内存与 GC 的线上问题", JAVA_PRO_LESSONS))),
            track("go", "Go", "🐹",
                    "内容已写全，但编译链太重、浏览器里没有实用方案：官方 playground 也是把源码发到后端编译。要真跑需接后端沙箱。",
                    "server",
                    List.of(stage("go-basic", "入门", "多返回值与 error、goroutine / channel、结构体与隐式接口", GO_LESSONS))),
            track("rust", "Rust", "🦀",
                    "内容已写全，同样需要后端 rustc。浏览器内只能跑预编译好的 WASM 产物，不能现场编译源码。",
                    "server",
                    List.of(stage("rust-basic", "入门", "所有权与借用、结构体与枚举 —— 先从「编译期内存安全」这条主线入手", RUST_LESSONS))))
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
