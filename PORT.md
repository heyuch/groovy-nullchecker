# PORT.md — Groovy 6 `NullChecker` → Groovy 4 回移说明

> 本文记录**回移的每一处偏离**、**为什么**，以及**怎么验证的**。
> 凡标注「实测」的都有可复现命令；标注「未证」的请先验证再依赖。
>
> 上游基线：`apache/groovy` tag **`GROOVY_6_0_0`**
> 　`NullChecker.groovy`　sha256 `0ed46916b516157883d1fb593eb1891f66c7839a926966f3904ef8e5837c3e0b`
> 　`CheckingVisitor.groovy`　sha256 `99df010de442efd25184200a1bcdf8331b5e1e41a661580950057e53a30d6e8f`

---

## 1. 结论

**可行，已完成。** 2 个文件、905 行源码级回移；**没有任何分析逻辑被重写**。
全部改动是 4 处 API 适配 + 1 处注解节点适配。

验证方式不是「读起来兼容」，而是：

| 验证 | 结果（实测）|
| --- | --- |
| 用 Groovy 4.0.30 编译上游源码 | ✅ 成功 |
| 语料门禁（30 个语料 × 2 模式）| ✅ 全绿，18 条规则全被覆盖 |
| 与 Groovy 6 官方实现双跑对照 | ✅ 85/87 行一致，**1 处已知偏离**（见 §5）|
| 跨 Groovy 4.x 运行 | ✅ 4.0.30 编译的产物在 4.0.24 上同样正常 |

---

## 2. 偏离清单（全部）

### 2.1 编译期必需（3 处）

| # | 文件:行 | 上游写法 | Groovy 4 现实 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | `NullChecker.groovy:21,142` | `import org.apache.groovy.lang.annotation.Incubating` + `@Incubating` | 该注解是 6.0 新增 | 删除。纯标记性注解，无语义 |
| 2 | `NullChecker.groovy`（原 173）| `StaticTypesMarker.INFERRED_NON_NULL_RETURN_VIOLATIONS` | `StaticTypesMarker` 在 4.0.30 只有 19 个字段（6.0.0 有 23 个）| 改为字符串键 `'INFERRED_NON_NULL_RETURN_VIOLATIONS'`；`getNodeMetaData` 本就按字符串取值。**功能降级** |
| 3 | `NullChecker.groovy`（原 670）| `StaticTypesMarker.INFERRED_NON_NULL` | 同上缺失 | 同上。**功能降级** |

> 穷举验证：脚本抽取两个源文件里**所有** import 到的 Groovy 侧类成员引用，逐条 `javap` 核对 4.0.30，
> 缺失项恰好就是上面这些 —— 即「缺失 3 个成员 + 1 个注解」，**没有任何 Groovy API 类缺失**。

### 2.2 运行期必需（1 处）：`options` / `strict`

**上游**：`boolean strict = options?.strict ?: false`，配合
`@TypeChecked(extensions = 'groovy.typecheckers.NullChecker(strict: true)')`。
Groovy 6 在 `GroovyTypeCheckingExtensionSupport` 里新增了
`parseParameters()` / `getOptions()` / `resolvedPath` 来支持这种带参写法。

**Groovy 4 的现实**（实测读 4.0.30 源码）：`setup()` 直接
`transformLoader.loadClass(scriptPath, false, true)` —— **scriptPath 是整个字符串**，
所以 `"…NullChecker(strict: true)"` 必然 ClassNotFound，回退找同名资源文件，最后报一句
**误导性**的 `Static type checking extension '…(strict: true)' was not found on the classpath.`

三种绕过尝试都**实测失败**：

| 尝试 | 结果 |
| --- | --- |
| 给 `NullChecker` 加 `NullChecker(Map options)` 构造器 | ❌ 走不到构造器，先在 `loadClass` 断了 |
| 写 `PortExtensionSupport extends GroovyTypeCheckingExtensionSupport` 子类剥参数 | ❌ `StaticTypesTransformation` **硬编码** `new GroovyTypeCheckingExtensionSupport(...)`，你的子类永远不会被用 |
| **静态/全局配置** | ✅ 采用 |

