# Gradle 插件

一行接入：`nullcheck` 任务在**不改源码**的前提下，对整个项目做一次编译期空值检查。
默认是独立的 CLI 编译；`compileCheck = true` 时改为复用 `compileGroovy`，省掉第二次编译。

- 完整支持 **`@CompileStatic`**（这正是裸注入与源码注解都到不了的那一类）
- 完整支持**包级 `@NullMarked`**
- **检查器不进你的运行时依赖**：插件以 `compileOnly` 依赖它，任务在独立进程里跑 CLI
- **退出码 2 会让构建失败**，即使 `failOnError` 是关的（`2` = 检查器没跑起来）

---

## 安装

尚未发布到 Gradle Plugin Portal，所以先从本地 Maven 仓库取：

```bash
# 在 groovy-nullchecker 仓库里；两个模块同属一个构建，一条命令装齐
./gradlew publishToMavenLocal
```

## 使用

`settings.gradle` —— ⚠️ **`pluginManagement` 必须放在文件最前面**：

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

`build.gradle`：

```groovy
plugins {
    id 'groovy'
    id 'io.github.heyuch.groovy-nullchecker' version '0.7.1'   // 版本号必填
}

repositories {
    // 检查器是普通依赖，由插件的任务解析，所以仓库要声明在【本项目】上：
    // 项目一旦自己声明 repositories，settings 里的那些就会被忽略。
    mavenLocal()
    mavenCentral()
}
```

```bash
./gradlew nullcheck
```

### DSL（全部可选）

```groovy
nullcheck {
    compileCheck = false          // true：在 compileGroovy 内检查，省掉一次独立编译
    failOnError = true            // 有"失败类"发现就让构建失败（默认 false，只报告）
    failOn      = ['null', 'initialization']   // 哪些分类算失败；'all' 连解析错误一起失败
    markedOnly          = false    // true：只检查 @NullMarked 的类/包
    nullnessCheck       = true     // 默认开启：空指针/返回/传参/赋值
    initializationCheck = true     // 默认开启；关掉后完全不报"字段未初始化"
    strict      = true            // 流敏感：也分析未注解代码
    targets     = ['src/main/groovy', 'src/main/java']
    reportFile  = 'build/reports/nullcheck/nullcheck.txt'
    canary      = true            // 关掉自检（不建议）
    toolVersion = '0.7.1'

    // 项目自己的守卫方法：声明极性，而不是猜名字
    guardNotNullWhenTrue  = ['com.acme.Utils#isNotEmpty']        // 返回 true  ⇒ 参数 0 非空
    guardNotNullWhenFalse = ['com.acme.MyCollectionUtils#isNullOrEmpty']  // 返回 false ⇒ 参数 0 非空

    // 永不返回的方法：调用之后的语句按不可达处理（兜底 return null 不再报）
    neverReturns = ['com.acme.Panic#notExpectedResult']

    // 可能查不到的查找（Map.get / 下标 / find / max）按可空处理；默认开启
    containerLookups = true

    // 项目声明的契约：优先于注解和内置表；按 类全名#方法 匹配
    contracts = [
        'java.util.Map#get'         : '-> !null',
        'com.acme.Utils#isNotEmpty' : 'null -> false',
    ]
}
```

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `compileCheck` | `false` | `true`：把检查器挂进 `compileGroovy`，复用同一次编译；见下文"编译内检查" |
| `failOnError` | `false` | 有"失败类"发现时是否让构建失败。**建议先保持 false 摸底** |
| `failOn` | `['null', 'initialization']` | 哪些分类算失败。`resolution`（静态检查解析不了的动态代码）默认只报告不失败；`['all']` 恢复"任何发现都失败" |
| `markedOnly` | `false` | 只检查 `@NullMarked` 作用域内的类：类上有 `@NullMarked`，或类所在**精确包**的 `package-info` 有 `@NullMarked`。`@NullUnmarked` 优先。用于 legacy 项目渐进式接入 |
| `nullnessCheck` | `true` | 空值规则族（解引用 / 返回 / 传参 / 赋值） |
| `initializationCheck` | `true` | "字段必须初始化"规则族。**这是"报不报"，不是"失不失败"**：关掉后该类发现完全不进报告。两个都关会被拒绝——什么都不查不能看起来像干净 |
| `guardNotNullWhenTrue` | `[]` | 返回 true 即保证参数 0 非空的方法，`类全名#方法名` |
| `guardNotNullWhenFalse` | `[]` | 返回 false 即保证参数 0 非空的方法（如 `isNullOrEmpty`） |
| `neverReturns` | `[]` | 永不返回的方法，`类全名#方法名`。声明后调用之后的语句不可达，兜底 `return null` 不再报；`@NoReturn` 类注解、`@Contract('-> fail')` 或 `@Contract('_, _ -> fail')` 可替代 |
| `containerLookups` | `true` | 可能落空的查找按可空处理：`Map.get`/下标/`remove`/`put`/`find`/`max`/`min`。`containsKey` 在值非空时收窄；关掉后只有 `@Nullable` 值类型才让查找可空 |
| `contracts` | `[:]` | 项目声明的契约，`类全名#方法: '契约串'`（JetBrains 语法）。**优先于注解和内置表**，可用来纠正库的错误声明或覆盖内置规则 |
| `strict` | `false` | 打开后报告量会明显上升 |
| `targets` | `['src/main/groovy', 'src/main/java']` | 两个都要，否则 `package-info.java` 里的包级默认看不见。**不存在的根只会警告并跳过**（纯 Groovy 项目没有 `src/main/java`）；但**如果所有根都不存在**则报错退出，避免把根写错变成"扫了 0 个文件" |
| `reportFile` | `build/reports/nullcheck/nullcheck.txt` | 原始报告落盘位置 |
| `canary` | `true` | 关闭后**不再自证**，`0` 就无法区分"干净"和"没查" |
| `toolVersion` | `0.7.1` | 解析不到的版本会**明确失败**，不会静默变绿 |

