# 与 AI Coding Agent 协作：让"概念合并"成为默认行为

- Status: draft（供讨论，尚未落地）
- Date: 2026-10-29
- 案例: Contract 统一（commit `0b7dff7`）
- 相关: [REVIEW-ACTIONS.md](REVIEW-ACTIONS.md)、[PORT.md](../PORT.md)、[NULLNESS-FLOW.md](NULLNESS-FLOW.md)

---

## 0. 一句话结论

Agent 并不缺"发现相似性"的能力，缺的是**把发现变成行动的流程**。要让它从"完成表面任务"转向"必要时抽象统一"，需要同时提供三样东西：

1. **发现机制**：强制它在写代码前枚举同一问题的现有实现。
2. **授权机制**：明确"第三个实现是设计缺陷，合并属于任务范围"，否则它会遵守"最小 diff、不做未要求重构"的默认准则。
3. **持久化机制**：把一次性的洞察写进仓库（所有权表、ADR、可执行的架构测试），让下一次会话继承。

三者缺一不可：只有发现没有授权，会产生一堆 `TODO`；只有授权没有发现，会过早抽象；只有文档没有可执行约束，文档会腐烂。

---

## 1. 案例：Contract 统一

### 1.1 统一前

`0b7dff7` 的父提交里，`NullChecker.groovy` 有 2142 行。与"一次调用承诺了什么"相关的知识分散在至少这些位置：

| 知识 | 统一前的实现位置（约） |
| --- | --- |
| JetBrains `@Contract` 解析 | 3 处：约 1063、1270、2100 行 |
| `@EnsuresNonNullIf` | 约 1050 行 |
| `@EnsuresNonNull` | 约 1260 行 |
| 抛异常的校验方法 | `NONNULL_VALIDATOR_METHODS`，约 199 行 |
| 永不返回的注解 | `NO_RETURN_ANNOS`，约 2077 行 |
| 项目声明的 guard 列表 | 独立的一套匹配逻辑 |
| `neverReturns` 列表 | 又一套独立逻辑 |
| `Map.get` / `getOrDefault` / `find` / `max` | 硬编码在分支里 |

对应的是五个前后相继的 feature commit：

- `f189cfd` Track map keys and values, and read JetBrains `@Contract`
- `f6a947d` Read guard contracts declared on the method, and `NotEmpty`/`NotBlank` as non-null
- `68065d1` Let a project declare its own guard methods
- `fa29d23` Stop checking code after a call that never returns
- `3e4591a` Name the common library guards in the documentation

每个 commit 都只解决一个局部问题，测试都通过，代码都"正确"。然后才有了：

- `0b7dff7` Resolve every contract through one registry

### 1.2 关键观察：Agent 已经"看见"了相似性

统一前的代码注释里写着：

```groovy
// JetBrains' @Contract spells the same idea as text: "null -> false" ...
// arguments are non-null. Same effect as requireNonNull, stated on the method
```

也就是说，**"same idea" 和 "same effect" 是 Agent 自己写下的**。它并不是没有识别出语义相似，而是没有把"相似"升级为"应该合并"的行动。

这是一个很重要的区分：

- **感知层**：Agent 能看到局部重复。
- **决策层**：Agent 的默认准则是"完成当前 ticket、保持最小 diff"。
- **执行层**：Agent 完全有能力写出统一模型——`0b7dff7` 的 commit message 就是证明。

所以瓶颈主要在决策层，而不是感知层或执行层。

### 1.3 统一后的模型

`0b7dff7` 的 commit message 第一段把问题说得非常准确：

> The same ideas were implemented five times: `@Contract` was parsed in three places,
> `@EnsuresNonNullIf` and `@EnsuresNonNull` in two, the guard lists and `neverReturns`
> each had their own handling, and the Map/collection result rules were hard-coded.
> The sources could disagree about the same call and a fourth one would have meant
> editing all of them.

统一后的结构是：

