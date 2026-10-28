# 代码评审待办：架构、类设计与代码质量

- Status: open
- 来源: 2026-10-29 对 `0.11.0` 工作树的整体评审
- 范围: 架构、类设计、代码质量、测试与文档
- 基线: `./gradlew :nullchecker:test` 通过（JDK 18；本机默认 JDK 26 超出 Gradle 8.14.3 支持范围）。插件功能测试未在本次评审中执行。
- 用法: 按优先级处理。每项完成后把 `Status` 改为 `done`，附上提交/PR 链接；行为变化同时更新 `CHANGELOG.md` 和语料。

## 优先级定义

| 级别 | 含义 |
| --- | --- |
| P0 | 可能导致错误结果、静默漏报或构建误判，应优先修复 |
| P1 | 架构或可维护性债，影响后续变更成本 |
| P2 | 健壮性与一致性，适合按模块顺手处理 |
| P3 | 清理、文档与长期项 |

---

## P0：正确性与可信度

### P0-1 修复 `NullCheckSinks` 的陈旧 sink 保护

- Status: open
- 类型: bug
- 证据: `nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/NullCheckSinks.groovy:29-51`
- 问题: `currentSink()` 在第 30 行发现 `CURRENT` 非空就直接返回，第 43 行的 `sink instanceof FileFindingsSink` 检查因此永远不会执行。注释声称要防止 "worker 被另一个 task 复用、findings 文件不同" 时写错文件，但保护实际失效；同一线程/classloader 下第二个 task 仍会写第一个 task 的 findings 文件。`NullCheckSinks.clear()` 在生产路径中也没有调用点。
- 动作:
  - 在返回缓存 sink 之前，比较 `FileFindingsSink.file` 与当前 `groovy.nullchecker.findingsFile`；不同则创建新 sink。
  - 在每个 compilation 结束时清理 ThreadLocal，避免跨 task 残留。
  - 明确 sink 的生命周期归属（customizer 或 harness），不要依赖隐式懒加载。
- 验收:
  - 构造同一线程连续使用两个 findings 文件的测试，断言第二次写入的是第二个文件。
  - 断言 compilation 结束后 `CURRENT` 为空。
- 测试建议: 在 `NullCheckSinkSpec` 增加 worker 复用场景；compile mode 的功能测试增加两个模块共用一个 worker 的用例（若 TestKit 可稳定复现）。

### P0-2 流分析预算耗尽不能静默

- Status: open
- 类型: 可信度
- 证据: `FlowAnalysis.groovy:38`（10000 步上限）；`NullCheckVisitor.groovy:370`、`:810`（循环固定点最多 8 次）
- 问题: 达到上限后 worklist 或循环固定点直接停止，没有诊断。分析可能因此漏报，而结果看起来仍然干净。这与项目 "0 findings 必须可信" 的核心主张冲突。
- 动作:
  - 记录是否因预算耗尽而提前终止，并把它上报为 internal error 或独立的诊断（至少让 harness 拒绝把该结果当作 clean）。
  - 预算是实现细节，建议做成常量集中定义，并在诊断中带上方法名和节点数。
  - 评估上限是否足够：用真实项目数据或合成大方法验证；必要时提高上限或改为按方法规模缩放。
- 验收:
  - 合成一个超过步数上限的方法，断言产生诊断而不是静默通过。
  - 现有语料与性能基线不回归。

### P0-3 裸 checker 的未知 checks 不能静默关闭全部检查

- Status: open
- 类型: fail-open
- 证据: `NullCheckConfig.groovy:72-87`
- 问题: `-Dgroovy.nullchecker.checks=typo` 会得到 `{typo}`，导致 `nullnessCheck=false`、`initializationCheck=false`，两个规则族都不运行。CLI 和 Gradle 插件有前置校验，但直接使用 checker 或第三方构建入口不会经过这些校验。
- 动作:
  - 未知名字应 fail-closed：抛出明确异常，或保留已知族并记录 internal error。
  - 保留 `all`、空值、逗号分隔等现有语义。
  - 在 `NullCheckCli` / `NullCheckFindingsCli` 之外，为直接使用 `NullChecker` 的路径补测试。