**采用的方案**（`NullChecker` 内新增约 45 行，语义等价于上游 `getOptions()`）：

```groovy
private static final Map<String, Object> PORT_OPTIONS = [:]
static final String STRICT_PROPERTY = 'groovy.nullchecker.strict'

static void setOptions(Map<String, Object> options)   // 对应上游 getOptions() 的写入侧
Map<String, Object> getOptions()                      // 与上游同名，便于移植代码
static void clearOptions()
private static boolean isStrictEnabled() {
    PORT_OPTIONS.strict == true || Boolean.getBoolean(STRICT_PROPERTY)
}
```

三种启用方式：

```groovy
// 1) 注解（默认，注解驱动）—— 注意【不能】写 (strict: true)
@TypeChecked(extensions = 'io.github.heyuch.groovy4.nullchecker.NullChecker')

// 2) 构建期全局
System.setProperty('groovy.nullchecker.strict', 'true')   // 或 JVM 参数 -Dgroovy.nullchecker.strict=true

// 3) 程序化（编译插件/自定义 Gradle 任务）
NullChecker.setOptions([strict: true])
```

> ⚠️ **这是 Groovy 4 上最容易踩的坑**：`'X(strict: true)'` 会失败，而失败信息是
> 「not found on the classpath」——它指向的是名字问题，不是参数语法问题，很容易误判成写错类名。
> 与 PORT 坑清单里的其他条目一样，**症状是静默/误导失效**。

### 2.3 注解节点适配（1 处，**仅本地变量声明**）

**这是唯一一处行为偏离**，而且是语料门禁逼出来的 —— 静态的 API 审计**发现不了它**。

- **上游**（`visitDeclarationExpression`）：把注解查在 `decl.variableExpression` 上。
- **Groovy 4 实测**：`@Nullable String x` 的注解挂在 **`DeclarationExpression`** 上，
  `variableExpression` 上**是空的**：

```
DECL expr.annos      = [NonNull]     ← Groovy 4 挂在这里
DECL ve.annos        = []            ← 上游查的是这里 ⇒ 永远查不到
```

⇒ 若照抄上游，Groovy 4 上**所有「本地变量声明」类规则都会静默失效**。

**处置**：新增 `declAnnoCarrier(decl, ve)` —— 哪边有注解就用哪边，两个 Groovy 版本下都正确：

```groovy
private static AnnotatedNode declAnnoCarrier(DeclarationExpression decl, VariableExpression ve) {
    if (!ve.annotations.isEmpty() || !ve.type.annotations.isEmpty()) {
        return ve
    }
    decl
}
```

行为差异见 §5（这正是双跑对照组里唯一的分歧）。

### 2.4 换包名（**必须**，否则踩冲突）

上游两个类分别在 `groovy.typecheckers` 与 `org.apache.groovy.typecheckers`。**两个包名都不能沿用**：

- `org/apache/groovy/typecheckers/CheckingVisitor` **在 Groovy 4.0.30 里已经存在**
  （`groovy-typecheckers-4.0.30.jar`，实测），而且 4.0.30 自带的 `RegexChecker` **正在使用它**。
- 两者**不兼容**：4.0.30 的 `findTargetVariable` 是 **`static`**，6.0.0 是**实例方法**（`javap` 实测）。
  ⇒ 覆盖它会**连带弄坏同项目的 `RegexChecker`**。

⇒ 两个类都放进本项目自己的包：

```
io.github.heyuch.groovy4.nullchecker.NullChecker      ← 对外唯一入口（使用者要写这个字符串）
io.github.heyuch.groovy4.nullchecker.CheckingVisitor  ← 内部基类，仅包名改动
```

---

## 3. 未改动（澄清）

- **`x in y` 运算符**（上游 L404、L459）：因为是在 Groovy 4 上**重新编译源码**，
  编译期会生成 4 自己的实现。实测 `'a' in ['a','b']` 在 Groovy 4 运行时正常。
  （`ScriptBytecodeAdapter.isIn` 在 4.0.30 确实不存在，但这只影响**搬运 jar**，不影响回移源码。）