- 一个 `Contract` 模型：结果可空性、分支内收窄、正常返回后收窄、是否永不返回。
- 一个 `ContractRegistry`：按"配置 > 注解 > 内置表"的优先级解析。
- 一个缓存：按 `MethodNode` 缓存，包括"没有 contract"这一否定结果。
- 一个测试：`ContractRegistrySpec` 覆盖配置覆盖注解、覆盖内置、无注解场景、格式错误忽略。

这里有一个值得保留的设计细节：**统一不等于把所有逻辑塞进一个方法**。`ContractRegistry` 只拥有"解析和优先级"，visitor 仍然负责"把 contract 应用到当前 AST 位置"。统一的是**知识的所有者**，不是所有代码路径。

---

## 2. 诊断：为什么 Agent 默认"贪心"

把原因拆开，才能对症下药：

1. **任务边界是 ticket，而不是概念**。每个请求都是"支持 X"，验收标准是"X 能用"。合并看起来是额外工作。
2. **强先验是"最小 diff、不做未要求的重构"**。这是合理的工程纪律，但它会压制主动抽象。除非项目明确授权，Agent 不会把合并视为任务的一部分。
3. **测试只验证行为，不验证知识位置**。五套实现都能让测试通过；重复在测试里是不可见的。
4. **上下文是局部的、会话是新的**。Agent 看不到"这是第五次"，也看不到上一个会话的洞察。
5. **抽象是全局属性，局部推理者天然不擅长**。它能看到当前文件的重复，但很难自发地横向检索整个仓库。
6. **增量提交没有"第三次"触发器**。每个 feature 都是第一次做某件事，直到某一天你意识到它已经是第五次。

这也解释了为什么"你告诉它要统一"之后，它能做得很好：一旦任务边界从"支持 X"变成"统一 X 的五个实现"，它的能力就匹配了。

---

## 3. 核心模型：发现 → 授权 → 持久化

```
发现 ──→ 授权 ──→ 执行 ──→ 持久化
 │         │         │         │
 │         │         │         └─ ADR / 所有权表 / fitness test
 │         │         └─ 行为保持的重构 + 等价测试
 │         └─ Definition of Done / AGENTS.md / 用户明确同意
 └─ inventory / 命名 / 注释触发词 / 重复信号
```

- **发现**：枚举"同一个问题"的所有实现位置。
- **授权**：明确合并属于任务范围，而不是 scope creep。
- **执行**：先引入模型、保持行为、再删旧路径。
- **持久化**：让下一次会话和下一个 Agent 继承这个决定。

下面按杠杆从高到低列出可用机制。

---

## 4. 机制清单

### 4.1 完成定义与 `.agents/AGENTS.md`

这是成本最低、杠杆最高的一层。项目级规则已经落在 `.agents/AGENTS.md`；根 `AGENTS.md` 尚未创建。建议内容见附录 A。

核心规则：

- 同一问题出现**第三个实现**时，视为设计缺陷，而不是新功能。
- 新增分支/字符串表前，先搜索该问题的现有所有者。
- 注释里出现 `same as` / `same effect` / `equivalent` / `keep in sync`，立即停下来判断是否应该合并。
- 行为变化必须有语料；概念变化必须有 ADR 和所有权表更新。
- 优先扩展数据表，而不是再加一个 `if`。

这条规则解决的是"授权"问题：它把"合并"从额外工作变成完成定义的一部分。

### 4.2 概念所有权表

用"问题 → 所有者"而不是"文件 → 职责"来组织。前者更接近 Agent 的搜索方式：它在写代码时脑子里是一个问题，而不是一个目录。

| 问题 | 所有者 |
| --- | --- |
| 一次调用承诺了什么？ | `Contract` / `ContractRegistry` |
| 这个值在这里可空吗？ | `FlowState` / `Nullness` |
| 注解怎么读？ | `AstSupport` |
| finding 去哪？ | `NullCheckReporter` / `NullCheckSink` |
| 这个包/类默认非空吗？ | `PackageScopes` |
| 哪些规则族运行？ | `NullCheckConfig` |
| 一个规则是否被覆盖？ | `CorpusRules` / `CorpusGate` |