- 验收:
  - `checks=typo` 时进程以非零退出或产生 internal error，而不是 `findings = 0`。
  - 现有 `--checks null|initialization|all` 行为不变。

### P0-4 finding 分类改为结构化，而不是消息子串

- Status: open
- 类型: 设计债 / 构建误判
- 证据: `NullCheckCategories.groovy:28-41`；`NullCheckFinding.of` / `NullCheckHarness.categoryOf` 都依赖它
- 问题: `null` / `initialization` / `resolution` 靠消息片段匹配。修改一条消息文案就可能把 nullness finding 降级为 `resolution`，而 `resolution` 默认不失败，构建会从红变绿。这是静默的语义变化，测试不一定能发现。
- 动作:
  - 在 finding 创建点携带 category（例如 `NullCheckReporter.report(category, message, node)`）。
  - `NullCheckCategories.classify` 只保留给 "来自编译器、无法结构化的消息" 兜底。
  - 把现有消息到 category 的映射固化成测试表，新增消息必须显式登记。
- 验收:
  - 修改任意 checker 消息文案，不会改变其 category。
  - `NullCheckCategoriesSpec` 覆盖 checker 当前会产生的全部消息形状。

---

## P1：架构与可维护性

### P1-1 拆分 `NullCheckVisitor`

- Status: open
- 类型: 上帝类
- 证据: `NullCheckVisitor.groovy` 共 1837 行，约 30 个字段；同时负责表达式规则、条件分析、闭包跟踪、集合元素/Map.Entry、流状态投影和不可达性判断。
- 问题: 变更理由过多。改闭包规则需要理解整个 flow 状态机；新增一条 nullness 规则容易与 guard/flow 逻辑纠缠。
- 建议拆分:
  - `ConditionAnalyzer`：`analyzeCondition` / `analyzeLeafCondition` / `GuardFacts` 相关。
  - `ClosureTracker`：`closureHints`、`closureContexts`、`closureNullability`、`closureVariables`。
  - `ElementNullness`：`nullableElements`、`entryVariables`、`Map.Entry` 的 key/value 规则。
  - `FlowStateOwner`：`nullableVars`、`guardedVars`、`pathFacts`、`nonEmptyVars`、`lookupGuards` 及 `captureState` / `restoreState`。
  - visitor 只保留 AST 分派与规则调用。
- 验收:
  - 行为不变：现有语料、单测、功能测试全绿。
  - 每个抽出的类有独立单测；visitor 行数显著下降（目标 < 800 行）。
  - 不引入新的公共 API（保持 `@PackageScope`）。

### P1-2 删除或明确隔离 strict 模式下的死分支

- Status: open
- 类型: 死代码 / 双重实现
- 证据: `NullCheckVisitor.groovy:356-389`（`visitForLoop`）、`:391-399`（`visitDoWhileLoop`）、`:401-428`（`visitSwitch`）、`:743-770`（`visitIfElse`）、`:799-826`（`visitWhileLoop`）
- 问题: strict 模式下 `NullnessFlowTransfer` 已经用 CFG 处理这些语句，语句块不会走 `statement.visit(visitor)`；这些方法里的 `flowSensitive` 分支从调用链看不可达。它们与 CFG 语义重复，未来容易改一处漏一处。
- 动作:
  - 先加注释/断言确认无入口；若确认，删除 strict 分支，只保留 annotation mode 的 lexical 路径。
  - 或者反向重构：让 strict 模式也只走一条路径，避免两套实现并存。
- 验收:
  - 删除后语料全绿，strict 行为不变。
  - 代码中只剩一套控制流语义。

### P1-3 绑定 `AstSupport` 缓存的生命周期

