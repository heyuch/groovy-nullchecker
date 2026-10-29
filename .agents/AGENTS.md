# nullchecker 项目 Agent 开发规则

- 适用范围：本仓库的所有代码、测试、语料、文档和发布操作。
- 本文件是**自包含**的项目规范。其他开发者或 Agent 不需要预先安装 `~/.agents/AGENTS.md`。
- 优先级：用户的直接指令 > 本文件 > 仓库其他文档。冲突时明确说明原因，不要静默选择。
- 目标：保持 nullchecker 的核心承诺——**结果可信、行为可回归、概念只有一个所有者**。

## 1. 开始工作前

按任务需要读取：

- `README.md` / `README.zh-CN.md`：对外行为和构建入口；
- `docs/NULLNESS-FLOW.md`：流分析语义和边界；
- `docs/ROADMAP.md`：当前阶段和明确不做的范围；
- `docs/REVIEW-ACTIONS.md`：已知架构/质量问题，open 项不是已完成；
- `docs/PUBLISHING.md`：只有发布时才需要；
- `PORT.md`：上游移植指纹和有意差异。

先复现问题，再改代码。不要把历史计划、TODO 或注释当成当前事实。

## 2. 通用代码规范

### 2.1 可读性优先

- 一行一条 statement；不要用分号把多条语句压在一行。
- 一行内最多一层“接收者上的方法调用”（`order.getItems()` 可以）。需要第二层调用时，先赋给具名局部变量。
- 只用 `if / else if / else / for / foreach / while / switch / try-catch` 等基础结构。
- `if`、`for`、`while` 一律加大括号，即使只有一条 statement。
- 不使用嵌套三元表达式；三元只允许单层，且两个分支都是简单值。
- 不把业务判断藏进谓词、闭包、lambda 或注解表达式里。
- 注释只写代码表达不出的“为什么”；不要用注释解释“这段在干嘛”，先提炼成语义清楚的方法。
- 不用只起分隔作用的注释横幅；空行本身就是分块。

### 2.2 命名

名称长度跟随作用域：

| 作用域 | 要求 |
| --- | --- |
| 类属性、全局、跨模块契约 | 精确、无歧义，必要时加限定词 |
| 方法局部变量 | 一个词即可；超过 3 个单词要有理由 |
| 循环索引、≤ 5 行作用域 | 单字符，如 `i`、`n` |

- 集合用复数，单个用单数：`orders` / `order`。
- 名称不携带类型信息：不写 `orderList`、`orderMap`、`orderStr`、`iCount`。
- 不使用分层后缀：`orderDO`、`userPO`、`paymentDTO` 禁止；类型由声明和包路径表达。
- 不造缩写；只使用领域通用缩写（`id`、`url`、`qty`）。
- 布尔用 `is / has / can / should` 前缀。
- 同一个概念全工程使用同一个词；不要在 `user / customer / member` 之间摇摆。

### 2.3 代码分块与函数

- 方法体按“做一件事”分块：校验 → 取数 → 计算 → 写入 → 组装返回；块之间留空行，块内不插空行。
- 变量声明紧跟使用点；不要把变量集中堆在方法开头。
- 连续空行压成一个；方法体首行和末行不留空行。
- 一个方法只做一件事；方法名 + 返回值应能用一句不带“和/然后/同时”的话说清。
- 方法体保持单一抽象层级：高层方法编排具名步骤，下标遍历、字符串拼接、边界判断下沉到私有方法。
- 方法名是动词短语并指向意图：`calculateOverdueDays`，不要 `process`、`handle`、`doWork`。
- 参数 0–3 个；超过时收成参数对象。禁止输出参数、禁止修改入参。
- 布尔开关参数最多一个，名称带 `is / has`；两条分支都短时才使用，否则拆成两个方法。
- 查询与修改分离：`find`、`count`、`exists` 不改状态；改状态用动词。
- 副作用必须与名字一致；叫 `validate` 就不能顺手写库。
- 方法超过约 20–30 行、出现 3 个以上可命名步骤、或嵌套超过 2 层时，先考虑拆分。
- 不要为减少行数而过度提炼；单行、只调用一次、名字没有新增语义的纯转发方法不要拆。

### 2.4 项目允许的写法

- Groovy 闭包是编译器/AST API 和测试 fixture 的一部分。短小、纯内存、无业务分支的 `collect` / `findAll` 可以使用；复杂流程、IO、分支和状态修改用显式循环。
- AST/编译器分派需要 `instanceof`；集中在一处，不要扩散成类型判断长链。
- Spock 测试允许 `def`、动态 fixture 和框架惯用的返回值写法；生产源码仍应显式类型。
- 现有上游移植代码可能保留少量不符合本文风格的写法；不要为了格式或链式调用做无关重构，新代码按本文执行。
- CLI 的 `System.exit`、`println` 和直接文件访问是其接口的一部分，不属于业务层违规。

### 2.5 静态类型与数据建模