这张表可以放在 `AGENTS.md`，详细版放在 `docs/CONCEPTS.md`。它的作用是：下一次 Agent 要加第四种 contract 来源时，先看到"这个问题已经有所有者"，从而扩展而不是新建。

### 4.3 `concept-consolidation` skill

把"合并"做成一个可重复的流程，而不是依赖灵感。详细草稿见附录 B。

六个步骤：

1. **Inventory**：用 `rg` 找出所有回答同一问题的位置。
2. **Classify**：是同一不变量/生命周期/优先级/失败语义，还是只是形状相似？
3. **Model**：给概念命名，写出不变量、来源、优先级、缓存/生命周期、错误策略。
4. **Migrate**：先在旧调用点后面引入模型，保持行为；用测试/语料证明等价；再删旧路径。
5. **Guard**：加 fitness test、所有权表、ADR。
6. **Report**：给出"未来改一处要动几个文件"的前后对比。

Skill 的关键不是教 Agent 重构，而是强制它在写代码前做 **inventory**。很多重复不是因为不会合并，而是因为从未枚举过"同一个问题还有哪些实现"。

### 4.4 Architecture fitness test

提示词和 skill 是软约束；真正能防止下一次重复的是 CI 里的硬约束。对这个项目，2～3 个高信号测试就够了：

```groovy
// 概念：contract 解析只能有一个所有者
def 'contract parsing has one owner'() {
    def sources = sourceFiles.findAll {
        it.name != 'ContractRegistry.groovy' &&
        it.text =~ /findAnnotation\([^)]*'Contract'|parseContract|contractFromJetBrains/
    }
    expect: sources.isEmpty()
}
```

其他候选：

- `System.getProperty('groovy.nullchecker.` 只出现在配置/传输层。
- `new FileFindingsSink` 只出现在 `NullCheckSinks`。
- 新增 `NULL_FRAGMENTS` 必须同时有分类测试。

这类测试的价值是双向的：它既阻止未来的 Agent 绕过所有者，也在它绕过时**告诉它所有者是谁**。把"架构意图"变成可执行规则，比写在文档里有效得多。

注意：正则型架构测试容易脆，数量要少、信号要高、允许列表要明确。更好的做法是 AST 级检查，但成本更高，可以后置。

### 4.5 两遍式工作流：实现 + 合并评审

实现者和评审者的视角天然不同。最有效的做法是**两遍式**：

1. **实现遍**：按 ticket 写功能，最小 diff，测试通过。
2. **合并遍**：新开一个会话/子 Agent，只给 diff 和仓库，不给实现理由，问：
   - 哪些地方在回答同一个问题？
   - 它们的不变量是否一致？
   - 如果合并，行为保持的测试是什么？

换上下文很重要：实现者会被自己的局部方案锚定，评审者没有这个包袱。这个项目里 `0b7dff7` 就是一次典型的"合并遍"，而且它被单独提交为纯重构，没有和 feature 混在一起——这个习惯值得保留。

评审 Agent 的 prompt 见附录 C。

### 4.6 提示词启发式

这些可以直接放进 skill、plan 模板或 review checklist：

| 启发式 | 问题 |
| --- | --- |
| Next-change test | 如果明天要支持第 4 种 contract 来源，需要改几个文件？ |
| Delete test | 删除这个功能要改几处？ |
| Noun test | 你新引入了哪些名词？哪些是已有名词的同义词？ |
| Sync test | 有没有两处必须同时修改才能保持正确？ |
| Comment test | 注释里有没有 "same as / equivalent / 保持一致"？ |
| Pre-coding inventory | 先不要写代码，列出这个行为目前由哪些 `file:line` 回答。 |

这些问题的共同点是：它们把"抽象"从审美判断变成**可回答的工程问题**。

### 4.7 计划阶段的 "Concept impact"

如果使用 plan mode，可以在计划模板里固定增加一节：

