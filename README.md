# groovy4-nullchecker

> ★ **要动手改接入方式的话，先看 [the integration notes](the integration notes)** —— 那是来自
> `a real-world project`（真实 Groovy 4 项目，43 个类**全是 `@CompileStatic`**）的接入反馈与任务单：
> 含一个**必修缺陷**（`@CompileStatic` 类被静默跳过，附最小复现）、可直接搬的参考实现，
> 以及"做完了"的可执行验收清单。

**把 Groovy 6 的编译期空值检查器 `NullChecker` 回移到 Groovy 4.0.x。**

Groovy 4 的 `groovy-typecheckers` 里**只有 `RegexChecker`**（实测 4.0.0 → 最新的 4.0.33 都没有
`NullChecker`），而 `NullChecker` 是 Groovy 6.0 才加入的。本项目让你在 Groovy 4 项目上直接用上它：
识别 `@Nullable` / `@NonNull` / `@MonotonicNonNull` 一类注解，在**编译期**报出潜在的空指针。

- **静态分析，不运行代码**：只编译到类型检查相，不生成 class 文件。
- **零运行时依赖**：`groovy` 与注解依赖都是 `compileOnly`，不会传递给你的项目。
- **只支持 Groovy 4.0.x**：类型检查扩展是**编译器插件**，必须与被检查的编译器同版本。

---

## 快速开始

### 1. 引入

```groovy
dependencies {
    compileOnly 'io.github.heyuch:groovy4-nullchecker:0.1.0'   // 只在编译期需要
}
```

发布到本地仓库：`./gradlew publishToMavenLocal`

> **还没发布到 Maven Central 时，怎么在别的本地项目里试？**
> `publishToMavenLocal` 装进本地 Maven 仓库即可（等价于 `mvn install`）；
> 其他项目只要在仓库列表里加一个 `mavenLocal()`，然后正常写依赖坐标。
> 完整步骤、**可直接复制的示例工程**、以及几个必踩的坑（重发后要刷新依赖缓存、
> `GroovyCompile` 没有 `systemProperty`、`extensions` 必须传 String、注解要 RUNTIME）
> 见 **[examples/consumer-strict-demo](examples/consumer-strict-demo/README.md)**。
>
> 也可以在不改任何构建配置的前提下先用自带 CLI 摸底 —— CLI 就在发布产物里：
>
> ```bash
> ./gradlew nullcheck                                  # 报告，不阻断
> ./gradlew nullcheck -Pnullcheck.strict=true          # 加上流敏感
> ./gradlew nullcheck -Pnullcheck.failOnError=true     # 当门禁
> ```

> 注解依赖（JSR-305 / JetBrains / Spring 等）**本项目不传递**。
> 你的项目用哪套注解就引哪套；检查器按**简单名**识别，任何包的 `@Nullable` 都认。

### 2. 开启检查

最小方式是给类加注解：

```groovy
import groovy.transform.TypeChecked
import javax.annotation.Nonnull

@TypeChecked(extensions = 'io.github.heyuch.groovy4.nullchecker.NullChecker')
class Service {
    int size(@Nonnull String input) {
        return input.length()
    }

    int unsafe(String maybe) {
        return maybe?.length() ?: 0      // 安全导航 ⇒ 不报
    }
}
```

### 3. 接进构建（不改源码）

> ⚠️ **如果你的类用了 `@CompileStatic`，请直接跳到 §3.2。**
> `@CompileStatic` 是 Groovy 里最常见的注解，而**裸的 `ASTTransformationCustomizer`
> 到不了这类类** —— 它们被**静默跳过**，于是整个项目报 `0 findings`，
> 而那个 0 的意思是「**什么都没查**」而不是「干净」。这是实测过的缺陷，见 §3.2 与 §9。

#### 3.1 纯注解代码：一行注入

**注意 `extensions` 必须传 String** —— 传 `Class` 或 `ClassExpression` 会被**静默忽略**：

