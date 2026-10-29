# nullchecker 项目 Agent 开发规则

- 适用范围：本仓库的所有代码、测试、语料、文档和发布操作。
- 优先级：本文件是项目级补充；`~/.agents/AGENTS.md` 的通用编码基线仍然有效。两者冲突时以本文件为准，并在交付说明中写明原因。
- 目标：让每一次改动都保持 nullchecker 的核心承诺——**结果可信、行为可回归、概念只有一个所有者**。

## 1. 先读这些

开始修改前，按任务需要读取：

- `README.md` / `README.zh-CN.md`：对外行为和构建入口；
- `docs/NULLNESS-FLOW.md`：流分析的语义和边界；
- `docs/ROADMAP.md`：当前阶段和明确不做的范围；
- `docs/REVIEW-ACTIONS.md`：已知架构/质量问题，不要把 open 项当成已完成；
- `docs/PUBLISHING.md`：只有做发布时才需要；
- `PORT.md`：上游移植指纹和有意差异。

不要在没有确认现状的情况下，把文档里的 TODO 或历史计划当成事实。

## 2. 项目原则

### 2.1 可信度优先

- **0 findings 必须可信**。新增规则、放宽分析或跳过路径时，先证明不会造成静默漏报。
- 失败要 fail-closed：未知配置、无法解析的 package 默认、分析预算耗尽、内部错误，都应可见；不能静默降级为“干净”。
- 不要用 suppression、消息匹配或关闭检查来掩盖 false positive；优先修正事实、契约或流状态。
- 允许保守的 false positive；不允许未经证明的 soundness 声明。

### 2.2 行为变化必须有证据

- 新规则/新 guard：同时增加阳性语料和阴性语料。
- strict-only 的变化：增加 strict fixture，并确认 annotation mode 的行为。
- 修复 bug：先写最小复现或 failing test，再改实现。
- 修改消息文本：同步检查 `NullCheckCategories` 和分类测试。
- 增加/修改契约：覆盖配置 > 注解 > 内置表的优先级，以及格式错误。

### 2.3 Java 与 Groovy 边界

- nullchecker 只检查 Groovy 源码；普通 `.java` 文件不参与检查。
- `package-info.java` 会被读取以提供包级默认值；Java API 的 `@Nullable` / `@NonNull` / `@Contract` 可以作为 Groovy 调用方的契约。
- 不要声称 Java 方法体会被本工具检查；需要 Java null 检查时使用独立的 Java 工具。
- autofix 只处理 Groovy 源文件。

## 3. 代码风格

通用基线（显式类型、一行一条 statement、强制大括号、避免 stream/lambda 管道、命名与职责）继续适用。项目特定约定如下：

- 生产源码使用 Groovy 4 和 `@CompileStatic`；不要为了省事新增动态代码。
- 生产代码禁止 `def`；容器必须写元素类型。Spock 测试中的 `def` 和动态 fixture 是允许的，但测试逻辑仍应清楚。
- Groovy 闭包是本项目的领域 API；短小、无业务分支、纯内存的 `collect` / `findAll` 可以使用。复杂流程、IO、分支和状态修改使用显式循环。
- 现有代码中的 AST `instanceof` 分派是编译器插件的一部分；新增时集中在一处，不要散落成类型判断长链。
- 保留既有文件的注释/Javadoc 语言风格；面向用户的文档和变更说明以中文为主。注释只写“为什么”，不写代码已经表达的内容。
- 不做与当前任务无关的格式化、重命名或大规模重构；需要时单独提交。

## 4. 架构所有权

修改前先问：“这个问题已经由谁回答？”

| 问题 | 所有者 |
| --- | --- |
| 一次调用承诺了什么？ | `Contract` / `ContractRegistry` |
| 这个值在这里可空吗？ | `FlowState` / `Nullness` / `FlowAnalysis` |
| 注解和 AST 细节怎么读？ | `AstSupport` |
| 包/类默认非空吗？ | `PackageScopes` |
| 哪些规则族运行、strict 等配置？ | `NullCheckConfig` |
| finding 如何创建、分类、写入？ | `NullCheckReporter` / `NullCheckSinks` / `NullCheckReportWriter` |
| 规则覆盖和语料门禁？ | `CorpusRules` / `CorpusGate` |
| 高置信度源码修改？ | `NullableAnnotator` / `NullCheckAutofixCli` |
| 插件参数和任务装配？ | `NullCheckExtension` / `NullCheckPlugin` |

规则：

- 新增第二种/第三种同语义实现前，先用 `rg` 枚举现有位置；优先扩展已有表、模型或 dispatch 点。
- **第三个回答同一语义问题的实现是设计缺陷**，不是新功能；但只有同时满足同一不变量、同一生命周期、同一优先级、同一失败语义时才合并。
- 不要因为“形状相似”就抽象；遵守 rule of three，并先证明行为等价。
- 新概念/新所有者要更新本表或对应文档；不要只留在 commit message 里。
- 不要把消息字符串当成 finding 的类型来源；新 finding 应结构化分类，P0-4 完成前也不要扩大字符串映射。