```markdown
## Concept impact

- 新增/扩展了哪个概念？
- 现有实现中哪些位置需要迁移？
- 哪些位置必须保持不变？
- 如何证明行为不变（语料/测试）？
- 如果不合并，理由是什么？
```

计划审批本身就是一次低成本的架构门禁。它比在代码写完后再评审更容易阻止"第五套实现"。

### 4.8 持久化：ADR、Concept debt、TODO(concept)

一次性的洞察必须落到仓库，否则下一个会话不会记得：

- `docs/CONCEPTS.md`：问题 → 所有者表，以及每个概念的不变量。
- `docs/adr/0001-contract-model.md`：把 `0b7dff7` 的 commit message 变成 ADR。
- `docs/BACKLOG.md` 增加 **Concept debt** 区：发现重复但暂不合并时，记录概念名和所有实现位置。
- 代码里用 `TODO(concept: Contract)` 标记"我知道这里重复，但先欠着"；CI 可以统计这些标记。
- commit message 模板增加两行：

```text
Duplication audit: <发现的重复概念、位置、决定>
Concept changes: <新增/扩展了哪个所有者，是否更新 ADR>
```

`0b7dff7` 的 commit message 已经是极好的 ADR；把它模板化，下次 Agent 就会在提交前被迫回答这些问题。

### 4.9 弱信号：重复检测工具

CPD / jscpd / 自定义 `rg` 查询可以作为弱信号，用来提示评审，不建议直接作为失败门禁：

```bash
# 同一注解名在多个文件被解析
rg -n "findAnnotation\([^)]*'(Contract|EnsuresNonNull|NoReturn)'"

# 同一组方法名在多个地方出现
rg -n "'requireNonNull'.*'checkNotNull'|'find'.*'findResult'.*'max'.*'min'"

# 注释里的"同义"标记
rg -n "same (idea|effect)|equivalent to|keep in sync|保持一致"
```

这些查询的价值是**帮你找到候选**，最终判断仍然需要人或评审 Agent。

---

## 5. 边界：什么时候不该合并

这套机制的风险是过度抽象。建议用四个"同"作为判据：

| 判据 | 问题 |
| --- | --- |
| 同一不变量 | 它们是否在回答同一个语义问题？ |
| 同一生命周期 | 是否都按 compilation/session 创建和失效？ |
| 同一优先级 | 多个来源冲突时，它们是否有相同的优先级规则？ |
| 同一失败语义 | 格式错误时，是否都选择忽略而不是猜测？ |

Contract 的五个实现同时满足这四条，所以合并是对的。如果只是代码形状相似（比如两个 `if` 都判断字符串），但语义、生命周期不同，就不要合并。

其他约束：

- **Rule of three**：第一次具体实现，第二次容忍重复，第三次再抽象。
- **行为保持**：先加测试/语料，再重构；不要在一个 commit 里混合 feature 和 refactor。
- **统一模型必须减少变更面**：如果它只是增加了一层间接调用，而没有减少"未来改一处要动几个文件"，那就是 speculative generality。
- **避免概念上帝类**：如果统一后的模型有很多互不相关的字段和 flag，可能它其实是多个概念。

---

## 6. 对本项目的落地建议

### 6.1 最小方案（建议先做）

1. 在 `.agents/AGENTS.md` 维护"概念所有权表 + 第三个实现即设计缺陷"的规则。
2. 把 `0b7dff7` 的 commit message 提升为 `docs/adr/0001-contract-model.md`。
3. 加一个 architecture fitness test：contract 解析只能出现在 `ContractRegistry`。

这三件事成本低、可直接观察效果：下一个新增 contract 来源的 feature，应该只会改 `ContractRegistry` 和语料。

### 6.2 增强方案

- 增加 `concept-consolidation` skill（附录 B）。
- 增加 `docs/CONCEPTS.md`，把所有权表从 `AGENTS.md` 展开。
- 增加 2～3 个 fitness tests（系统属性读取、sink 创建、消息分类）。
- 引入两遍式工作流：feature commit + 独立的 consolidation commit。
- 在 `docs/BACKLOG.md` 增加 Concept debt 区。