```groovy
// config/nullcheck.groovy
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer

configuration.addCompilationCustomizers(new ASTTransformationCustomizer(
        [extensions: ['io.github.heyuch.groovy4.nullchecker.NullChecker']],
        Class.forName('groovy.transform.TypeChecked')            // ← String，不是 Class
))
```

#### 3.2 有 `@CompileStatic` 的代码：需要声明注册相

必须用一个 `CompilationCustomizer` 包装器把注册**声明在 `CANONICALIZATION` 相**
（`CONVERSION` / `SEMANTIC_ANALYSIS` 也可以；**`INSTRUCTION_SELECTION` 不行**，那时类型检查变换已经处理完了）。

这个包装器**必须内联在你的 configuration script 里**，不能用 jar 里的 ——
实测：Gradle 编译 configuration script 用的类加载器**不含项目 compile classpath**，
连 `Class.forName('…NullCheckCustomizer')` 都会 `ClassNotFoundException`。
好在包装器只用 Groovy 自带的类，不需要任何 import：

```groovy
// config/nullcheck.groovy —— 自包含，无外部 import
import groovy.transform.CompilationUnitAware
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.customizers.CompilationCustomizer

class NullCheckRegistration extends CompilationCustomizer implements CompilationUnitAware {

    private final ASTTransformationCustomizer delegate

    NullCheckRegistration(CompilePhase phase, ASTTransformationCustomizer delegate) {
        super(phase)
        this.delegate = delegate
    }

    @Override
    void setCompilationUnit(CompilationUnit unit) {
        // ASTTransformationCustomizer 是 CompilationUnitAware，必须转发：
        // 漏掉这一句，就是一个「看起来对、其实什么都没查」的包装器
        if (delegate instanceof CompilationUnitAware) {
            ((CompilationUnitAware) delegate).setCompilationUnit(unit)
        }
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        delegate.call(source, context, classNode)
    }
}

def inner = new ASTTransformationCustomizer(
        [extensions: ['io.github.heyuch.groovy4.nullchecker.NullChecker']],
        Class.forName('groovy.transform.TypeChecked'))

configuration.addCompilationCustomizers(
        new NullCheckRegistration(CompilePhase.CANONICALIZATION, inner))
```

挂上脚本：

```groovy
tasks.withType(GroovyCompile).configureEach {
    groovyOptions.configurationScript = file('config/nullcheck.groovy')
}
```

> 为什么这里有 20 行样板而不是一行？因为 Gradle 的 configuration script
> 跑在一个**隔离的类加载器**里（连反射都拿不到你的依赖），而检查器是
> `compileOnly` 的。若把检查器改成 `implementation`（污染运行时依赖）也**解决不了**
> —— 实测仍然 `ClassNotFoundException`。所以自包含是唯一稳妥的写法。
>
> **不想写样板？用 CLI 那条路**（§4），它对 `@CompileStatic` 是完全支持的，
> 而且不需要改源码、也不需要 configuration script。

**验证你的注入是否真的生效**（别只看构建是绿的）：

在某个 `@CompileStatic` 类里故意写一处 `@Nullable` 解引用 —— **预期构建失败并报
`Potential null dereference`**。如果构建仍然是绿的，说明注入没生效，那个「绿」没有意义。

---

### 4. 推荐入口：CLI

CLI 是**所有构建系统的最小公倍数**，支持 `@CompileStatic`、包级注解、strict 模式，
而且**不需要改源码、也不需要 configuration script**：

```bash
java -cp <你的jar>:groovy-4.0.30.jar:<注解jar> \
     io.github.heyuch.groovy4.nullchecker.harness.NullCheckCli src/main/groovy
```

Gradle 里包一层（报告模式，不阻断构建）：

```groovy
configurations { nullcheckTool }
dependencies {
    nullcheckTool 'io.github.heyuch:groovy4-nullchecker:0.1.0'
    nullcheckTool 'org.apache.groovy:groovy:4.0.30'   // 检查器把 Groovy 声明为 compileOnly
}

tasks.register('nullcheck', JavaExec) {
    classpath = configurations.nullcheckTool + files(sourceSets.main.output)
    mainClass = 'io.github.heyuch.groovy4.nullchecker.harness.NullCheckCli'
    args = ['src/main/groovy']
    def result = ignoreExitValue = true
    doLast {
        int code = executionResult.get().exitValue
        if (code == 2) throw new GradleException('nullcheck: the checker is not working')
        if (code == 1 && project.hasProperty('nullcheck.failOnError')) {
            throw new GradleException('nullcheck: findings reported')
        }
    }
}
```