- **其余全部 Groovy API**：`ClassHelper.VOID_TYPE` / `isPrimitiveType` / `Types.*` /
  `StaticTypesMarker.{INFERRED_TYPE,DIRECT_METHOD_CALL_TARGET}` /
  `GroovyTypeCheckingExtensionSupport.TypeCheckingDSL` 及全部 DSL 钩子 —— 签名一致。

---

## 4. 坑清单（每个都让「检查」静默失效过）

继承前一轮交接文档的结论并继续遵守。**共同症状都是「报告无发现」**。

| # | 坑 | 症状 | 正确做法 |
| --- | --- | --- | --- |
| 1 | 编译相停太早 | 任何输入都「无发现」 | 必须到 **`Phases.INSTRUCTION_SELECTION`**（早于 `OUTPUT`，不写 class）|
| 2 | 注入扩展时传了 Class | 静默无发现 | `ASTTransformationCustomizer` **只认 String** |
| 3 | 用「没抛异常」判断「无发现」 | 无法区分「干净」与「没查」 | 编译正常返回后**仍要查 `errorCollector.errors`** |
| 4 | 没有阳性对照 | 「全绿」毫无意义 | 每条规则都要有必须被报出的输入，且放进**被测的那棵树** |
| 5 | 注解依赖随库传递 | 使用者被迫引入 JSR-305 | 注解用 **`compileOnly`** |
| 6 | 把它当一般库 | 想一个 jar 支持 4/5/6 | 类型检查扩展是**编译器插件**，必须与编译器同版本 ⇒ **按大版本分档** |
| 7 | **带参扩展名**（本次新增）| `'X(strict: true)'` 报「not found on the classpath」 | Groovy 4 用系统属性 / `setOptions()`，见 §2.2 |
| 8 | **注解保留策略**（本次新增）| `@Nullable` 完全不识别，**静默漏报** | 语料/使用方的注解必须 `@Retention(RUNTIME)`；`CLASS` 保留实测漏报 |
| 9 | **本地变量注解节点**（本次新增）| Groovy 4 上「本地变量声明」类规则**全部静默失效** | 注解可能挂在 `DeclarationExpression` 而非 `variableExpression` 上，见 §2.3 |

**第 1/2/3/7/8/9 条这一轮都真实发生过**，且**每一次都表现为「无发现」**。
唯一把它们抓出来的办法只有一个：**阳性对照 + 双跑**。

---

## 5. 语义等价性证据（双跑对照）

`./gradlew dualRun` 用同一套语料（29 个 fixture × 2 模式）跑两份实现，输出逐行可比报告：

```
build/reports/dual-run/groovy4.txt      ← 本实现
build/reports/dual-run/groovy6.txt      ← Groovy 6 官方
build/reports/dual-run/diff.txt         ← 差异
```

**实测结果：85 行 vs 87 行，唯一分歧是 §2.3 的本地变量声明：**

| 语料 | 本实现（Groovy 4）| Groovy 6 官方 | 说明 |
| --- | --- | --- | --- |
| `positive/nonnull_local_declaration.groovy` [annotations] | **1 条** | **0 条** | 见下 |

**分析**：`@NonNull String s = null` 作为**方法内**局部声明。
双跑的变量不止一个实现，还有两代 Groovy 的注解挂载差异：

- Groovy 4 把注解挂 `DeclarationExpression` ⇒ 本实现（用 `declAnnoCarrier`）能读到 ⇒ 报。
- Groovy 6 把注解挂 `variableExpression` —— **但实测官方 `NullChecker` 在此处仍报 0 条**。

即：这**不能**简单归因为「本实现更严」或「官方漏报」，它是
**「实现差异 × AST 差异 × 版本差异」的叠加**，无法在不改变已核实行为的前提下消除。

**处置**：作为**已知偏离**保留并记录在此，**不**为了对齐而关掉本地变量检查
（关掉会让 Groovy 4 用户丢掉一整类规则）。其余全部语料**逐字对齐**。

**其余对齐的部分**（全部实测一致）：