### 6.3 不建议现在做

- 不要为了这套机制去大改 `NullCheckVisitor`；那属于 [REVIEW-ACTIONS.md](REVIEW-ACTIONS.md) 的 P1，不应和协作机制混在一起。
- 不要引入重量级重复检测平台；先用 `rg` + 少量 fitness tests。
- 不要写一个泛泛的"请更架构化"的全局提示；它对具体决策没有约束力。

### 6.4 成功标准

可以用一个很具体的指标衡量：

> 假设明天要新增第 4 种 contract 来源（例如自定义注解），需要修改的文件数从 N 降到 1～2 个，并且 fitness test 会在第二处解析器出现时失败。

如果这个指标成立，说明机制真的起作用了。

---

## 7. 待思考的问题

这些问题没有标准答案，值得结合项目节奏决定：

1. **所有权表放在哪里？** `AGENTS.md` 还是 `docs/CONCEPTS.md`？放两处会有同步成本；只放 `AGENTS.md` 可能不够详细。
2. **fitness test 用正则还是 AST？** 正则便宜但脆；AST 准确但实现成本高。哪些概念值得 AST 级检查？
3. **合并遍用同一个 Agent 还是新 Agent？** 新上下文更客观，但需要重新建立项目上下文；同一 Agent 更省 token，但容易被锚定。
4. **如何度量成功？** "未来变更面"是最直接的指标，但需要假想场景；也可以统计 review 中发现的重复概念数、`TODO(concept)` 的存量。
5. **如何避免过度抽象？** 是否需要一个"新概念需要人工批准"的门槛？还是完全依赖 rule of three 和四个"同"？
6. **`TODO(concept)` 允许存在多久？** 如果不设上限，它会变成新的技术债黑洞。
7. **这套机制是否也适用于测试和文档？** 例如重复的 fixture、重复的说明段落，是否也应该有所有权？
8. **如何与现有 REVIEW-ACTIONS 的优先级配合？** 先修 P0，还是先把协作机制落地，再用它指导 P1 的重构？
9. **跨会话记忆的边界**：除了仓库文档，是否还需要一个 project-scope skill 来承载"怎么做合并"的流程？两者如何分工？
10. **是否需要在 CI 中禁止某些模式？** 例如禁止在 `ContractRegistry` 之外出现 `findAnnotation(..., 'Contract')`；这会不会误伤合法的只读使用？

---

## 附录 A：`.agents/AGENTS.md` 片段草稿

```markdown
## 概念所有权与合并规则

新增行为前，先回答：它回答的是哪个已有问题？

| 问题 | 所有者 |
| --- | --- |
| 一次调用承诺了什么？ | Contract / ContractRegistry |
| 这个值在这里可空吗？ | FlowState / Nullness |
| 注解怎么读？ | AstSupport |
| finding 去哪？ | NullCheckReporter / NullCheckSink |
| 这个包/类默认非空吗？ | PackageScopes |
| 哪些规则族运行？ | NullCheckConfig |

规则：
- 同一问题出现第三个实现时，视为设计缺陷，而不是新功能。
- 新增分支或字符串表前，先搜索该问题的现有所有者。
- 注释里出现 "same as / same effect / equivalent / keep in sync"，
  立即停下来判断是否应该合并。
- 行为变化必须有语料；概念变化必须有 ADR 和所有权表更新。
- 优先扩展数据表，而不是再加一个 if。
- 发现重复但暂不合并时，记录 `TODO(concept: <name>)` 并加入
  `docs/BACKLOG.md` 的 Concept debt 区。
```

## 附录 B：`concept-consolidation` skill 草稿