---

### 渐进式接入（`markedOnly = true`）

```groovy
nullcheck {
    markedOnly = true
}
```

只有以下作用域会被检查：

- 类（或外层类）标注 `@NullMarked`；
- 类所在**精确包**的 `package-info` 标注 `@NullMarked`；
- 任意层出现 `@NullUnmarked` 会取消该范围。

未标记的类不会被检查，也不会进入 coverage 的 class 统计；适合先给少数叶子包加
`@NullMarked`，再逐步扩大。CLI 对应 `--marked-only`，程序化 API 对应
`groovy.nullchecker.markedonly=true`。

注意：`markedOnly` 过滤的是 checker 的 nullness 报告，不会吞掉 Groovy 静态类型检查器
对未标记类产生的 **resolution** 错误；后者默认只报告、不让构建失败，但设置
`failOn = ['all']` 时会参与失败判定。

### 编译内检查（`compileCheck = true`）

默认模式是独立 CLI：它不依赖 `compileGroovy`，所以**项目编译不过时也能出报告**。代价是每个模块
多一次完整编译。编译内模式复用同一次 `compileGroovy`：

```groovy
nullcheck {
    compileCheck = true
    failOnError  = true
}
```

实现方式：

- 检查器被放到**编译器 worker 的 classpath**（`compileGroovy.classpath` / `groovyClasspath`），
  不进入应用的 runtime/published 依赖；
- 插件生成一个 `configurationScript`，在 worker 内设置检查器参数，并把 findings 写到
  `build/nullcheck/findings.tsv`；
- 检查器在**收集模式**下工作：finding 不再变成编译器错误，而是写入该文件；
- `nullcheck` 任务依赖 `compileGroovy`，只读取该文件、打印并按 `failOn` / `failOnError` 决定是否失败。

代价与限制（务必读）：

- **编译失败就没有报告**：`nullcheck` 依赖 `compileGroovy`，源码编译不过时任务不会运行；
  "先量一下坏代码"的场景请用默认模式；
- **不检查 Java 源码**：`compileGroovy` 只编译 Groovy；`package-info.java` 仍会作为编译输入被读取；
- **canary 是独立的 `nullcheckCanary` 任务**：它编译一个内置的、必然违规的样本；只有退出码
  为 1（确实报出 finding）才继续，`nullcheck` 依赖它。关闭 `canary` 时不注册该任务；
- **要求项目 Groovy 是 4.0.x**：检查器运行在项目自己的编译器里，不再固定 4.0.30；
- **只注入静态类**：compile mode 只对 `@CompileStatic` / `@TypeChecked` / `@NullCheck` 的类
  （或其中带这些注解的方法）注入扩展；动态类不被注入，因此动态代码里解析不了的调用不会再被
  变成编译器错误。动态类的 resolution 仍由默认 standalone 模式报告；
- 不覆盖项目已有的 `groovyOptions.configurationScript`：插件生成的是包装脚本，先 `evaluate` 用户脚本，
  再注册检查器。若脚本是在插件的 `afterEvaluate` 之后才设置，插件看不到，仍可能被替换；