完整可运行示例见 [`examples/consumer-strict-demo`](examples/consumer-strict-demo/README.md)。

#### 退出码契约

| 码 | 含义 |
| --- | --- |
| `0` | 无发现 |
| `1` | 有发现 |
| **`2`** | **检查器没在正常工作**（自检失败、扩展名写错、无输入）|

> ⚠️ **`2` 与 `0` 必须区别对待。** 把 `2` 当成「通过」就等于把「没查」当成「干净」。

#### 自证：为什么 `0` 可以信

CLI 在报告任何结果**之前**，先编译一个**内置的、必然违规的样本**，其中**包含一个
`@CompileStatic` 类**（就是失效的那一类）。它必须被报出，否则 CLI **拒绝报告结果并以 `2` 退出**：

```
canary     = ok (2 findings on the built-in sample, including its @CompileStatic class)
findings   = 0
coverage   = files=43 classes=50 compileStatic=46 nonNullByDefault=50 methodsWithReturnType=223
```

`coverage` 是让 `0` 可解释的东西 —— 它说明**实际看了多少**。
真实项目上实测（43 个文件、全部 `@CompileStatic`）：`compileStatic=46`（含内部类）、
`findings=0`，所以这个 0 是**有意义的 0**。

跳过自检（**不推荐**）：`--no-canary`。

---

## 检查什么

### 会报出

| 场景 | 例子 |
| --- | --- |
| 返回 null 的 `@NonNull` 方法 | `@NonNull String f() { null }` |
| 返回 `@Nullable` 值 | `@NonNull String f() { maybe() }` |
| 给 `@NonNull` 变量赋 null | `@NonNull String s = null` |
| `@MonotonicNonNull` 非空后回赋 null | `cached = 'x'; cached = null` |
| 解引用 `@Nullable` 值 | `@Nullable String s; s.length()` |
| 解引用 `@Nullable` 方法返回值 | `maybe().length()` |
| 解引用安全导航结果 | `a?.b.c`（应写 `a?.b?.c`）|
| 传 null 给 `@NonNull` 参数 | `take(null)` |
| 传 `@Nullable` 值给 `@NonNull` 参数 | `take(maybe())` |
| **`strict` 模式**：流敏感解引用 | `String s = null; s.length()` |
| **`strict` 模式**：`@NonNull` 字段未初始化 | 声明了但没有任何构造器/初始化块赋值 |
| **包级默认**：整个包非空 | 见下节 |

### 包级默认：一个包一个文件，而不是一个类一个注解

给包标 `@NullMarked`（或 `@NonNullByDefault`），该包内**所有类**都变成非空默认，
不必逐类标注。`@NullUnmarked` 可以按类退出。

```java
// src/main/java/com/acme/order/package-info.java
@org.jspecify.annotations.NullMarked
package com.acme.order;
```

```groovy
// 同一个包里，这个类没有任何注解 —— 但仍然被检查
package com.acme.order

class OrderService {
    String findName() {
        return null        // ← 报出：包默认它是非空的
    }
}
```

支持 `package-info.java` 和 `package-info.groovy` 两种写法；依赖 jar 里**预编译**的
`package-info.class` 也认（要求注解是 RUNTIME 保留，常见的空值注解都是）。

> **这是本库相对官方 Groovy 6 `NullChecker` 的增强。** 官方检查器**完全不读**包级注解：
> 同一份代码在 Groovy 6 下**不报**，在本库下报出。`./gradlew dualRun` 会把这条差异
> 稳定地列在 `only-in-port` 一侧 —— 那是**特性差异，不是回归**。
>
> **作用域**：按 JSpecify 语义，标记只作用于**被标注的那个包本身**，
> **不覆盖子包**（子包各自声明）—— 语料里有一条守卫专门钉住这个语义。

