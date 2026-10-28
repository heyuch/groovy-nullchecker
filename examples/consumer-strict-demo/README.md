# 在本地项目里试用 nullchecker

这是一个**可直接复制**的示例消费者工程，演示三种接入方式。

在发布到 Maven Central 之前，用 `publishToMavenLocal` 把检查器装进本地 Maven 仓库
（等价于 `mvn install`），其他本地项目照常用**普通依赖坐标**引用即可 ——
Gradle 侧只需要把仓库加一个 `mavenLocal()`，`build.gradle` 里不需要任何特殊写法。

---

## 0. 一次性：把检查器装进本地仓库

在 `nullchecker` 工程里：

```bash
./gradlew publishToMavenLocal
```

产物落在：

```
~/.m2/repository/io/github/heyuch/groovy/nullchecker/0.10.0/
├── nullchecker-0.10.0.jar      # 检查器 + CLI 本体
├── nullchecker-0.10.0.pom      # 无任何依赖声明
└── nullchecker-0.10.0.module   # Gradle metadata
```

> ⚠️ **同版本号重发后必须刷新依赖缓存。**
> 目前版本固定为 `0.10.0`；Gradle 会缓存 `mavenLocal()` 解析到的模块元数据，
> 重新 `publishToMavenLocal` 之后消费者工程可能仍用旧 jar：
>
> ```bash
> cd <你的项目> && ./gradlew --refresh-dependencies <你的编译任务>
> ```

---

## 1. 你的项目需要改两处

### `settings.gradle`：加 `mavenLocal()`

Gradle **不会**默认把 `mavenLocal()` 放进仓库列表。不加的话会直接报
`Could not find io.github.heyuch:groovy-nullchecker:0.10.0`：

```groovy
dependencyResolutionManagement {
    repositories {
        mavenLocal()      // ← 让本地仓库里的 nullchecker 可被解析
        mavenCentral()    // ← Groovy 本身还是从中央仓库来
    }
}
```

（老式写法在 `build.gradle` 的 `repositories {}` 里加 `mavenLocal()` 同样有效。）

### `build.gradle`：加依赖

```groovy
dependencies {
    implementation 'org.apache.groovy:groovy:4.0.30'   // 你的 Groovy 版本

    compileOnly 'io.github.heyuch:groovy-nullchecker:0.10.0'
}
```

> **不要**再加 `groovy-typecheckers`。本库就是它在 Groovy 4 上的替代品，
> 两者同时上会让 classpath 里出现两份 `org.apache.groovy.typecheckers.*`。

---

## 2. 四种接入方式，按侵入程度递增

### 方式 A：只检查标了注解的类（最简单，**建议先用这个试水**）

源码里加一个注解，**不改任何构建配置**：

```groovy
import groovy.transform.TypeChecked

@TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')
class MyService {
    // ...
}
```

想试哪些文件就加哪些 —— 对现有老项目这是唯一不会一次性炸出几千条报告的姿势。

### 方式 B：整个编译单元全局检查（不改源码，但影响面大）

用一个 configuration script 全局注入，见 [`config/nullcheck.groovy`](config/nullcheck.groovy)：

```groovy
tasks.withType(GroovyCompile).configureEach {
    groovyOptions.configurationScript = file('config/nullcheck.groovy')
}
```

> ⚠️ **两个都会让人拿到「假绿」的坑，这里脚本已经处理好：**
>
> 1. `extensions` 必须传 **String**。传 `Class` / `ClassExpression` 会被**静默忽略**，
>    构建照样绿、检查一条不跑。
> 2. **裸的 `ASTTransformationCustomizer` 到不了 `@CompileStatic` 类** —— 它们被静默跳过。
>    而真实项目几乎全是 `@CompileStatic`，于是整个项目报 `0 findings`，
>    那个 0 的意思是「**什么都没查**」。所以脚本里内联了一个把注册声明在
>    `CANONICALIZATION` 相的包装器。
>
> 包装器**为什么必须内联**（而不是 import 本库提供的 `NullCheckCustomizer`）：
> 实测 Gradle 编译 configuration script 用的类加载器**不含项目 compile classpath**，
> `import` 会 "unable to resolve class"，改成 `Class.forName` 仍 `ClassNotFoundException`，
> 把依赖从 `compileOnly` 换成 `implementation` 也**解决不了**。自包含是唯一稳妥写法。
>
> **不想写这 20 行样板就用方式 D（CLI）** —— 它对 `@CompileStatic` 一样支持，且零侵入。

