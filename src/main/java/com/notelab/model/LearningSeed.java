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
            // 下面几门暂时只有骨架：运行方案要么代价大、要么得走后端，先把位置与说明留出来
            track("c", "C / C++", "⚙️",
                    "需要把 Clang 编译成 WASM 才能在浏览器里编译，首屏要下几十兆。适合做成「点了才下载」的可选能力。",
                    "clang-wasm",
                    List.of(stage("c-basic", "入门", "指针与内存布局", List.of()))),
            track("java", "Java", "☕",
                    "浏览器里只有 CheerpJ（社区版不许自托管、且只到 Java 8）。要学现代 Java 得走后端沙箱。",
                    "cheerpj",
                    List.of(stage("java-basic", "入门", "类、对象与集合框架", List.of()))),
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