- Status: open
- 类型: 内存泄漏风险
- 证据: `AstSupport.groovy:80-88` 使用强引用 `IdentityHashMap<AnnotatedNode, Set<String>>`；`NullChecker.resetCaches()` 在 `NullChecker.groovy:200-204`；只有 `NullCheckHarness.groovy:299-301` 调用。
- 问题: compile mode 不调用 `resetCaches()`。Gradle worker 常驻且 classloader 复用时，旧 compilation 的 AST 节点会被缓存持续持有。
- 动作:
  - 把缓存清理挂到 session/compilation 生命周期（例如 session 创建时或 compilation 结束时）。
  - 或改为 `WeakHashMap` / `WeakReference`，让 AST 节点可回收。
  - 评估 `SESSIONS`、`PORT_OPTIONS`、`lastCoverage` 的同类问题，给出统一的生命周期约定。
- 验收:
  - 在同一个 classloader 里连续编译多次，断言缓存大小不随编译次数增长。
  - 增加一个重复编译的回归测试。

### P1-4 收敛全局系统属性配置总线

- Status: open
- 类型: 架构 / 并行安全
- 证据: `NullCheckConfig.groovy:55-66`、`ContractRegistry.groovy:306-324/384-397`、`AstSupport.groovy:47-62`、`PackageScopes.groovy:310-324`、`NullChecker.groovy:146-182`；harness 通过 set/restore 隔离（`NullCheckHarness.groovy:216-234/302-325/371-385`）
- 问题: 系统属性是 JVM 全局的。Gradle 并行编译不同模块、不同配置时理论上会互相污染；harness 的 set/restore 也意味着它不能并行运行。Groovy 4 不能给 extension 传参是客观限制，但不应让所有下游都直接读全局属性。
- 动作:
  - 引入 per-compilation 的配置对象，构造一次后显式传给 session/visitor/registry；系统属性只作为边界传输层。
  - 可用 ThreadLocal + `CompilationUnit` metadata 承载，避免 DSL 实例之间共享。
  - 为并行场景补测试（至少两个不同配置的 compilation 并发/交替执行）。
- 验收:
  - 两套不同配置（strict、contracts、checks）交替运行，结果相互独立。
  - 现有 CLI/plugin 参数语义不变。

### P1-5 测试脚手架不要进入发布物

- Status: open
- 类型: 打包 / 依赖面
- 证据: `CorpusGate.groovy`、`CorpusRules.groovy` 位于 `src/main/groovy/.../harness/`，会随 `groovy-nullchecker` jar 发布
- 问题: 它们只服务测试与 `tools/`，却成为消费者可见的 API 和依赖面。
- 动作:
  - 移到 `src/test/groovy`，或建立 `nullchecker-harness-test` 子模块。
  - 如果 `tools/dual-run.groovy` 需要复用，改为从测试 classpath 运行或复制最小逻辑。
- 验收:
  - 发布 jar 中不再包含 `CorpusGate` / `CorpusRules`。
  - 测试与 `dualRun` 仍可运行。

### P1-6 合并三个 finding DTO

- Status: open
- 类型: 重复模型
- 证据: `NullCheckFinding.groovy`、`NullCheckHarness.Finding`、`NullCheckReport.Finding`
- 问题: 字段几乎相同（path/line/category/message），目前靠转换层维持一致；长期容易出现字段漂移或分类不一致。
- 动作:
  - 定义一个核心 record（或不可变类），harness 和 report 作为适配器/视图。
  - 保留 `toTsv` / `fromTsv` / 渲染等序列化职责在独立类中。
- 验收:
  - 只有一个 finding 字段定义源；分类与序列化测试不变。

---

## P2：健壮性与一致性

### P2-1 `PackageScopes` 的静默兜底要可见