### 方式 C：全局 + strict 流敏感模式

`strict` 额外跟踪**未注解**代码里"经赋值变成 null"的变量，以及 `@NonNull` 字段是否被确定初始化。
报告量会明显上升，**不建议一上来就开**。

Groovy 4 **不能**用 `'NullChecker(strict: true)'` 这种带参扩展名（会报一句误导性的
"not found on the classpath"）。可用开关按优先级从高到低：

```groovy
// ① 编程方式（自定义 Gradle 任务 / 编译插件）
io.github.heyuch.groovy.nullchecker.NullChecker.setOptions([strict: true])

// ② 系统属性 —— 注意传给【编译所在的 JVM】
tasks.withType(GroovyCompile).configureEach {
    // GroovyCompile 上【没有】systemProperty 方法，写它会直接报 "No signature of method"
    options.forkOptions.jvmArgs = (options.forkOptions.jvmArgs ?: []) +
            ['-Dgroovy.nullchecker.strict=true']
}

// ③ 环境变量 —— 最适合 CI 或临时试跑，构建文件一个字都不用改
GROOVY_NULLCHECKER_STRICT=true ./gradlew compileGroovy
```

报告模式的那个任务也认 `-Pnullcheck.strict=true`（见 §5）。

### 方式 D：CLI（**推荐当主力入口**）

CLI 对所有构建系统都一样、零侵入（不改源码、不写 configuration script），
**并且完整支持 `@CompileStatic` 与包级注解**，是目前功能最全的路径。

```groovy
configurations { nullcheckTool }

dependencies {
    nullcheckTool 'io.github.heyuch:groovy-nullchecker:0.10.0'
    // 检查器把 Groovy 声明为 compileOnly（不污染你的运行时依赖），
    // 所以"运行检查器"的那份 classpath 要自己给出 Groovy
    nullcheckTool 'org.apache.groovy:groovy:4.0.30'
}

tasks.register('nullcheck', JavaExec) {
    classpath = configurations.nullcheckTool + files(sourceSets.main.output)
    mainClass = 'io.github.heyuch.groovy.nullchecker.harness.NullCheckCli'
    // src/main/java 也要走一遍，否则 package-info.java 里的包级默认看不见
    args = ['src/main/groovy', 'src/main/java', 'samples/broken']
    ignoreExitValue = true
    doLast {
        int code = executionResult.get().exitValue
        // 2 = 检查器本身没跑起来（自检失败 / 扩展名错）。绝不能当成"通过"。
        if (code == 2) throw new GradleException('nullcheck: the checker is not working')
        if (code == 1 && project.hasProperty('nullcheck.failOnError')) {
            throw new GradleException('nullcheck: findings reported')
        }
    }
}
```

它在报告任何结果**之前**会先跑一个内置的、必然违规的样本（**内含一个 `@CompileStatic` 类**），
并打印覆盖计数：

```
canary     = ok (2 findings on the built-in sample, including its @CompileStatic class)
findings   = 0
coverage   = files=43 classes=50 compileStatic=46 nonNullByDefault=50 methodsWithReturnType=223
```

> 这就是 `findings = 0` 为什么可信：canary 证明它在跑，coverage 证明它看了什么。
> 没有这两样，`0` 无法与"什么都没查"区分 —— 这正是这次接入反馈的核心问题。

---

## 3. 注解从哪来（本库不传递任何注解依赖）

检查器按**简单名**识别注解，来自哪个包都认：`Nullable` / `CheckForNull` / `MonotonicNonNull`
算可空；`NonNull` / `NotNull` / `Nonnull` 算非空。

所以你**不需要为本库引入任何新依赖**，有什么用什么：

