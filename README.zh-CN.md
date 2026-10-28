# nullchecker

[English](README.md) | 简体中文

面向 **Groovy 4** 的编译期空值检查。

Groovy 4 本身不带空值检查器：`groovy-typecheckers` 里只有 `RegexChecker`，能理解
`@Nullable` / `@NonNull` 的 `NullChecker` 是 Groovy 6 才加入的。本项目把该检查器回移到
Groovy 4.0.x，让空指针解引用在编译期就暴露，而不是等到生产环境。

```groovy
@NullMarked
class OrderService {

    String findName(@Nullable String id) {
        return id.length()          // ← 编译期报出
    }
}
```

- **编译期完成，零运行时开销。** 检查在编译时运行，不会进入运行时 classpath 或发布的 POM。
- **可用于 `@CompileStatic` 代码**——这很重要，因为真实 Groovy 大多是这种形态。
- **无需新增依赖。** 注解按简单名匹配，JSpecify、JSR-305、JetBrains、Spring 或自定义注解都可以。
- **绝不输出未经验证的结果。** 打印任何结论之前，它先证明自己仍能发现违规；做不到就明确失败。
  见 [为什么 `0` 可信](#为什么-0-可信)。

需要 **Groovy 4.0.x** 和 **Java 17+**。不适用于 Groovy 3、5、6：类型检查扩展是编译器插件，
必须与编译器主版本匹配。

---

## 快速开始

三种接入方式。它们对构建的要求不同——特别是**只有后两种需要依赖**：

| | 你需要加什么 | 检查器从哪来 |
| --- | --- | --- |
| **A. Gradle 插件** | `plugins {}` 里一行 | 任务运行时由插件解析 |
| **B. `@TypeChecked(extensions = …)`** | 一个 `compileOnly` 依赖 | 你的编译 classpath |
| **C. 命令行** | 一个依赖，或显式 `-cp` | 你放进 classpath 的东西 |

方案 A 不需要依赖，因为插件不会链接检查器：它只保存 artifact 坐标，在任务执行时解析，
所以一行确实够用。它真正需要的是一个能提供该 artifact 的仓库——发布后用
`mavenCentral()`，之前用 `mavenLocal()`。

方案 B 和 C 需要 jar，因为编译器要加载扩展类：

```groovy
repositories {
    mavenCentral()
}

dependencies {
    // 仅编译期使用。它永远不会进入你的运行时 classpath。
    compileOnly 'io.github.heyuch:groovy-nullchecker:0.11.0'
}
```

你**不需要** `groovy-typecheckers`，也不需要额外引入注解库——用项目里已有的即可。

如果完全不想依赖仓库，每个 release 也直接附带 jar。`groovy-nullchecker-<version>.jar`
是自包含的——丢进编译 classpath，B 或 C 就能用，不需要坐标也不需要仓库：

```groovy
dependencies {
    compileOnly files('libs/groovy-nullchecker-0.11.0.jar')
}
```

该 jar 自身没有依赖：Groovy 和注解库由你提供，Groovy 项目本来就有。（插件不能这样用——
Gradle 需要仓库里的 plugin marker，所以只能按 id 安装。）

### A. Gradle 插件（推荐——一行）

完整 DSL 见 [nullchecker-gradle-plugin](nullchecker-gradle-plugin/README.md)：

```groovy
plugins {
    id 'io.github.heyuch.groovy-nullchecker' version '0.11.0'
}
```

```bash
./gradlew nullcheck
```

### B. 给想检查的类加注解

除了上面的 `compileOnly` 依赖，不需要改构建，因此在现有代码库上试用最安全：

```groovy
import groovy.transform.TypeChecked

@TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')
class OrderService {
    // ...
}
```

> 如果类是 `@CompileStatic`，只加注解**不够**——检查器必须在正确的编译阶段注册。
> 请用方案 A 或 C，或 [PORT.md §5.1](PORT.md) 里的包装方式。

### C. 直接检查源码树

插件做的一切都可以通过 CLI 完成，适合任何构建系统：

```bash
java -cp <checker jar>:<groovy 4 jar>:<your compile classpath> \
     io.github.heyuch.groovy.nullchecker.harness.NullCheckCli src/main/groovy
```

两种接入方式的可运行示例都在 [examples/](examples/)。

---

## 给代码加注解

注解怎么写，决定了检查能看到什么。注解按**简单名**匹配，所以任何库都可以；示例使用
JSpecify，新代码建议用它：

```groovy
compileOnly 'org.jspecify:jspecify:1.0.0'
```

无论选哪个库，注解类都必须在被检查模块的**编译 classpath** 上。检查器会解析注解类本身，
包括编译产物依赖里的注解——解析不到的注解会被静默忽略。

### 你想表达什么，就写什么

| 你想表达 | 写什么 | 写在哪 |
| --- | --- | --- |
| 这个包默认非空 | `@NullMarked` | `package-info.java` |
| 这个**值**可能为空 | `@Nullable` | 返回值、参数、字段、局部变量 |
| 这个**容器的元素**可能为空 | 在类型实参里写 `@Nullable` | `List<@Nullable X>`、`Set<@Nullable X>` |
| 这个 **map 的 value** 可能为空 | 在 value 位置写 `@Nullable` | `Map<K, @Nullable V>` |
| 这个 **map 的 key** 可能为空 | 在 key 位置写 `@Nullable` | `Map<@Nullable K, V>` |
| 这个包还没准备好 | `@NullUnmarked` | 类或 `package-info.java` |
| 这个 helper 能证明参数非空 | `@EnsuresNonNullIf`、`@EnsuresNonNull`、JetBrains `@Contract` | 写在 helper 上 |

### 从一个包开始

```java
// src/main/java/com/example/order/package-info.java
@org.jspecify.annotations.NullMarked
package com.example.order;
```

该包内一切默认非空，因此只需要标例外。没动过的包行为不变；一次只迁移一个包。

### 可空的值

```groovy
@NullMarked
class OrderService {
    @Nullable
    Order find(String id) {          // 可能查不到
        return repository.find(id)
    }

    void apply(@Nullable String note) {   // 调用方可以不传
    }

    @Nullable
    String remark                      // 这个 bean 上可能没有
}
```

调用方必须先判断再解引用，检查接受所有常见守卫：`if (order != null)`、
`order?.total() ?: 0`、提前 `return`、循环里的 `continue`。

### 可空的容器 vs 可空的元素

这两者不同，而这个区别正是该特性的全部意义：

```groovy
@Nullable List<String> maybeMissing    // 列表本身可能为 null
List<@Nullable String> mayContainNull  // 列表存在，部分元素为 null
```

对第二种，每一种取元素的方式得到的都是可空值，因此不判断就会报出：

```groovy
names[0].length()               // 下标
names.get(0).length()           // get
names.first().length()          // DGM 访问器
for (String n : names) n.length()        // for-each 变量
names.each { it.length() }               // 闭包参数和隐式 it
```

Map 的 key 和 value 分别跟踪：

```groovy
Map<String, @Nullable String> byCode     // value 可能为空
Map<@Nullable String, String> byName     // key 可能为空

byCode.get(code).length()
byCode[code].length()
for (String v : byCode.values()) v.length()
for (String k : byName.keySet()) k.length()
for (Map.Entry<String, String> e : byCode) e.value.length()
byCode.each { k, v -> v.length() }
```

注解描述的是"读出来可能是什么"，它本身不约束"往里放什么"。详细规则，以及两种不跟踪的
形态，见[容器元素](#容器元素)。

### 能证明非空的 helper

当守卫是一个方法时，把结论写在方法上，而不是在每个调用点重复：

```groovy
// 集合为 null 或空时返回 false
@EnsuresNonNullIf(expression = '#1', result = false)
static boolean isNullOrEmpty(Collection<?> values) { ... }

// 参数为 null 时抛异常，因此调用返回后参数非空
@EnsuresNonNull(expression = '#1')
static <T> T require(T value) { ... }

// 同样的两种，适合已经为 IntelliJ 标注过 helper 的项目
@Contract('null -> true')  static boolean isNullOrEmpty(Collection<?> values) { ... }
@Contract('null -> false') static boolean isNotNullOrEmpty(Collection<?> values) { ... }
```

不涉及任何构建配置，编译后的依赖里也有效。极性必须与方法实际行为一致：写反会把事实放进
错误的分支，被守卫的代码**仍然会被报出**。

### Bean Validation 约束

`@NotNull`、`@NotEmpty`、`@NotBlank` 按非空契约处理，因为三者在检查内容之前都先拒绝 null。
它们不要求在源码里初始化字段——由 ORM 或 mapper 填充的 bean，只要校验运行时非空就有效。
要表达"可能缺失"请用 `@Nullable`（而不是"不写 `@NotNull`"）；`@Size`、`@Pattern`、
`@Email` 等接受 null，不表达非空。

### 框架管理的字段

`@NullMarked` 下每个字段都非空，严格模式会追问它在哪赋值——而 Spring、ORM、mapper 注入的
字段永远不会在源码里赋值。对这类模块关掉这一条规则：

```groovy
nullcheck {
    initializationCheck = false
}
```

### 一个可行的推进顺序

1. 给一个叶子包加 `@NullMarked`（`package-info.java`），用 `strict = true` 跑检查。
2. 把可能为空的返回值标 `@Nullable`，再修调用方。
3. 标"可以不传"的参数，再标可能缺失的字段。
4. 集合可能含 null 时，把注解写在类型实参上，而不是写在集合上。
5. 把项目自己的守卫声明一次（`@Contract` 或守卫清单），它们就不再是发现。
6. 最后才对那个模块打开 `failOnError`，然后处理下一个包。

---

## 检查内容

| 场景 | 示例 |
| --- | --- |
| `@NonNull` 方法返回 null | `@NonNull String f() { null }` |
| 返回 `@Nullable` 值 | `@NonNull String f() { maybe() }` |
| 把 null 赋给非空字段或局部变量 | `@NonNull String s = null` |
| 非空值之后又赋 null | `cached = 'x'; cached = null` |
| 解引用 `@Nullable` 值 | `@Nullable String s; s.length()` |
| 解引用返回 `@Nullable` 的调用 | `maybe().length()` |
| 解引用安全导航的结果 | `a?.b.c`——应写成 `a?.b?.c` |
| 解引用可空的**元素** | `List<@Nullable String> xs; xs[0].length()`、`xs.get(0)`、`xs.first()` |
| 解引用迭代产生的可空元素 | `for (String s : xs) s.length()`、`xs.each { it.length() }` |
| 把 null 传给 `@NonNull` 参数 | `take(null)` |
| 把 `@Nullable` 值传给 `@NonNull` 参数 | `take(maybe())` |
| **严格模式：** 流敏感的解引用 | `String s = null; s.length()` |
| **严格模式：** 未初始化的 `@NonNull` 字段 | 声明了但从未赋值 |

### 能识别的注解

按**简单名**匹配，包名无关：

| 含义 | 名称 |
| --- | --- |
| 可空 | `Nullable`、`CheckForNull`、`MonotonicNonNull` |
| 非空 | `NonNull`、`NotNull`、`Nonnull`、`NotEmpty`、`NotBlank` |
| 默认非空（全部） | `NullMarked`、`NonNullByDefault`、`NonnullByDefault` |
| 默认非空**参数** | `ParametersAreNonnullByDefault`、`ParametersAreNonNullByDefault`、`NonNullApi` |
| 默认非空**返回值** | `ReturnValuesAreNonnullByDefault`、`NonNullApi` |
| 默认非空**字段** | `NonNullFields` |
| 退出默认 | `NullUnmarked` |

> 保留策略：写在源码里的注解始终可见。编译产物依赖里的声明注解从 class 文件读取，因此
> `CLASS` 保留也可以——前提是注解类本身在编译 classpath 上。编译好的
> `package-info.class` 通过反射读取，必须是 `RUNTIME`。编译产物里的 type-use 注解建议用
> `RUNTIME`，常见的空值注解库都是这样。

**Bean Validation 的 `@NotNull`、`@NotEmpty`、`@NotBlank`**
（`jakarta.validation.constraints`、`javax.validation...`、
`org.hibernate.validator.constraints`）按非空契约处理：赋 null、传 null、返回 null 都会报出。
`@NotEmpty` 和 `@NotBlank` 先拒绝 null 再检查内容，因此蕴含非空；其余约束
（`@Size`、`@Pattern`、`@Email` 等）接受 null，被忽略。它们都不被当作"源码里必须初始化"的
要求，因为由 ORM 或反序列化填充的 bean 只要校验时非空就有效——所以严格模式不会把每个映射
字段都报一遍。在 `@NullMarked` 类里初始化要求依然成立：标记是更强的契约。

### 容器元素

注解可以写在类型实参上，此时描述的是元素而不是容器：

```groovy
List<@Nullable String> names
```

每一种取元素的方式都得到可空值，因此不判断都会报出：

```groovy
names[0].length()              // 下标
names.get(0).length()          // get()
names.first().length()         // DGM 访问器
for (String name : names) {    // for-each 变量
    name.length()
}
names.each { it.length() }     // 闭包参数和隐式 it
```

空值判断会像其他可空值一样收窄元素（`if (name != null) …`），安全导航避免解引用；
不带注解的 `List<String>` 不报任何东西。

Map 的 key 和 value 分开跟踪：

```groovy
Map<String, @Nullable String> byCode      // value 可能为空，key 不会
Map<@Nullable String, String> byName      // 这里反过来
```

```groovy
byCode.get(code).length()                 // value
byCode[code].length()                     // value
for (String v : byCode.values()) v.length()          // value
for (String k : byName.keySet()) k.length()          // key
for (Map.Entry<String, String> e : byCode) e.value.length()   // entry 的一半
byCode.each { k, v -> v.length() }        // 双参数闭包
byCode.collect { it.value.length() }      // 闭包里的 entry
```

有两种形态不跟踪：未指定泛型的 `Map.Entry` 变量，以及 `entrySet()` 迭代——它们在检查器
看到之前就丢失了 key/value 类型。这两种情况下直接读 map 仍然有效，也不会因此误报。

### 可能查不到的查找

容器的元素类型描述的是**存进去的值**能不能为 null。有些操作即使元素非空也会返回 null，
因为查找本身可能落空：

| 调用 | 结果 | 原因 |
| --- | --- | --- |
| `map.get(k)`、`map[k]` | 可空 | 键可能不存在 |
| `map.remove(k)`、`map.put(k, v)` | 可空 | 返回的是旧值 |
| `map.getOrDefault(k, d)` | `d` 非空时非空 | 有兜底 |
| `list.find { }`、`findResult { }` | 可空 | 没找到 |
| `list.max()`、`min()` | 可空 | 集合可能为空 |
| `list.first()`、`last()` | 非空 | 空集合时直接抛异常 |

所以 `Map<String, String>` 并不能让 `get` 变安全：值非空，但键可能不在。要么判断查找结果，
要么用不会落空的写法：

```groovy
map.getOrDefault(code, '').length()
if (map.containsKey(code)) {
    map.get(code).length()      // 已收窄：containsKey 证明键存在
}
map.get(code)?.length()
```

`containsKey` 只在值非空时才收窄；`Map<String, @Nullable String>` 下键存在也可能是显式
null。

迁移期间可以关掉这族规则：

```groovy
nullcheck {
    containerLookups = false
}
```

CLI：`--no-container-lookups`。

### 契约（Contracts）

上面这些机制在内部是同一个模型：一次调用可以收窄参数、让结果可空，或者根本不返回。
三个来源按下面的优先级提供契约：

1. **配置**——插件的 `contracts`，或 CLI 的 `--contract`；
2. **注解**——JetBrains `@Contract`、`@EnsuresNonNullIf`、`@EnsuresNonNull`、`@NoReturn`；
3. **内置表**——`Map.get`、`getOrDefault`、`find`、`max`、`Objects.requireNonNull` 等。

配置优先，是为了让库里写错或缺失的契约能在不改库的前提下纠正：

```groovy
nullcheck {
    contracts = [
        // 本项目能保证这个键一定存在
        'java.util.Map#get'         : '-> !null',
        // 库自己的注解写反了
        'com.acme.Utils#isNotEmpty' : 'null -> false',
    ]
}
```

CLI：`--contract "java.util.Map#get=-> !null"`。

语法沿用 JetBrains：

| 契约 | 含义 |
| --- | --- |
| `null -> fail` | 传 null 时抛出；调用返回后该参数非空 |
| `-> fail` | 永不返回 |
| `null -> true` / `null -> false` | 返回值揭示参数是否为 null |
| `_, !null -> !null` | 当且仅当该参数可空时结果可空 |
| `-> !null` / `-> null` | 结果恒非空 / 恒为 null |

条目按 `declaringClass#method` 匹配，也会沿父类和接口查找，所以 `java.util.Map#get` 同样
覆盖 `HashMap.get`。无法解释的契约会被忽略，而不是猜测。

有两样东西**刻意不算契约**：`containsKey` 对后续查找的收窄是两个调用之间的事实；元素类型
（`List<@Nullable E>`）是类型，不是行为。

### 接受的守卫写法

以下写法**不会**产生发现：

`if (x != null)` · `if (x == null) else` · 提前 `return` / `throw` · `?.` · `if (x)` 和
`if (!x)` · `&&` / `||` 短路 · `instanceof` · `Objects.nonNull(x)` / `Objects.isNull(x)` ·
`assert x` / `assert x != null` · elvis `?:` · 三元 · `while (x != null)` ·
`Objects.requireNonNull(x)` / `checkNotNull(x)` · `assertNotNull(x)`（含消息参数）·
`assertThat(x).isNotNull()`

校验/断言方法同样按简单名匹配，因此 JUnit、TestNG、AssertJ、Truth、Guava 都无需配置。

项目自己的 helper **不会被猜测**——需要声明，并说明哪种结果代表"非空"：

```groovy
nullcheck {
    // 返回 true  → 第一个参数非空（isNotEmpty、hasValue）
    guardNotNullWhenTrue  = ['com.acme.Utils#isNotEmpty']
    // 返回 false → 第一个参数非空（isNullOrEmpty、isBlank）
    guardNotNullWhenFalse = ['com.acme.MyCollectionUtils#isNullOrEmpty']
}
```

如果项目已经为 IntelliJ 标注了 helper，JetBrains 的 `@Contract` 表达同样的信息，也能被识别：

```groovy
@Contract('null -> fail')      // 传 null 抛异常，因此调用后参数非空
static <T> T require(T value) { ... }

@Contract('null -> false')     // 传 null 返回 false，因此返回 true 说明非空
static boolean isNotEmpty(Collection<?> values) { ... }

@Contract('null -> true')      // isEmpty 形态：返回 false 说明非空
static boolean isNullOrEmpty(Collection<?> values) { ... }
```

只解释这两种形态。`!null`、`_`、`param1`、`new` 以及语法里的其余部分不做猜测，直接忽略。

真实注解是 `CLASS` 保留的，这没问题：注解从 class 文件读取，所以编译进依赖的契约也有效，
包括同一构建里另一个模块中的契约。注解类本身必须在编译 classpath 上——通常本来就满足，
因为项目要编译这个注解就离不开它。没有它，注解会被静默丢弃，契约不生效。

永不返回的 helper 也是一种守卫。声明一次，调用之后的语句就不可达，方法末尾的兜底
`return null` 不再被报出：

```groovy
@NoReturn                     // 或 @Contract('-> fail')，或 neverReturns 清单
static void giveUp() { throw new IllegalStateException('give up') }

static Object execute() {
    try {
        return doSomething()
    } catch (Exception e) {
        giveUp()
    }
    return null               // 不报：giveUp 永不返回
}
```

它同样能收窄：`if (x == null) giveUp()` 之后 `x` 非空，效果与 `throw` 相同。`@NoReturn` 按
简单名匹配；`@Contract('-> fail')` 表示"任意调用都抛出"——空参数列表是合法的 contract
语法，`'_, _ -> fail'` 则按参数个数表达同一件事。只写 `'null -> fail'` 不够，因为其他参数
下调用仍会返回。

方法属于无法改注解的库时，用配置声明：

```groovy
nullcheck {
    neverReturns = ['com.acme.Panic#notExpectedResult']
}
```

CLI：`--never-returns com.acme.Panic#notExpectedResult`，可重复。

helper 也可以声明自己的契约，当方法属于本项目或你可控的库时，这比集中清单更好：

```groovy
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf
import org.checkerframework.checker.nullness.qual.EnsuresNonNull

// 结果为 false 时非空；isNotEmpty 形态用 result = true
@EnsuresNonNullIf(expression = '#1', result = false)
static boolean isNullOrEmpty(Collection<?> values) { ... }

// 正常返回后非空，等价于 requireNonNull 的注解形式
@EnsuresNonNull(expression = '#1')
static <T> T require(T value) { ... }
```

按简单名匹配，因此注解可以来自 Checker Framework、其他库，或由项目自己声明。`#1`、`#2` …
表示参数；检查器无法解释的引用（字段路径、越界下标）直接忽略，不做猜测。`result` 写反会
把事实放进错误的分支，因此**不会**让本想覆盖的代码静默——这是刻意的安全属性，不是巧合。

CLI：`--guard-when-true` / `--guard-when-false`，每个 flag 一个条目。裸检查器：
`-Dgroovy.nullchecker.guards.true=...` / `.false`。条目按 `declaringClass#method` 精确匹配，
只考虑第一个参数。极性是声明出来的，不是推断的：读反会掩盖真实问题。

常见库的 helper 也需要声明——不做猜测，因为一个代码库里的 `isBlank` 不总是另一个代码库
里的含义：

```groovy
guardNotNullWhenFalse = [
    'com.acme.MyCollectionUtils#isNullOrEmpty',
    'org.apache.commons.lang3.StringUtils#isBlank',
]
guardNotNullWhenTrue = [
    'com.acme.MyCollectionUtils#isNotNullOrEmpty',
    'org.apache.commons.lang3.StringUtils#isNotBlank',
    'org.springframework.util.StringUtils#hasText',
]
```

每个项目收集一次这份清单，就是全部的调优成本：在一个真实的 251 文件模块上，声明完自己的
helper 后剩下的发现全部来自严格模式——安全导航的结果没判断就解引用。

---

## 包级默认

给包标记一次，包内所有类都默认非空，不必逐个类标注：

```java
// src/main/java/com/example/order/package-info.java
@org.jspecify.annotations.NullMarked
package com.example.order;
```

```groovy
package com.example.order

class OrderService {
    String findName() {
        return null        // ← 报出：包级默认说这里应该非空
    }
}
```

`package-info.java` 和 `package-info.groovy` 都可用，依赖里编译好的 `package-info.class`
也会被读取。作用域是所标注的包本身：子包**不**覆盖，必须各自声明。

> 这一点超出了上游——Groovy 6 的 `NullChecker` 完全不读包注解。见
> [PORT.md §4.2](PORT.md)。

### 分作用域的默认

JSR-305 和 Spring 的注解一次只标一个作用域，这通常正是遗留代码库最需要的：参数是最便宜
的非空化对象，返回值需要配套 `@Nullable`，字段最吵（容器赋值的字段永远没有构造器赋值）。

| 注解 | 参数 | 返回值 | 字段 |
| --- | --- | --- | --- |
| `@ParametersAreNonnullByDefault`（JSR-305） | ✅ | — | — |
| `@ReturnValuesAreNonnullByDefault`（JSR-305） | — | ✅ | — |
| `@NonNullApi`（Spring） | ✅ | ✅ | — |
| `@NonNullFields`（Spring） | — | — | ✅ |
| `@NullMarked`（JSpecify） | ✅ | ✅ | ✅ |

在一个真实服务包上实测（71 个文件，`--strict`）：不加注解 1 条发现，
`@ParametersAreNonnullByDefault` 26 条，`@NonNullApi` 71 条，`@NullMarked` 195 条——
差异完全来自上表的作用域，`@NullMarked` 多出的 124 条是注入字段的初始化报告。

类上的 `@NullUnmarked` 会让该类退出包声明的默认，覆盖所有作用域。

---

## 严格模式

在完全没有注解的代码里，也能跟踪通过赋值变成 null 的值：

```groovy
String s = null
s.length()        // 关闭严格模式：不报。开启：'s' 可能为 null
```

它报得更多，因此应当作第二步，而不是起点。

Groovy 4 **无法**把它写成带参数的扩展名——Groovy 6 的写法
`'NullChecker(strict: true)'` 会失败，并给出误导性的 "not found on the classpath"。
请用下面之一：

```groovy
NullChecker.setOptions([strict: true])           // 代码方式
```
```bash
-Dgroovy.nullchecker.strict=true                 // JVM 属性
GROOVY_NULLCHECKER_STRICT=true ./gradlew build   # 环境变量，适合 CI
```

用 Gradle 插件时就是 `nullcheck { strict = true }`。

---

## 什么会让运行失败

发现是分类的，因为检查器与 Groovy 静态类型检查器共用一个错误收集器：

| 类别 | 含义 | 默认失败 |
| --- | --- | --- |
| `null` | 上面所有空值规则 | 是 |
| `initialization` | 显式非空字段没有任何构造器赋值（严格模式） | 是 |
| `resolution` | 静态检查器无法解析某段运行时动态分派的代码 | **否** |

`resolution` 发现不是空值结论——这类代码能编译能运行——但它说明分析不完整，所以仍然打印、
仍然计数。用 `failOn`（插件）/ `--fail-on`（CLI）配置：默认 `null,initialization`；`all`
让所有类别都失败，与早期版本一致。

**选择跑哪些检查**是另一个开关，每条规则族一个布尔值：

```groovy
nullcheck {
    nullnessCheck       = true    // 默认
    initializationCheck = false   // 不再报告未初始化字段
}
```

这与 `failOn` 不同：停用一个族会把它从报告里移除，适合项目已决定暂时无法满足初始化要求
（例如字段由容器赋值），而不是教所有人忽略满屏输出。两者默认都开；两个都关会被拒绝，
因为"什么都没检查"的运行不能看起来像干净结果。CLI 的写法是 `--checks null`。多模块项目
可以按模块设置。

配合包级 `@NullMarked` 时差异很大：在有注入字段的服务包上，`initializationCheck = false`
把 195 条发现变成 71 条——那 124 条初始化报告是容器赋值的注入字段，剩下的才是真正值得处理
的信号：没有 `@Nullable` 却返回 null 的方法，以及把 null 传给非空参数。

```
findings   = 3 (null=2, initialization=0, resolution=1)
fail-on    = null,initialization
note       = 1 finding(s) outside the failing categories (reported, not failing)
```

---

## 为什么 `0` 可信

空值检查器最危险的失败是沉默：一个从未跑起来的检查会报 `0 findings`，与干净代码无法区分。
这里的每个入口都对此做了防护。

**它先验证自己。** 在报告任何结果之前，CLI 会编译一个内置的、故意写坏的样本——其中包含
一个 `@CompileStatic` 类——只有该样本被捕获时才继续报告：

```
canary     = ok (2 findings on the built-in sample, including its @CompileStatic class)
findings   = 0
coverage   = files=43 classes=50 compileStatic=46 nonNullByDefault=50 methodsWithReturnType=226
```

`coverage` 让下面的数字可解释：它显示实际检查了多少内容。`compileStatic=46` 是关键数字——
静默配置错误最先跳过的就是这种类。

**退出码有含义：**

| 码 | 含义 |
| --- | --- |
| `0` | 没有发现 |
| `1` | 有发现 |
| `2` | **检查器没有运行**——配置错误、工具无法解析、自检失败 |

退出码 `2` 永远不会被当作成功，否则"什么都没检查"就会被当成健康证明。

---

## Gradle 插件

一个 `nullcheck` 任务：不改源码、不写编译器配置脚本、不向运行时 classpath 添加任何东西。

```groovy
plugins {
    id 'groovy'
    id 'io.github.heyuch.groovy-nullchecker' version '0.11.0'
}

nullcheck {
    failOnError = true                        // 默认 false：只报告
    strict      = true                        // 可选
    failOn      = ['null', 'initialization']   // 默认；'all' 让 resolution 也失败
    initializationCheck = true                 // 默认；false 去掉未初始化字段报告
    markedOnly  = false                        // true：只检查 @NullMarked 类/包
    targets     = ['src/main/groovy', 'src/main/java']
}
```

完整 DSL 与行为：[nullchecker-gradle-plugin/README.md](nullchecker-gradle-plugin/README.md)。

---

## 从 Groovy 6 迁移

| 项目 | 差异 |
| --- | --- |
| 类名 | `io.github.heyuch.groovy.nullchecker.NullChecker`，不是 `groovy.typecheckers.NullChecker` |
| `strict` | 带外配置，不是扩展参数 |
| 注解 | `RUNTIME` 始终可用；编译好的 `package-info.class` 必须是 `RUNTIME` |
| 局部声明 | 本移植会报"局部声明的 `@NonNull` 用 null 初始化"；Groovy 6 不报 |
| 展开结果 | 这里 `xs*.prop` 永不为 null；Groovy 6 会报后续调用 |
| 循环退出 | 这里 `continue` 和 `break` 与 `return` 一样收窄；Groovy 6 只认 `return`/`throw` |
| 校验约束 | Bean Validation 的 `@NotNull` 保留契约但不要求初始化；Groovy 6 要求 |
| 类型推断 | 依赖推断空值的检查可能比 Groovy 6 报得少 |
| 包注解 | 这里支持，上游不支持 |

完整清单（含原因与限制）在 **[PORT.md](PORT.md)**。

---

## 项目结构

根目录本身没有源码，下面两个平级子项目：

```
nullchecker/                 检查器本体
├── src/main/groovy/io/github/heyuch/groovy/nullchecker/
│   ├── NullChecker.groovy           检查器（从 Apache Groovy 回移）
│   ├── CheckingVisitor.groovy       基础 visitor
│   ├── NullCheckCustomizer.groovy   注册逻辑，同时覆盖 @CompileStatic 类
│   └── harness/
│       ├── NullCheckHarness.groovy  编译器调用的唯一入口
│       ├── NullCheckCli.groovy      命令行入口
│       ├── NullCheckCanary.groovy   内置自检样本
│       ├── CorpusGate.groovy        测试使用的语料门禁
│       └── CorpusRules.groovy       覆盖率门禁背后的规则清单
├── corpus/                  配对样本：每条规则必须报出，每种守卫必须静默
└── tools/                   CLI 包装脚本与对比探针

nullchecker-gradle-plugin/   Gradle 插件
examples/                    可运行的消费方示例（插件与 CLI）
docs/                        发布说明
```

插件不依赖 `:nullchecker`。它把检查器的类名作为字符串常量传入，让 detached configuration
在执行时按坐标解析 artifact，因此两个模块可以独立编译。

---

## 构建与测试

```bash
./gradlew check                # 语料门禁：每条规则被覆盖，每种守卫静默
./gradlew dualRun              # 信息性对照：在本移植和 Groovy 6 下各跑一遍语料并 diff
./gradlew publishToMavenLocal  # 把两个模块安装到 ~/.m2
```

`./gradlew check` 是这个项目的核心，不是形式：每条规则都需要一个**必须**被报出的样本，
每种守卫都需要一个必须静默的样本，没有样本覆盖的规则会让构建失败——所以规则不可能在没有
证据证明它会触发的情况下被加入。

插件的功能测试通过 TestKit 驱动真实 Gradle 构建，因此是同一道门禁里较慢的一半：

```bash
./gradlew :nullchecker-gradle-plugin:functionalTest
```

发布流程见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。

---

## 许可证

从 Apache Groovy 回移，遵循 Apache License 2.0。回移的源文件保留原始许可证头，并带有上游
指纹；见 [PORT.md](PORT.md)。