### 认得的守卫（这些**不会**误报）

`if (x != null)` ｜ `if (x == null) { … } else { … }` ｜ 提前 `return` / 提前 `throw` ｜
安全导航 `?.` ｜ Groovy 真值 `if (x)` / `if (!x)` ｜ `&&` / `||` 短路 ｜ `instanceof` / `!instanceof` ｜
`Objects.nonNull(x)` / `Objects.isNull(x)` ｜ `assert x` / `assert x != null` ｜ elvis `?:` ｜ 三元 ｜
`while (x != null)` ｜ 校验器 `Objects.requireNonNull(x)` / `checkNotNull(x)` ｜
测试断言 `assertNotNull(x)`（含 message 参数）｜ `assertThat(x).isNotNull()`

与注解一样，校验器/断言方法也是**按简单名**匹配的，所以 JUnit、TestNG、AssertJ、Truth、Guava 都认。

---

## `strict` 模式（流敏感）

除注解外，还能跟踪未注解代码里「经赋值变成 null」的变量、以及 `@NonNull` 字段是否被确定初始化。

```groovy
String s = null
s.length()        // strict 关：不报；strict 开：报「's' may be null」
```

> ⚠️ **Groovy 4 下不能用带参扩展名。**
> 官方文档里的 `@TypeChecked(extensions = '…NullChecker(strict: true)')` 只有 Groovy 6 能用；
> Groovy 4 的 `GroovyTypeCheckingExtensionSupport` 会拿**整个字符串**去 `loadClass`，
> 于是报一句**误导性**的 `… was not found on the classpath`（看起来像类名写错，其实是参数语法不支持）。

Groovy 4 上改用下面任一方式：

```groovy
// ① Gradle 构建里全局开启（GroovyCompile 上【没有】systemProperty，要用 forkOptions）
tasks.withType(GroovyCompile).configureEach {
    options.forkOptions.jvmArgs = ['-Dgroovy.nullchecker.strict=true']
}

// ② 直接跑编译器时用 JVM 参数
-Dgroovy.nullchecker.strict=true

// ③ 程序化（编译插件、自定义 Gradle 任务）
io.github.heyuch.groovy4.nullchecker.NullChecker.setOptions([strict: true])
```

> 上面三种都已实测可用（其中 ① 是在一个只依赖发布产物的**独立消费者工程**里验证的）。
> 注意 `GroovyCompile` 任务上没有 `systemProperty` 方法，只有 `options.forkOptions.jvmArgs`。

---

## 从 Groovy 6 迁移的注意点

| 事项 | 说明 |
| --- | --- |
| 类名不同 | 用 `io.github.heyuch.groovy4.nullchecker.NullChecker`，**不是** `groovy.typecheckers.NullChecker` |
| `strict` 语法不同 | 见上；功能等价，语法不兼容 |
| 注解必须 `@Retention(RUNTIME)` | 用 `CLASS` 保留实测**静默漏报**（见 [PORT.md](PORT.md) 坑 8）|
| 本地变量声明的 `@NonNull` | 本实现比 Groovy 6 官方**多报** 1 处（Groovy 4 把局部注解挂在不同 AST 节点上，见 [PORT.md](PORT.md) §5）|
| 依赖类型推断的场景 | 可能比 Groovy 6 **少报**（Groovy 4 缺两个 `StaticTypesMarker`，见 [PORT.md](PORT.md) §2.1）|

完整偏离清单、证据与坑清单见 **[PORT.md](PORT.md)**；
回移前的可行性调研见 **[the implementation plan](the implementation plan)** 与 **[the hand-over notes](the hand-over notes)**。

---

## 开发

```bash
./gradlew check       # 语料门禁：29 个语料 × 2 模式 + 规则覆盖率门禁
./gradlew dualRun     # 双跑对照：本实现 vs Groovy 6 官方，输出 build/reports/dual-run/
```