- Status: open
- 证据: `PackageScopes.groovy:92-95`（源码 package-info 扫描）、`:277-280`（classpath package-info 读取）、`:322-324`（classpath hint）
- 问题: `catch (Throwable ignored)` 会让 package default 解析失败静默降级为 "没有默认"，属于漏报，与 fail-closed 主张冲突。
- 动作: 保留 best-effort 行为，但把失败记录到 `NullCheckReporter` 的 internal errors（或独立的诊断），让 harness 能拒绝结果。
- 验收: 人为制造 package-info 读取失败，断言产生诊断而不是干净结果。

### P2-2 `NullCheckReporter.guard` 对 `Throwable` 的处理

- Status: open
- 证据: `NullCheckReporter.groovy:43-49`
- 问题: `catch (Throwable)` 会连 `OutOfMemoryError` / `StackOverflowError` 一起吞掉并继续编译。记录后让 compilation 完成，可能留下部分分析结果。
- 动作: 记录后重新抛出致命 VM error（至少 `VirtualMachineError`），或在 harness 层强制终止；普通 `Exception` / `LinkageError` 仍按现有方式降级。
- 验收: 模拟 `StackOverflowError` 时不会被当作普通可恢复失败。

### P2-3 清理 Groovy 4 下不可达的 marker 分支

- Status: open
- 证据: `NullCheckSession.groovy:63-70` 读取 `INFERRED_NON_NULL_RETURN_VIOLATIONS`，注释自述 Groovy 4 没有该 marker
- 动作: 删除，或只在 Groovy 6 兼容路径保留并加显式版本判断。
- 验收: Groovy 4 路径不再查询该 metadata；测试全绿。

### P2-4 统一 CLI 的可测试入口

- Status: open
- 证据: `NullCheckFindingsCli.groovy:18-23` 直接 `System.exit`
- 问题: 与 `NullCheckCli.main` / `run` 的拆分方式不一致，难以在进程内测试退出码。
- 动作: 抽出 `static int run(List<String> args, PrintStream out, PrintStream err)`，`main` 只负责 `System.exit(run(...))`；补齐 `--help` 与未知参数处理。
- 验收: 可在 Spock 中直接断言退出码与输出。

### P2-5 并发写入 findings/coverage 文件

- Status: open
- 证据: `FileFindingsSink.groovy:29-33` 只对单个 sink 实例 `synchronized (this)`；`Coverage.groovy:47-52` 直接 `File.append`
- 问题: 不同 sink 实例写同一文件时锁不互斥；并发编译可能产生交错的 TSV 行或 coverage 行。
- 动作: 使用按文件路径的静态锁，或让每个 compilation 只有一个 writer；考虑写入临时文件后原子合并。
- 验收: 并发写入测试产生完整、可解析的行，无交错。

### P2-6 默认 annotation mode 的分支流精度

- Status: open
- 证据: `NullCheckVisitor.visitIfElseLexical`（`NullCheckVisitor.groovy:773-797`）只保存/恢复 guard 类事实，不保存 `nullableVars`；NULLNESS-FLOW.md §4 已记录
- 问题: 默认模式（strict 关闭）仍是许多用户的第一入口，分支顺序依赖会导致误报/漏报。目前文档把它视为 transitional，但没有产品层面的决定。
- 动作: 明确决定：要么把 `FlowState` join 引入 annotation mode 的局部变量，要么在文档中声明这是永久限制并给出推荐用法（strict）。
- 验收: 决策记录 + 对应语料；若改进默认模式，annotation mode 的语料变化必须逐条解释。

### P2-7 `NULL` 在状态投影中被压成 `MAYBE_NULL`

- Status: open
- 证据: `NullCheckVisitor.groovy:1308-1326` 的 `restoreState` 把 `NULL` 和 `MAYBE_NULL` 都放入 `nullableVars`；`captureState` 再统一标记为 `MAYBE_NULL`
- 问题: `Nullness.NULL` 在 CFG 中参与 join，但经过 visitor 投影后丢失 "确定为空" 的信息，诊断只能报 "may be null"。这不是错误，但浪费了格上的精度。
- 动作: 要么让 visitor 原生保存 `Nullness`，要么在投影时保留 definite-null 标记，输出 "Cannot dereference null" 类诊断。
- 验收: 新增用例断言 "确定为空" 与 "可能为空" 的消息区分（如果决定实现）。