```markdown
---
name: concept-consolidation
description: 当同一语义在多个位置被重复实现时，识别、建模并合并为一个所有者。
---

# 触发条件

- 新增第 2/3 个相似分支、注解解析或字符串表
- 注释出现 "same as / same effect / equivalent / mirror"
- review 发现同一决定在多处编码
- 计划中预计要修改 3 个以上文件来实现一个"小功能"

# 步骤

1. Inventory
   用 rg 找出所有回答同一问题的位置：注解名、方法名、字符串、数据结构。
   输出 file:line 列表。

2. Classify
   用四个"同"判断：同一不变量、同一生命周期、同一优先级、同一失败语义。
   只有全部满足才合并。

3. Model
   给概念命名，写出：
   - 来源
   - 优先级
   - 不变量
   - 缓存/生命周期
   - 错误策略

4. Migrate
   先在旧调用点后面引入模型，保持行为；
   用现有测试/语料证明等价；再删旧路径。

5. Guard
   加 fitness test、所有权表、ADR；
   让"再次实现"在 CI 里失败。

6. Report
   给出合并前后"未来改一处要动几个文件"的对比。

# 输出模板

- 概念名：
- 现有实现位置：
- 不变量是否一致：
- 合并方案：
- 行为保持证据：
- 新增护栏：
```

## 附录 C：评审 Agent prompt

```text
你是没有参与实现的架构评审者。只读，不改代码。

任务：找出这个 diff 中"同一个问题被回答了多次"的地方。

输出：
1. 概念名
2. 所有实现位置（file:line）
3. 它们的不变量、生命周期、优先级、失败语义是否一致
4. 合并方案
5. 一个能证明行为不变的测试

不要报告风格问题、命名问题或格式问题。
如果不存在重复，明确说"未发现"。
```

## 附录 D：fitness test 伪代码

```groovy
class ArchitectureSpec extends Specification {

    static final File SOURCE_ROOT =
            new File('src/main/groovy')

    def 'contract parsing lives only in ContractRegistry'() {
        given:
        List<File> offenders = groovySources().findAll { File file ->
            file.name != 'ContractRegistry.groovy' &&
            file.text =~ /findAnnotation\([^)]*'Contract'|parseContract|contractFromJetBrains/
        }

        expect:
        offenders.isEmpty()
    }

    private static List<File> groovySources() {
        List<File> files = []
        SOURCE_ROOT.eachFileRecurse { File file ->
            if (file.name.endsWith('.groovy')) {
                files << file
            }
        }
        files
    }
}
```

## 附录 E：commit message 模板

```text
<一句话说明行为变化>

Duplication audit:
- 发现的重复概念：
- 位置：
- 决定：合并 / 暂缓（TODO(concept: X)）

Concept changes:
- 新增/扩展的所有者：
- 是否更新所有权表 / ADR：
- 行为保持证据（语料/测试）：
```

## 附录 F：ADR 骨架

```markdown
# ADR 0001: Contract 统一模型

- Status: accepted
- Date: 2026-10-21
- Context: @Contract 在 3 处解析，@EnsuresNonNullIf/@EnsuresNonNull 各 1 处，
  guard 列表和 neverReturns 各自独立，Map 结果规则硬编码。
- Decision: 一个 Contract 模型 + 一个 ContractRegistry，优先级为
  配置 > 注解 > 内置表。
- Invariants:
  - 结果可空性、分支收窄、正常返回后收窄、永不返回是同一组语义。
  - 配置优先，以便纠正库的错误注解。
  - 格式错误忽略，不猜测。
  - 缓存按 MethodNode，随 compilation 失效。
- Consequences:
  - 新增 contract 来源只需扩展 registry。
  - visitor 只负责应用，不负责解析。
  - 需要测试覆盖优先级和格式错误。
```

---

## 参考

- `0b7dff7` Resolve every contract through one registry
- `0b7dff7^:nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/NullChecker.groovy`（统一前的 2142 行版本）
- `nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/Contract.groovy`
- `nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/ContractRegistry.groovy`
- `nullchecker/src/test/groovy/io/github/heyuch/groovy/nullchecker/ContractRegistrySpec.groovy`
- [REVIEW-ACTIONS.md](REVIEW-ACTIONS.md)
- [`.agents/AGENTS.md`](../.agents/AGENTS.md)
- [NULLNESS-FLOW.md](NULLNESS-FLOW.md)
