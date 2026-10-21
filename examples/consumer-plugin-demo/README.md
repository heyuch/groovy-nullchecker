# 用 Gradle 插件接入（示例）

这是一个**可直接复制**的示例，只改了**两处**就接上了：`plugins {}` 加一行，
`nullcheck { }`（可选）配几项。

## 一次性准备

插件与检查器都还没发到公开仓库，所以先装进本地 Maven 仓库（两者同属一个构建，一条命令装齐）：

```bash
# 在 groovy-nullchecker 仓库里
./gradlew publishToMavenLocal
```

## 你的项目里改什么

`settings.gradle`：**`pluginManagement` 必须放在文件最前面**，否则 Gradle 报
"The pluginManagement {} block must appear before any other statements"。

```groovy
pluginManagement {
    repositories {
        mavenLocal()          // 插件 marker 在这里，直到发到 Plugin Portal
        gradlePluginPortal()
    }
}
```

`build.gradle`：

```groovy
plugins {
    id 'groovy'
    id 'io.github.heyuch.groovy-nullchecker' version '0.6.0'   // ← 版本号必填
}

repositories {
    // 检查器是普通依赖，由插件的任务解析，所以仓库要声明在【本项目】上。
    // 一旦项目自己声明了 repositories，settings 里的就会被忽略。
    mavenLocal()
    mavenCentral()
}

nullcheck {
    // 默认只报告（findings 让任务 exit 1，但不让构建失败）
    // 治理完存量问题后再打开：
    //   failOnError = true
}
```

> ⚠️ `plugins {}` 里**必须写版本号**。从插件仓库（mavenLocal）解析而不带版本时，
> Gradle 报的是 "plugin dependency must include a version number" —— 那句话很容易
> 被读成"插件找不到"，实际原因是**缺版本号**。

## 用

```bash
./gradlew nullcheck
```

`strict`、`failOnError`、`targets` 等都在 `nullcheck { }` DSL 里配，不走 `-P` 属性。

输出（本示例实测）：

```
canary     = ok (2 findings on the built-in sample, including its @CompileStatic class)
FINDING .../Violation.groovy:16: [Static type checking] - Potential null dereference: 'value' is @Nullable
findings   = 1
coverage   = files=2 classes=2 compileStatic=2 nonNullByDefault=0 methodsWithReturnType=2
nullcheck: findings reported (exit 1)
```

`canary` 证明检查器**真的在跑**（内置样本里含一个 `@CompileStatic` 类），
`coverage` 说明**实际看了什么**。有这两样，`findings = 0` 才是可信的 0。

## 三个设计上的注意点

1. **`nullcheck` 不依赖 `compileGroovy`**（只依赖 `compileJava`）。
   所以你项目**编译不过的时候也能出报告** —— 而这恰恰是最需要报告的时候。
   （Java 那一步是为了编译注解类。）
2. **退出码 2 会让构建失败**，即使 `failOnError` 是关的。
   `2` 表示检查器**根本没跑起来**（自检失败／工具解析不到／扩展名错），
   把它当成"通过"就等于把"没查"当成"干净"。
3. **检查器不进你的运行时 classpath**：插件是 `compileOnly` 依赖它，
   任务在独立进程里跑 CLI，只是把 `sourceSets.main.compileClasspath` 传给它解析注解。