### P2-8 `NullableAnnotator` 的文本编辑边界

- Status: open
- 证据: `NullableAnnotator.groovy:447-524`（按行正则定位、按 offset 插入）
- 问题: 多行声明、复杂泛型、Tab/Unicode、注解换行等形态会被跳过或定位错误；当前是 opt-in 原型（ROADMAP N3 也标注 design not final）。
- 动作: 保持原型定位，但补强安全网：默认 dry-run 输出 diff；遇到无法安全定位的声明明确跳过并计数；补充幂等性与多行声明测试。
- 验收: 对含 Tab、CRLF、多行声明的样例不产生损坏；重复运行结果稳定。

### P2-9 代码级清理

- Status: open
- 证据与动作:
  - `NullCheckVisitor.groovy:709-713`：同一方法上两个 `@Override`，删除一个。
  - `NullCheckExtension.groovy:4/6`：重复 `import ListProperty`，删除一个。
  - `NullCheckExtension.groovy:60-73`：`guardNotNullWhenTrue` 的 Javadoc 与字段（第 88 行）分离，重新挂在字段上。
  - `NullCheckVisitor.groovy:167-169` 与 `174-186`：`@NonNull` 局部变量赋 null 有两条报告路径，目前靠 reporter 去重；合并为一条。
  - `CorpusRules.groovy:45/84`：`STRICT_ONLY_RULES` / `isStrictOnly` 无生产调用点，删除或接入门禁。
  - `AccessPath.groovy:53`：`isRootedAt` 无调用点，删除或使用。
  - `FlowAnalysis.groovy:83`：`outState` 无生产调用点，删除或保留给诊断。
  - `NullCheckPluginFunctionalSpec.groovy:32`：`PLUGIN_VERSION = '0.3.0'` 与当前版本脱节；改为从项目版本读取，或删除这个未生效的常量。
- 验收: 编译、CodeNarc、SpotBugs、Spotless、语料全绿；无行为变化。

### P2-10 收紧语料阳性用例的期望

- Status: open
- 证据: `corpus/MANIFEST.txt` 中大量 `expect=some`（例如 `positive/nonnull_local_declaration.groovy:63-67`、`positive/nonnull_nullable_declaration.groovy:169-173`）
- 问题: `expect=some` 只要求 "至少一条且匹配声明规则"，额外 finding 不算失败；确定性场景新增的噪声不会被门禁发现。
- 动作: 对输出稳定的 fixture 改为精确计数；只有确实依赖版本/类型推断差异的用例保留 `some`，并在 `note` 中说明原因。
- 验收: 审计全部 `expect=some`，每个保留项都有理由；确定性用例多报会失败。

### P2-11 文档与注释 drift

- Status: open
- 证据与动作:
  - `PORT.md:446` 与 `:449` 重复 `## 6. What is not verified`，合并。
  - `ROADMAP.md`：F2 状态写 done，但 F2b 写 in progress；前文说 "not yet per-variable"，后文 F1 已 done。统一状态与措辞（约 29-70 行）。
  - 根 `build.gradle` 注释写 `allprojects`，实际使用 `subprojects`（约 25-30 行）。
  - `nullchecker-gradle-plugin/build.gradle:22` 注释同样写 `allprojects`。
  - `NULLNESS-FLOW.md` 中引用的行号（如 `visitIfElse` 425 行）已与当前文件不符，改为按符号引用或更新行号。
- 验收: 文档中的状态、版本、符号位置与代码一致。

---

## P3：清理与长期项

### P3-1 Gradle 配置缓存兼容

