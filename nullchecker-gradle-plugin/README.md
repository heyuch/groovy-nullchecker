# Gradle 插件

一行接入：`nullcheck` 任务在**不改源码、不写 configuration script** 的前提下，
对整个项目做一次编译期空值检查。

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
    id 'io.github.heyuch.groovy-nullchecker' version '0.2.0'   // 版本号必填
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
    failOnError = true            // 有"失败类"发现就让构建失败（默认 false，只报告）
    failOn      = ['null', 'initialization']   // 哪些分类算失败；'all' 连解析错误一起失败
    strict      = true            // 流敏感：也分析未注解代码
    targets     = ['src/main/groovy', 'src/main/java']
    reportFile  = 'build/reports/nullcheck/nullcheck.txt'
    canary      = true            // 关掉自检（不建议）
    toolVersion = '0.2.0'

    // 项目自己的守卫方法：声明极性，而不是猜名字
    guardNotNullWhenTrue  = ['com.acme.Utils#isNotEmpty']        // 返回 true  ⇒ 参数 0 非空
    guardNotNullWhenFalse = ['com.acme.MyCollectionUtils#isNullOrEmpty']  // 返回 false ⇒ 参数 0 非空
}
```

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `failOnError` | `false` | 有"失败类"发现时是否让构建失败。**建议先保持 false 摸底** |
| `failOn` | `['null', 'initialization']` | 哪些分类算失败。`resolution`（静态检查解析不了的动态代码）默认只报告不失败；`['all']` 恢复"任何发现都失败" |
| `guardNotNullWhenTrue` | `[]` | 返回 true 即保证参数 0 非空的方法，`类全名#方法名` |
| `guardNotNullWhenFalse` | `[]` | 返回 false 即保证参数 0 非空的方法（如 `isNullOrEmpty`） |
| `strict` | `false` | 打开后报告量会明显上升 |
| `targets` | `['src/main/groovy', 'src/main/java']` | 两个都要，否则 `package-info.java` 里的包级默认看不见。**不存在的根只会警告并跳过**（纯 Groovy 项目没有 `src/main/java`）；但**如果所有根都不存在**则报错退出，避免把根写错变成"扫了 0 个文件" |
| `reportFile` | `build/reports/nullcheck/nullcheck.txt` | 原始报告落盘位置 |
| `canary` | `true` | 关闭后**不再自证**，`0` 就无法区分"干净"和"没查" |
| `toolVersion` | `0.2.0` | 解析不到的版本会**明确失败**，不会静默变绿 |

---

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

**2. 任务用 CLI，而不是把自己塞进编译流程。**
塞进 `compileGroovy`（configuration script 那条路）会带来两个问题：一是每次编译都跑一遍，
二是**错误直接让编译失败**，没有 baseline 的余地；而且 configuration script 跑在**隔离的类加载器**里，
连检查器的类都 import 不到（实测：`import` 报 "unable to resolve class"，改 `Class.forName`
仍 `ClassNotFoundException`，把依赖从 `compileOnly` 换成 `implementation` 也不行）。
独立任务则是你要接进 `check` 时一行的事，不想接就永远只是"看一眼"。

**3. 不存在的根只警告，全都不存在才报错。**
默认 `targets` 两个根都给了，但纯 Groovy 项目没有 `src/main/java`。若把"根不存在"当错误，
这些项目根本跑不起来；若静默忽略，把根名写错就会悄悄缩小分析范围。所以：**部分缺失 → 警告并继续；
全部缺失 → 明确失败**（退出码 2）。两种都在功能测试里钉住了。

**4. 检查器不进你的运行时 classpath。**
插件是 `compileOnly` 依赖它；任务把 `sourceSets.main.compileClasspath` 传给 CLI 用于解析注解，
仅此而已。检查器不会出现在你的 `runtimeClasspath`，也不会进你发布的 POM。

---

## 已验证

`./gradlew :nullchecker-gradle-plugin:functionalTest` —— 8 个用例，每个都**真跑一次 Gradle 构建**（TestKit）：

| 用例 | 断言 |
| --- | --- |
| `@CompileStatic` 违规 | 报出，且默认不阻断构建；输出含 `canary` 与 `coverage` |
| `failOnError = true` | 构建失败 |
| `@CompileStatic` 且守卫正确 | **不报**，且 `compileStatic=1`（证明真看了，不是跳过）|
| 包级 `@NullMarked` | 类上无注解也被覆盖 ⇒ `package-info` 确实被读到 |
| **项目编译不过** | `nullcheck` 仍出报告（`compileGroovy` 未被执行）|
| `toolVersion` 不存在 | 构建失败并给出该版本号，**不会静默变绿** |

不写单元测试是刻意的：这个插件的全部职责就是组装出一个会产生特定退出码的任务，
而那类"看起来正常、其实什么都没查"的失效**只有在真实构建里才观察得到**。