- 注解驱动：`@NonNull` 方法返回 null / 返回 `@Nullable` 值；给 `@NonNull` 字段或本地变量赋 null；
  `@MonotonicNonNull` 非空后回赋 null；`@Nullable` 参数/返回值/属性解引用；字面 null 解引用；
  传 null 或 `@Nullable` 值给 `@NonNull` 参数。
- `strict` 流敏感：变量经赋值/初始化变 null 后解引用；`@NonNull` 字段未初始化 / 未被所有构造器初始化。
- 守卫（**全部 0 误报**，两代实现一致）：`if (x != null)` / `if (x == null) else` / 提前 `return` /
  提前 `throw` / `?.` / `if (x)` / `if (!x)` / `&&` / `||` / `instanceof` / `!instanceof` /
  `Objects.nonNull` / `Objects.isNull` / `assert x` / `assert x != null` / elvis / 三元 /
  `while (x != null)` / `requireNonNull` / `checkNotNull` / `assertNotNull`（含 message 参数）/
  `assertThat(x).isNotNull()`。

> ⚠️ **双跑自身的坑（本轮踩到）**：最初语料把扩展名**写死**成本实现的 FQCN，
> Groovy 6 加载不到该类 ⇒ **每个 fixture 都「无发现」**，看起来像「两边完全一致」。
> 这是**最危险的一种假绿**。现在 fixture 写占位符 `@NULLCHECKER_EXTENSION@`，
> 由运行方替换成各自实现的名字。任何「双跑结果完全一致」的结论，
> 都必须先确认**参考实现那边真的跑起来了**（本项目的 `dualRun` 会打印 reference 的行数，
> 为 0 就说明没跑，而不是「干净」）。

---

## 6. 已知降级 / 局限

| # | 事项 | 影响 | 说明 |
| --- | --- | --- | --- |
| 1 | 两个 `StaticTypesMarker` 缺失（§2.1）| 依赖**类型推断**产出非空信息的两条快路在 4 下不生效 | 注解路径兜底，但依赖推断的场景可能比 6 **少报** |
| 2 | `strict` 语法不兼容（§2.2）| 功能等价，**语法不兼容** | 4 不能用 `'X(strict: true)'` |
| 3 | 本地变量声明（§2.3）| **1 处**已知偏离 | 见 §5 |
| 4 | `@MonotonicNonNull` 跨方法跟踪 | 在 A 方法置非空、B 方法回赋 null **不报** | 检查器按方法体跟踪，上游同样如此。语料把初始化与失效放在同一方法内 |
| 5 | `deref_null_literal` 类语料 | 触发通用静态类型错误而非本规则 | 用 `null.toString()` 才能触发 R06；`null.length()` 会先被 Groovy 自身的类型检查拦下 |
| 6 | 动态代码（无 `@CompileStatic`）| 大型真实代码库上的噪音/漏报比例**未测** | 需要先在真实项目上观察模式跑一轮 |
| 7 | 全面语义等价性 | **未证**；仅在现有 29 个语料 × 2 模式上逐字对齐（除已知偏离）| 靠持续扩语料 + 双跑守 |

---

## 7. 复现

```bash
cd groovy4-nullchecker

./gradlew check       # 语料门禁：29 fixture × 2 模式 + 覆盖率门禁
./gradlew dualRun     # 双跑对照：本实现 vs Groovy 6 官方，输出 diff
./gradlew publishToMavenLocal

# 单独跑 CLI（产物 + 注解都在 test classpath 上）
CP="build/classes/groovy/main:build/classes/java/test:$(./gradlew -q printTestClasspath)"
java -cp "$CP" groovy.ui.GroovyMain tools/nullcheck.groovy --support corpus/support corpus/positive
```

取上游源码重做基线：

```bash
B=https://raw.githubusercontent.com/apache/groovy/GROOVY_6_0_0/subprojects/groovy-typecheckers/src/main/groovy
curl -sS -o NullChecker.groovy     "$B/groovy/typecheckers/NullChecker.groovy"
curl -sS -o CheckingVisitor.groovy "$B/org/apache/groovy/typecheckers/CheckingVisitor.groovy"
shasum -a 256 NullChecker.groovy CheckingVisitor.groovy   # 对照文首哈希
```