| 你已有的依赖 | 可用注解 |
| --- | --- |
| `com.google.code.findbugs:jsr305` | `javax.annotation.Nullable` / `Nonnull` |
| `org.jetbrains:annotations` | `org.jetbrains.annotations.Nullable` / `NotNull` |
| Spring（通常已在 classpath）| `org.springframework.lang.Nullable` / `NonNull` |
| JSpecify | `org.jspecify.annotations.Nullable` / `NonNull` |
| 都没有 | 自己写，见本示例 `src/main/java/demo/` |

> ⚠️ 自己的注解必须 `@Retention(RUNTIME)`。
> 用 `CLASS` 保留时检查器**识别不到**，表现为**静默漏报**（实测）。

---

## 4. 建议的试用顺序

现有老项目一次性全量开启几乎肯定会被淹掉。建议：

1. **先摸底**：装好依赖，用方式 A 挑一个你确实关心的类加注解，确认能报出来。
2. **再用 `nullcheck` 任务看规模**：本示例里的 `nullcheck` 任务会打出一份清单，
   **默认不让构建失败**；确认没有误报之后再 `-Pnullcheck.failOnError=true` 当门禁。
3. **圈定范围**：把 `nullcheck.targets` 收窄到你打算治理的目录。
4. **最后才入门禁**：范围稳定、噪音清零之后，再接进 `check`。

真实代码库上的噪音/漏报比例**目前还没有数据**（见本库 `PORT.md` §6）——
这正是第 2 步要先摸底、而不是直接当门禁的原因。

---

## 5. 本示例怎么跑

```bash
cd examples/consumer-strict-demo

# 注释模式 + 包级默认（示例里是 4 条，其中 1 条来自包默认）
./gradlew nullcheck

# strict 流敏感模式：额外报出未注解代码里的问题
./gradlew nullcheck -Pnullcheck.strict=true

# 当作门禁：有发现就让构建失败
./gradlew nullcheck -Pnullcheck.failOnError=true

# 方式 B 的实证：全局注入 + 编译
./gradlew clean compileGroovy

# 方式 C 的实证：环境变量开 strict（注意 compileGroovy 里没有违规代码，所以是绿的）
GROOVY_NULLCHECKER_STRICT=true ./gradlew clean compileGroovy
```

示例的构成：

| 路径 | 作用 |
| --- | --- |
| `src/main/groovy/demo/CleanExample.groovy` | **阴性对照**：注解与守卫都正确的代码，必须完全安静 |
| `src/main/groovy/demo/marked/` | **包级默认**示例：类上没有注解，默认来自 `package-info.java` |
| `src/main/java/demo/marked/package-info.java` | 一个文件声明整包的默认 |
| `samples/broken/*.groovy` | **阳性对照**：故意写错的代码，`nullcheck` 任务的显式目标 |
| `samples/broken/package-level/` | **包级默认的阳性对照**：违规来自包默认而非类注解 |
| `src/main/groovy/demo/staticmethod/` | **`@CompileStatic`** 类：默认来自全局注入，类上无 `@TypeChecked` |
| `samples/broken/compile-static/` | **`@CompileStatic` 的阳性对照**：这类类曾经被静默跳过 |
| `src/main/java/demo/*.java` | 自带注解，证明不依赖任何注解库 |
| `config/nullcheck.groovy` | 方式 B 的全局注入脚本 |

`samples/broken/` **刻意不放在 `src/main/groovy`**：那样会让 `compileGroovy` 直接失败，
而你通常想先"看看有多少问题"再决定是否当门禁。

`nullcheck` 任务用的是发布产物里自带的 CLI 类
（`io.github.heyuch.groovy.nullchecker.harness.NullCheckCli`），
所以它**不需要**本仓库的源码就能跑起来 —— 你复制这个工程到别处也能用。

> 注意 `nullcheckTool` 这个 configuration 里**显式声明了 Groovy**。
> 因为检查器把 Groovy 声明成 `compileOnly`（不污染你的运行时依赖），
> 所以"运行检查器"的那份 classpath 需要你自己给出 Groovy 版本。