## 5. 修改流程

### 5.1 开始前

1. 复现问题，写出最小输入和实际输出；
2. 用 `rg` 找到所有回答同一问题的位置；
3. 判断是 bug、行为扩展还是纯重构；
4. 计划要改哪些所有者、哪些语料、哪些兼容路径；
5. 大改动先拆分步骤，不要把 feature 和重构混在一个 commit。

### 5.2 实现

- 先建立行为基线，再移动代码；
- 一次只改一个语义层；保持 strict/annotation 两条路径的行为差异有测试；
- 不删除“看起来不可达”的分支，除非有断言、计数或测试证明；
- 新增配置必须有默认值、非法值行为和测试；
- 新增 public/API 行为必须更新 README/CHANGELOG；
- 修 false positive 时，优先修 guard/fact，不要加 suppression。

### 5.3 验证

按改动范围运行，至少：

```bash
./gradlew :nullchecker:test
./gradlew :nullchecker:codenarcMain :nullchecker:nullcheck
./gradlew check
./gradlew dualRun
./gradlew :nullchecker-gradle-plugin:functionalTest
```

- `check` 是核心门禁：每个规则都要有会被报告的语料，每个 guard 都要有保持静默的语料；
- `dualRun` 是信息性对照，不是发布门禁；参考侧必须有输出；
- 发布前再运行 `./gradlew clean check dualRun dist`，并遵循 `docs/PUBLISHING.md`；
- 不要声称未实际运行的命令通过；报告里写清运行环境和结果。

## 6. 测试与语料

- `nullchecker/corpus/MANIFEST.txt` 是规则覆盖的事实来源；新增规则必须登记。
- 阳性 fixture 证明会报，阴性 fixture 证明不会误报；两者都要尽量确定，不要用 `expect=some` 掩盖额外 finding。
- 修改 finding 文案时，检查 `NullCheckCategories`、`CorpusRules` 和相关 spec。
- 新增/修改 autofix 时，覆盖 dry-run、apply、幂等性，以及“没有安全处理就不改”的对照。
- Java 互操作、`package-info`、classpath 注解都属于边界行为，改动时同时跑 plugin functional tests。
- 性能敏感改动参考 `docs/PERF.md` 的方法，不要只看单测时间。

## 7. Agent 协作规则

### 7.1 先盘点，再写代码

在写新分支、新字符串表、新注解解析或新 finding 规则前，先列出：

- 这个问题目前由哪些 `file:symbol` 回答？
- 它们的输入、生命周期、优先级和失败策略是否一致？
- 如果不合并，理由是什么？

把盘点结果写进计划或交付说明。不要因为“最小 diff”而跳过盘点；也不要因为发现了相似代码就立即合并。

### 7.2 两遍式评审

功能实现完成后，做一遍独立的“合并评审”：

- 只看 diff 和仓库，不被实现理由锚定；
- 找“同一个问题被回答了多次”的地方；
- 检查新代码是否绕过了所有权表；
- 给出行为保持的测试。

实现和合并评审最好分成两个 commit；纯重构不要混入 feature。

### 7.3 交付说明

必须说明：

- 改了什么行为；
- 影响哪些模式（strict / annotation / compile / CLI）；
- 运行了哪些命令，结果如何；
- 新增/修改了哪些语料和文档；
- 是否引入新概念、新所有者或兼容性变化；
- 如果有意偏离全局基线，说明原因和影响范围。

不要用“已经完成”“测试通过”代替具体命令和结果。

### 7.4 不做什么

- 不在用户未要求时发布、打 tag 或改版本号；
- 不为了通过检查而关闭检查或扩大 suppression；
- 不把真实业务项目名、内部类名或未脱敏代码写进仓库文档/语料；
- 不把 `docs/REVIEW-ACTIONS.md` 的 open 项擅自标成 done；
- 不在没有证据时删除“死代码”，尤其是 strict 流分析路径。

## 8. 完成定义

提交前逐项确认：

- [ ] 复现/输入已明确，行为变化有阳性+阴性语料
- [ ] 没有绕过现有所有者；概念变化已更新所有权表或文档
- [ ] strict 和 annotation mode 的预期行为都有测试
- [ ] finding 分类、消息和测试同步
- [ ] autofix 仍然 dry-run 可行且幂等
- [ ] `check` 和 plugin functional tests 通过（或明确说明未跑）
- [ ] CHANGELOG/README/ROADMAP 中受影响的内容已更新
- [ ] 没有未说明的全局基线偏离