- 生产代码显式声明类型；禁止 `def` / `var` 作为业务对象类型。
- 容器必须带元素类型：`List<Order>`，不写裸 `List`；不要用 `Object` 当逃生口。
- 有固定字段的业务数据用数据类（Groovy class / record / DTO），不要用 `Map`、数组、元组承载。
- `Map` 只用于真正的键值字典、外部消息原始载荷、API 边界无序键集合；进入业务逻辑前转换成数据类。
- 不用动态属性、`set/get` 魔法、字符串键绕开建模。
- 生产 Groovy 源码默认使用 `@CompileStatic`；新增动态代码必须有明确理由。

### 2.6 类设计：SOLID + GRASP

修改类之前逐条检查：

- **SRP**：变化的理由是否多于一个？多于一个就拆。
- **OCP**：新增业务场景时是否要改已有 `if/else` 或 `switch`？先找扩展点。
- **LSP**：子类不得加强前置条件、削弱后置条件、抛接口未声明的异常，也不得用空实现掩盖未实现。
- **ISP**：接口按调用方拆分，不强迫实现方实现用不到的方法。
- **DIP**：业务类依赖抽象，不直接 `new` 基础设施或具体实现。

职责分配按 GRASP 顺序判断：

1. **Information Expert**：谁持有数据，谁提供操作；
2. **Creator**：谁有创建所需上下文，谁创建；
3. **Low Coupling / High Cohesion**：依赖面最小、职责内聚；
4. **Controller**：入口只接收参数、编排、组装响应，不写领域规则；
5. **Polymorphism**：按类型分叉用多态，不写 `instanceof` / 类型枚举长链；
6. **Pure Fabrication**：没有领域归属时才引入服务/工厂；
7. **Indirection**：加中介前说明它解除了哪两个耦合；
8. **Protected Variations**：把易变协议、规则、排序/展示口径放到稳定接口后。

### 2.7 设计模式取舍

引入模式、抽象层或 Service/Factory/Strategy/Manager/Helper 前回答五个问题：

1. 能否不用模式？不用也是结论；
2. 用哪种模式？
3. 它简化了什么？
4. 它复杂了什么（间接层、样板、调试路径）？
5. 值得吗？

只有一个实现的接口、只调用一处的工厂、只有一个分支的策略，通常不值得。

## 3. 项目特有约束

### 3.1 可信度优先

- **0 findings 必须可信**；新增规则、放宽分析或跳过路径时，先证明不会造成静默漏报。
- 失败要 fail-closed：未知配置、无法解析的 package 默认、分析预算耗尽、内部错误都必须可见；不能静默变成“干净”。
- 不要用 suppression、消息匹配或关闭检查来掩盖 false positive；优先修正 fact、contract 或 flow。
- 允许保守的 false positive；不允许未经证明的 soundness 声明。

### 3.2 行为变化必须有证据

- 新规则/guard：同时增加阳性语料和阴性语料。
- strict-only 变化：增加 strict fixture，并确认 annotation mode 行为。
- 修 bug：先写最小复现或 failing test，再改实现。
- 改消息文本：同步检查 `NullCheckCategories`、`CorpusRules` 和分类测试。
- 改 contract：覆盖配置 > 注解 > 内置表的优先级，以及格式错误。

### 3.3 Java/Groovy 边界

- nullchecker 只检查 Groovy 源码；普通 `.java` 文件不参与检查。
- `package-info.java` 会被读取以提供包级默认值；Java API 的 `@Nullable` / `@NonNull` / `@Contract` 可作为 Groovy 调用方契约。
- 不声称 Java 方法体会被本工具检查；需要 Java null 检查时使用独立 Java 工具。
- autofix 只处理 Groovy 源文件。

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
- 新概念/新所有者要更新本表或对应文档，不要只留在 commit message 里。
- 不要把消息字符串当成 finding 的类型来源；新 finding 应结构化分类，P0-4 完成前不要扩大字符串映射。

## 5. 静态检查与门禁

### 5.1 已经机械执行的规则

以下规则由 Gradle/CodeNarc/Spotless/SpotBugs/语料门禁执行，不依赖 Agent 记忆：

| 规则 | 执行方式 |
| --- | --- |
| 生产代码 `@CompileStatic`、禁止 `def` | CodeNarc `CompileStaticRule`、`NoDefRule` |
| 变量/字段/参数/返回值显式类型 | CodeNarc `VariableTypeRequiredRule`、`FieldTypeRequiredRule`、`MethodParameterTypeRequired`、`MethodReturnTypeRequired` |
| `if/for/while/else` 大括号 | CodeNarc `BracesFor*`、`IfStatementBraces` |
| 格式、缩进、空行、尾随空白 | Spotless + CodeNarc formatting rules |
| 命名、import、未使用符号、明显 bug pattern | CodeNarc + SpotBugs |
| 规则/guard 语料覆盖 | `CorpusGate`，由 `check` 执行 |
| contract 解析只有一个所有者 | `ArchitectureSpec` |
| 生产代码不引入 Java Stream | `ArchitectureSpec` |
| nullchecker 自身无 finding | `:nullchecker:nullcheck`（dogfood） |