**语料门禁是这个项目的核心。** 一个不能区分「查过且干净」和「根本没查」的检查器，
比没有检查器更危险 —— 所以本项目的每条规则都必须有一个**必须被报出来**的语料，
每种守卫形态都必须有一个**必须保持沉默**的语料，且没有语料覆盖的规则会让构建变红。
新增规则时请同时在 `corpus/` 加对应语料并在 `corpus/MANIFEST.txt` 里登记。

源码结构：

```
src/main/groovy/io/github/heyuch/groovy4/nullchecker/
├── NullChecker.groovy          # 回移主体（改 4 处 API + 1 处注解节点）
├── CheckingVisitor.groovy      # 仅改包名
└── harness/
    ├── NullCheckHarness.groovy # 编译器调用：相号/注入/错误收集三件事只在这里做一次
    ├── CorpusGate.groovy       # 语料门禁
    └── CorpusRules.groovy      # 规则清单（覆盖率门禁的唯一来源）
corpus/                         # 自包含语料（含注解与桩类）
tools/nullcheck.groovy          # CLI
tools/dual-run.groovy           # 双跑对照（可分别跑在 Groovy 4 / 6 下）
```

---

## 许可

回移自 Apache Groovy（Apache License 2.0）。源文件保留原始许可头与上游 sha256 指纹，见 [PORT.md](PORT.md)。

### §12.1 更正：全局注入**可以**覆盖 `@CompileStatic`（本节早期结论过于绝对）

本文档早先写过"全局注入在 `@CompileStatic` 代码库上走不通"。**那条结论是错的**，在此更正 ——
它会影响你对 M0 的设计，所以请以本节为准。

在 `a real-world project` 上重做的实测（同一个探针，只换注入方式）：

| 注入方式 | `@CompileStatic` 类上的结果 |
| --- | --- |
| 直接 `new ASTTransformationCustomizer(params, ann)` | ❌ 不报 |
| **自己写子类覆盖 `getPhase()`**（phase 取值相同也行）| ✅ **报出** |
| 把 `@TypeChecked` 写在源码里 | ✅ 报出 |

**结论**：变的不是注解内容、也不是 phase 的取值，而是**自定义器以什么相注册**。
用一个几行的包装器（**组合，不是继承** —— 见下）把注册声明在 `CANONICALIZATION`，
就能让扩展对所有 `@CompileStatic` 类生效，**不需要改动任何业务源码**。

**本仓库已复现并已修复**（这一条不再是"待办"，而是已落地）：

| 注入方式 | `@CompileStatic` 类上的实测结果 |
| --- | --- |
| 裸 `new ASTTransformationCustomizer(params, ann)` | ❌ 不报（**静默跳过**）|
| 源码里写 `@TypeChecked(extensions='…')` | ❌ 不报（**`@CompileStatic` 在时注解也无效**）|
| 加上 `--strict` | ❌ 不报 |
| **`NullCheckCustomizer`（本仓库提供，声明 `CANONICALIZATION` 相）** | ✅ **报出** |
| 包装器注册在 `CONVERSION` / `SEMANTIC_ANALYSIS` | ✅ 报出 |
| 包装器注册在 `INSTRUCTION_SELECTION` | ❌ 不报（类型检查变换已处理完）|

补充两条**实测纠正**，与早期记录不同：

- **不能靠"覆盖 `getPhase()`"的子类**：Groovy 4 的 `ASTTransformationCustomizer`
  构造函数用**动态属性赋值**写自己的 `annotationNode`，一旦被 Groovy 写的子类继承就炸
  （`MissingPropertyException: No such property: annotationNode`）。**必须用组合包装**。
- **`@TypeChecked` 写在源码里也救不了**：只要类上有 `@CompileStatic`，裸注入和源码注解
  **两种写法都不报**。所以"文档可以保持简单"是不成立的 —— §3 已按此改写。

包装器的 `setCompilationUnit` 必须**转发**给内层（`ASTTransformationCustomizer` 是
`CompilationUnitAware`）；漏掉它能得到一个"看起来对、其实什么都没查"的包装器。