- Status: open
- 证据: `nullchecker-gradle-plugin/build.gradle:81-91` 明确声明 `configurationCache = false`
- 说明: 这是诚实的现状声明，不是缺陷。`afterEvaluate` 与 `doLast` 捕获 task/extension 是主要障碍。
- 动作: 把可序列化状态迁到 task 的 `Property`/`ConfigurableFileCollection`，移除 `afterEvaluate` 中的任务装配；再逐项打开配置缓存验证。
- 验收: `--configuration-cache` 下 `nullcheck` 可复用配置；功能测试覆盖。

### P3-2 `NullCheckFindingsCli` 的参数校验对齐

- Status: open
- 动作: 补 `--help`、重复参数、缺值参数、未知参数的一致处理；与 `NullCheckCli` 共用一个小型参数解析器或至少共用错误文案。
- 验收: 两个 CLI 对同类误用的退出码和输出一致。

### P3-3 CLI 取值的边界

- Status: open
- 证据: `NullCheckCli.groovy:453-459` 的 `value()` 会把下一个选项当成值（例如 `--report --strict`）
- 动作: 拒绝以 `-` 开头的值，或要求显式 `--report=...` 形式。
- 验收: 误用返回 2 并给出清晰提示。

### P3-4 分类测试的完整表

- Status: open
- 证据: `NullCheckCategoriesSpec.groovy` 只有 5 个用例
- 动作: 为 checker 当前产生的每条消息建立分类表；新增消息时测试失败，强制登记。
- 验收: `NullCheckCategoriesSpec` 覆盖 `NULL_FRAGMENTS` 的每一项及初始化/解析消息。

### P3-5 性能基线复测与微基准

- Status: open
- 证据: `docs/PERF.md` 的基线来自 0.6.0/0.7.x 时期；当前已有 CFG、闭包、autofix 等变化
- 动作: 按 PERF.md 的方法重跑 9 模块或等规模样本；补一个 `captureState`/`restoreState`、`FlowState.join` 的微基准，防止 O(nodes × vars) 退化。
- 验收: 记录新基线；若回归，定位到具体阶段。

### P3-6 `try/finally` 的 abrupt exit

- Status: open
- 证据: `ROADMAP.md` F3 已注明 "Abrupt exits through `finally` are not yet special-cased"
- 动作: 为 `return`/`break`/`continue` 穿过 `finally` 的路径补 CFG 边和语料；或在文档中明确永久限制。
- 验收: 对应语料通过，或限制被写入 PORT/ROADMAP。

### P3-7 Autofix provider SPI（ROADMAP N3）

- Status: open
- 说明: 当前是原型，设计未定。等 P0/P1 稳定后再决定是否引入 `FixProvider` SPI，避免过早固化 API。
- 验收: 设计文档 + dry-run/diff/幂等性测试。

---

## 建议处理顺序

1. **第一批（P0）**：P0-1 sink、P0-2 预算诊断、P0-3 checks 校验、P0-4 结构化分类。这批直接关系 "结果可信"。
2. **第二批（P1）**：P1-3 缓存生命周期、P1-2 删除死分支、P1-1 拆分 visitor、P1-4 配置隔离。先修泄漏和重复实现，再拆类，避免拆完又改语义。
3. **第三批（P2）**：P2-1/P2-2 错误可见性、P2-5 并发写、P2-9 清理、P2-10 语料收紧、P2-11 文档。适合按模块顺手做。
4. **第四批（P3）**：配置缓存、CLI 一致性、性能复测、finally 边、autofix SPI。

## 不回归基线

每个批次结束都应运行：

```bash
./gradlew :nullchecker:test
./gradlew :nullchecker-gradle-plugin:functionalTest
./gradlew check
./gradlew dualRun   # 信息性对照，参考侧必须有输出
```

约定：

- 行为变化必须有阳性/阴性语料对；strict-only 变化需要 strict fixture。
- 修改消息文本时同步更新 `NullCheckCategories` 及其测试（在 P0-4 完成后应不再需要）。
- 每完成一项，更新本文档状态，并在 `CHANGELOG.md` 记录用户可见变化。