- 检查器会出现在该任务**编译 classpath**上（不是 runtime，也不是发布依赖），以便类型检查扩展能被加载。

## 输出怎么读

```
canary     = ok (2 findings on the built-in sample, including its @CompileStatic class)
FINDING src/main/groovy/demo/Violation.groovy:16: [Static type checking] - Potential null dereference: 'value' is @Nullable
findings   = 1
coverage   = files=2 classes=2 compileStatic=2 nonNullByDefault=0 methodsWithReturnType=2
nullcheck: findings reported (exit 1)
```

- **`canary`** —— 报告任何结果**之前**，先编译一个内置的、必然违规的样本，
  其中**含一个 `@CompileStatic` 类**。它必须被报出，否则任务以 `2` 失败、拒绝报告结果。
  没有这一步，一个静默失效的检查器会打印 `0 findings`，与"干净"无法区分。
- **`coverage`** —— 让 `0` 可解释：看了多少文件/类、其中多少是 `@CompileStatic`。
  在真实的 43 文件全 `@CompileStatic` 项目上实测是 `classes=50 compileStatic=46`。

### 退出码

| 码 | 含义 | 插件行为 |
| --- | --- | --- |
| `0` | 无发现 | 成功 |
| `1` | 有发现 | 成功（`failOnError=true` 时失败）|
| `2` | **检查器没在正常工作** | **一律失败**，不受 `failOnError` 影响 |

---

## 三个刻意的设计决定

**1. `nullcheck` 不依赖 `compileGroovy`。**
它只依赖 `compileJava` —— 那是注解类所在的那一步。所以你项目**编译不过的时候也能出报告**，
而这恰恰是最需要报告的时候。若让报告依赖 `compileGroovy`，一个编译错误就会把你挡在报告之外。

**2. 默认独立 CLI，`compileCheck` 才进编译流程。**
独立任务的好处是**不依赖 `compileGroovy`**：项目编译不过时仍能出报告，而且能跑 canary。
早期把检查器塞进 `compileGroovy` 确实失败了：configuration script 跑在隔离类加载器里，import 不到
检查器（`ClassNotFoundException`），而且 finding 直接变成编译错误。
现在的 `compileCheck` 解决了这两点：检查器被放进编译 classpath，configuration script 通过
`groovyClasspath` 加载它；finding 在收集模式下写入 findings 文件，而不是 `addStaticTypeError`。
它仍是**可选**的，因为"编译不过也能报告"和 canary 是默认模式独有的价值。

**3. 不存在的根只警告，全都不存在才报错。**
默认 `targets` 两个根都给了，但纯 Groovy 项目没有 `src/main/java`。若把"根不存在"当错误，
这些项目根本跑不起来；若静默忽略，把根名写错就会悄悄缩小分析范围。所以：**部分缺失 → 警告并继续；
全部缺失 → 明确失败**（退出码 2）。两种都在功能测试里钉住了。

**4. 检查器不进你的运行时 classpath。**
插件是 `compileOnly` 依赖它；任务把 `sourceSets.main.compileClasspath` 传给 CLI 用于解析注解，
仅此而已。检查器不会出现在你的 `runtimeClasspath`，也不会进你发布的 POM。

---

## 已验证

`./gradlew :nullchecker-gradle-plugin:functionalTest` —— 11 个用例，每个都**真跑一次 Gradle 构建**（TestKit）：

| 用例 | 断言 |
| --- | --- |
| `@CompileStatic` 违规 | 报出，且默认不阻断构建；输出含 `canary` 与 `coverage` |
| `failOnError = true` | 构建失败 |
| `@CompileStatic` 且守卫正确 | **不报**，且 `compileStatic=1`（证明真看了，不是跳过）|
| 包级 `@NullMarked` | 类上无注解也被覆盖 ⇒ `package-info` 确实被读到 |
| **项目编译不过** | `nullcheck` 仍出报告（`compileGroovy` 未被执行）|
| `toolVersion` 不存在 | 构建失败并给出该版本号，**不会静默变绿** |
| `compileCheck = true` | `compileGroovy` 内收集 finding，`nullcheck` 报告，默认不阻断 |
| `compileCheck + failOnError = true` | 构建失败 |
| `compileCheck` 且守卫正确 | `findings=0`，不误报 |

不写单元测试是刻意的：这个插件的全部职责就是组装出一个会产生特定退出码的任务，
而那类"看起来正常、其实什么都没查"的失效**只有在真实构建里才观察得到**。