（附带一条教训：这次错误正是"全绿其实没在查"的第 5 次出现。所以现在
**CLI 内置常驻阳性对照（canary）+ 覆盖计数**，自检不过就 `exit 2` 而不是报 0 —— 见 README §4。）

---

## 13. 一个可以超过官方实现的机会：包级注解

Groovy **支持** `package-info.groovy`（与 Java 的 `package-info.java` 等价），实测（Groovy 4.0.30）：

```
groovyc io/github/heyuch/the formatter project/probe/package-info.groovy   # 内容：@NullMarked + package 声明
→ 生成 io/github/heyuch/the formatter project/probe/package-info.class
→ javap -v 显示该注解落在包上：RuntimeVisibleAnnotations: org.jspecify.annotations.NullMarked
```

**但 Groovy 6 的 `NullChecker` 不读包级注解**：给包标 `@NullMarked` 之后，
该包里一个**类级没有标记**、且方法 `return null` 的类**没有被报出**（实测，同时子包也未覆盖）。

⇒ **这就是 groovy4-nullchecker 做得比官方好的地方**：使用者可以用 **每个包一个文件** 声明默认
（我们这个仓库是 **9 个包** 而不是 **41 个类**），而且仍然完全在源码里可见 ——
这比逐类注解更符合"注解是签名的一部分"的诉求。

**注意作用域**：按 JSpecify 的语义，`@NullMarked` 的作用域是**被标注的那个元素本身**，
所以包级注解**不覆盖子包**（子包需各自声明）。

### 13.1 已实现 ✓（本节其余部分是设计依据，实现见 README「包级默认」一节）

包级默认**已经实现并通过语料门禁**，三种注解来源都实测可用：

| 来源 | 实测结果 |
| --- | --- |
| 同一次编译里的 `package-info.java` | ✅ 生效 |
| 同一次编译里的 `package-info.groovy` | ✅ 生效 |
| 依赖 jar 里**预编译**的 `package-info.class` | ✅ 生效（经 JVM package 对象反射读取）|

**实现要点（踩了三次才做对，别照直觉写）**：

1. **不能读 `classNode.annotations`**。`package-info` 的注解在 Groovy 里落在
   **`PackageNode`** 上（`@Foo package a.b;` 解析到包上），那个合成类节点是**空的**。
2. **不能只读 `classNode.package.annotations`**。Groovy 4 给**每个源文件**都建了**独立的
   `PackageNode` 实例**：`package-info` 那个实例带着注解，而同包其它类的 `PackageNode`
   注解列表是**空的**。所以同一次编译里、包内普通类**看不到**包级注解。
3. ⇒ 必须在 `setup()` 阶段**先扫描整个 `CompilationUnit`** 把所有 `package-info` 的
   **`PackageNode` 注解**收集起来，再按包名查表。放在 `beforeVisitClass`/`afterVisitClass`
   里都会因为**访问顺序**而不稳定（`package-info` 可能晚于同包类被访问）。

另外两条**顺带修掉的一致性问题**：

- 包级默认原本只作用于**返回值和参数**，**不作用于字段初始化检查** ——
  同一个标记对"返回值"和"字段"含义不同。现已在 `isNonNullAnnotated()` 里统一。
- CLI/门禁原来只认 `*.groovy`，会**跳过 `package-info.java`** ——
  而后者正是 Groovy 项目声明包级注解最常见的形式。现在两者都认
  （仍然**不**碰普通 `.java`：Groovy 类型检查器解析不了任意 Java）。

**仍未解决的限制**（写进文档，不假装支持）：

- 依赖 jar 里的 `package-info.class` 若是 **CLASS 保留**，反射读不到 ——
  只能靠源码内可见或 RUNTIME 保留。常见空值注解（JSpecify / JSR-305 / JetBrains /
  Checker Framework）都是 RUNTIME，所以实务上不受影响。
- 子包**不**继承父包的标记（刻意的，见上「注意作用域」）。
- 这一能力**无法与官方 Groovy 6 对齐**（官方根本不读），所以 `./gradlew dualRun`
  里这几条语料会稳定地出现在 **only-in-port** 一侧 —— 那是**特性差异**，不是回归。