`./gradlew check` 和 plugin functional tests 是门禁；CodeNarc/Spotless 目前是 **check/lint 阶段**执行，不是编译器 warning。需要实时反馈时，使用 IDE 插件或本地运行对应任务。

### 5.2 不应重复写成纯文字规则

如果一条规范可以机械检查：

1. 先加到 CodeNarc/Spotless/ArchitectureSpec；
2. 更新 `docs/CODENARC.md` 或对应测试；
3. 不要只在本文档里重复描述。

新增 CodeNarc 规则后必须用 `-PcodenarcAll=true` 评估全量命中，并记录在 `docs/CODENARC.md`，不能让已知违规无声地变成 baseline。

### 5.3 只能靠语义判断的规则

以下不强行做成静态检查，由计划、评审和本文档约束：

- SOLID/GRASP 的职责归属、抽象是否值得；
- false positive 与 soundness 的取舍；
- 数据建模选择、API 兼容性、命名是否表达领域含义；
- 语料是否真正覆盖语义（`CorpusGate` 只能证明登记和触发）；
- CHANGELOG/README 是否准确；
- 两个实现是否真的“同一概念”；
- 代码审查中的重复概念识别。

这些内容应写进计划、commit message 和评审意见，而不是伪装成 lint 规则。

## 6. 修改与测试流程

### 6.1 开始前

1. 复现问题，写出最小输入和实际输出；
2. 用 `rg` 找到所有回答同一问题的位置；
3. 判断是 bug、行为扩展还是纯重构；
4. 列出要改的所有者、语料、兼容路径；
5. 大改动拆步骤，不把 feature 和重构混在一个 commit。

### 6.2 实现

- 先建立行为基线，再移动代码；
- 一次只改一个语义层；strict/annotation 两条路径的差异必须有测试；
- 不删除“看起来不可达”的分支，除非有断言、计数或测试证明；
- 新配置必须有默认值、非法值行为和测试；
- 新 public/API 行为必须更新 README/CHANGELOG；
- 修 false positive 优先修 guard/fact，不加 suppression。

### 6.3 验证命令

```bash
./gradlew :nullchecker:test
./gradlew :nullchecker:codenarcMain :nullchecker:nullcheck
./gradlew check
./gradlew dualRun
./gradlew :nullchecker-gradle-plugin:functionalTest
```

- `check` 是核心门禁：每个规则都要有会被报告的语料，每个 guard 都要有保持静默的语料；
- `dualRun` 是信息性对照，不是发布门禁；参考侧必须有输出；
- 发布前运行 `./gradlew clean check dualRun dist`，并遵循 `docs/PUBLISHING.md`；
- 不要声称未实际运行的命令通过；报告中写清环境和结果。

## 7. Agent 协作与概念合并

### 7.1 先盘点，再写代码

在写新分支、新字符串表、新注解解析或新 finding 规则前，先列出：

- 这个问题目前由哪些 `file:symbol` 回答？
- 它们的输入、生命周期、优先级和失败策略是否一致？
- 如果不合并，理由是什么？

把盘点结果写进计划或交付说明。不要因为“最小 diff”跳过盘点，也不要因为发现相似代码就立即合并。

### 7.2 两遍式评审

功能完成后做一遍独立的合并评审：

- 只看 diff 和仓库，不被实现理由锚定；
- 找“同一个问题被回答了多次”的地方；
- 检查新代码是否绕过所有权表；
- 给出行为保持的测试。

实现和合并评审最好分成两个 commit；纯重构不要混入 feature。

### 7.3 交付说明

必须说明：

- 改了什么行为；
- 影响哪些模式（strict / annotation / compile / CLI）；
- 实际运行了哪些命令，结果如何；
- 新增/修改了哪些语料和文档；
- 是否引入新概念、新所有者或兼容性变化；
- 如果有意偏离基线，说明原因和影响范围。

不要用“已经完成”“测试通过”代替具体命令和结果。

### 7.4 不要做

- 不在用户未要求时发布、打 tag 或改版本号；
- 不为通过检查而关闭检查或扩大 suppression；
- 不把真实业务项目名、内部类名或未脱敏代码写进仓库文档/语料；
- 不把 `docs/REVIEW-ACTIONS.md` 的 open 项擅自标成 done；
- 不在没有证据时删除“死代码”，尤其是 strict 流分析路径。

## 8. 完成定义

- [ ] 复现/输入明确，行为变化有阳性+阴性语料
- [ ] 没有绕过现有所有者；概念变化已更新所有权表或文档
- [ ] strict 和 annotation mode 的预期行为都有测试
- [ ] finding 分类、消息和测试同步
- [ ] autofix 仍然 dry-run 可行且幂等
- [ ] `check` 和 plugin functional tests 通过（或明确说明未跑）
- [ ] CHANGELOG/README/ROADMAP 中受影响的内容已更新
- [ ] 没有未说明的基线偏离
