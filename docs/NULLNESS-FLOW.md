# 方法内空值流分析：现状、CFG 方案与落地计划

- Status: Phase 0 + F1 implemented (strict-only branch map/join); F2-F7 tracked in [ROADMAP.md](ROADMAP.md)
- Scope: 先在 `strict` 落地的方内 nullness 分析；默认注解模式可逐步演进，但不再要求上游一致
- Baseline: 0.5.0 工作树（`NullCheckVisitor` 当前实现）

本文只讨论 **nullchecker 自己的方法内空值追踪**。这里的“CFG”指方法内控制流图，
“数据流”指 nullness 格上的前向数据流分析（unknown-suppressing，见 5.3）；**不是** k-CFA / CFA2 / PDCFA 那类跨过程、
上下文敏感的高阶控制流分析。

---

## 1. 结论

- **Phase 0（`FlowState` + 分支 join）已批准为下一步实现。** 它修的是当前已知的
  分支顺序依赖和 early exit 误报/漏报，不需要完整 CFG。
- **Phase 1（方法内 CFG）和 Phase 2（access path/闭包）是剩余路线图**，按
  [ROADMAP.md](ROADMAP.md) 的 F1-F7 顺序推进到方法内边界；不再等待额外证据。
- **不适合做 k-CFA / CFA2 这类跨过程高阶分析。** 对 Groovy 的动态分派、闭包逃逸和
  编译器插件的延迟预算来说，复杂度和收益不成比例。
- 当前实现已经是一个**手写的、结构化的流分析雏形**：`GuardFacts` 负责条件，
  `nullableVars` 负责“可能为空”，`applyFacts` 处理 early exit。它缺的不是“更高级的
  CFA”，而是**分支汇合时的状态合并（join）**。
- 与上游的一致性不再是发布门禁；`dualRun` 只做信息性对照。演变方向是比上游更准确，
  而不是逐字复刻。

---

## 2. 范围与非目标

### 2.1 范围

- 方法体内的局部变量 nullness 追踪；
- `if` / `else` / 三元 / `&&` / `||` / `assert` 的条件细化；
- `while` / `do-while` / `for-in` 的循环状态；
- `break` / `continue` / `return` / `throw` 的路径终止；
- `try` / `catch` / `finally` 的异常边和汇合；
- `this.field` 与有限深度的 access path（后续阶段）；
- 闭包的捕获变量与调用效果（后续阶段，保守处理）；
- 方法调用的返回值/参数 nullness 通过现有 `ContractRegistry` 和注解获取；
- 诊断的置信度与去重。

### 2.2 非目标

- k-CFA / CFA2 / PDCFA / 全程序类型推导；
- 完整别名分析、堆抽象、线程间分析；
- 对 Groovy 动态分派（`methodMissing`、`propertyMissing`、`invokeMethod`）做精确解析；
- 宣称 soundness；
- 重写所有 nullness 规则，或替换现有注解/contract 体系。

默认模式是主要兼容基线，但不再把逐字上游一致当作发布门禁；行为变化必须有 corpus
证据并在 `PORT.md` / `CHANGELOG.md` 记录。新的流分析先在 `strict` 落地，避免一次改变
默认模式的全部行为。

---

## 3. 当前实现

### 3.1 状态与条件

相关文件：

- [`NullCheckVisitor.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/NullCheckVisitor.groovy)
- [`GuardFacts.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/GuardFacts.groovy)
- [`ContractRegistry.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/ContractRegistry.groovy)

当前 per-method 状态主要是：

| 状态 | 作用 |
| --- | --- |
| `nullableVars` | 流分析认为可能为空的变量（第 99 行） |
| `guardedVars` | 当前分支上已证明非空的变量 |
| `nonEmptyVars` | 当前已知非空的集合，用于 `max`/`min` |
| `lookupGuards` | `containsKey(k)` 证明存在的 key |
| `nullableElements` | 集合元素类型为 `@Nullable` 的循环/闭包变量 |
| `entryVariables` | `Map.Entry` 的 key/value 可空性 |

`GuardFacts` 把条件拆成 `whenTrue` / `whenFalse`，并处理了：

- `x != null` / `x == null`；
- `x instanceof T` / `x !instanceof T`；
- Groovy truth：`if (x)` 的 true 分支推出非空；
- `&&` / `||` 的短路；
- `Objects.nonNull` / `isNull`、`containsKey`、`isNotNullOrEmpty` 等契约；
- `@EnsuresNonNullIf` 和 `assert`。

这部分是当前实现里最值得保留的资产：**条件识别已经做得不错，问题在状态管理。**

### 3.2 控制流

- `visitIfElse`（第 425 行）：先访问 `ifBlock`，再访问 `elseBlock`；
- `visitWhileLoop`（第 449 行）：只在循环体上应用 true 条件事实；
- `visitBlockStatement`（第 403 行）：遇到 `cannotCompleteNormally` 就停止访问后续语句；
- `visitClosureExpression`（第 262 行）：保存/恢复 `nullableElements` 和 `entryVariables`，
  但**不保存/恢复 `nullableVars`**；
- `visitReturnStatement`：检查返回值；
- `applyFacts`（第 810 行）：在 early exit 后把 guard 事实写回全局状态；
- `cannotCompleteNormally`（第 1222 行）：语法层面的“是否能正常结束”判断。

### 3.3 已经做对的部分

- 条件级路径敏感（`GuardFacts`）；
- `&&` / `||` 的短路事实；
- early exit 的意图（`if (x == null) return` 之后 x 非空）；
- `break` / `continue` 被当作路径退出（PORT.md §2.6 的行为修复）；
- 注解、contract、内置表、`@EnsuresNonNullIf` 的统一入口；
- 类型层面的元素/Map.Entry 可空性；
- corpus 门禁和 `dualRun` 对比机制。

### 3.4 关键缺口

**没有 join。** `nullableVars` 是一个可变的 `Set`，`visitIfElse` 顺序访问两个分支，
`withGuards` 只保存/恢复 `guardedVars`，不保护 `nullableVars`。因此：

- `if` 之后的状态是“最后访问的那个分支留下的状态”；
- early exit 的 `applyFacts` 直接改同一个集合，退出分支的赋值会残留；
- `while` 没有循环固定点，循环体只按一次状态访问；
- `try` / `catch` / `finally` 没有独立的状态合并；
- 闭包没有捕获变量的读写摘要；
- access path 完全没有进入状态模型。

这不是“精度差一点”，而是会同时产生误报和漏报。下面是可以复现的形态。

---

## 4. 已复现的失败形态

以下结果是在 0.5.0 工作树的已编译 CLI 上，用 `--strict` 跑临时样例得到的；
这些样例不是 corpus 门禁的一部分，只用于说明状态合并的必要性。

### 4.1 分支顺序依赖

```groovy
static void a(boolean c) {
    String x = null
    if (c) { x = 'a' } else { x = null }
    x.length()          // 期望：warn；实际：warn
}

static void b(boolean c) {
    String x = null
    if (c) { x = null } else { x = 'a' }
    x.length()          // 期望：warn；实际：不报（FN）
}

static void noElse(boolean c) {
    String x = null
    if (c) { x = 'a' }
    x.length()          // 期望：warn；实际：不报（FN）
}
```

`b` 和 `noElse` 都有一条路径让 `x` 为 null，但当前状态在访问完最后一个分支后被
“覆盖”了。`a` 之所以报，是因为最后一个分支恰好赋了 null，而不是因为做了 join。

### 4.2 early exit 同时产生 FP 和 FN

```groovy
static void safe(boolean c) {
    String x = 'a'
    if (c) { x = null; return }
    x.length()          // 期望：clean；实际：误报（FP）
}

static void unsafe(boolean c) {
    String x = null
    if (c) { x = 'a'; return }
    x.length()          // 期望：warn；实际：不报（FN）
}
```

`safe` 在 `c == true` 时已经 return，fall-through 路径上 `x` 仍是 `'a'`；当前代码却把
`x = null` 留在了 `nullableVars` 里。`unsafe` 则相反：`x = 'a'` 把 `nullableVars` 清掉，
但 `c == false` 的路径上 `x` 仍是 null。两个方向都错，说明仅靠“访问顺序 + 事后
`applyFacts`”无法表达分支的 must/may 语义。

### 4.3 根因

- `nullableVars` 是单个可变集合（第 99 行）；
- `visitIfElse` 没有 `copy` / `join`（第 425 行）；
- `withGuards` 只保存 `guardedVars`（第 798 行），不保存 `nullableVars`；
- `applyFacts` 在 early exit 时改的是同一个集合（第 810 行），没有排除已退出分支；
- `cannotCompleteNormally` 是语法近似，不能替代 CFG 的 exit edge。

---

## 5. 目标模型

### 5.1 边界：方法内，不跨过程

- 分析单元：一个 `MethodNode` 或一个 `ClosureExpression`。
- 方法调用的返回 nullness、参数要求、guard 关系：查现有 `ContractRegistry`、注解和内置表。
- 不做跨方法固定点，不做上下文敏感的调用串。
- 需要更多跨过程信息时，优先加 **contract / summary**，而不是加 CFA 上下文。

### 5.2 `FlowState`

建议把当前分散的状态收进一个对象，作为方法内唯一的状态所有者：

```groovy
class FlowState {
    Map<Variable, Nullness> locals
    Set<Variable> nonEmpty
    Set<String> presentLookups
    boolean unreachable

    FlowState copy()
    FlowState join(FlowState other)
    boolean equalsForFixpoint(FlowState other)
}
```

要点：

- `nullableVars` 和 `guardedVars` 合并为 `locals: Map<Variable, Nullness>`；
  一个变量在某个程序点只有一个 nullness 值。
- `nonEmpty` 和 `presentLookups` 是 **must 事实**：join 时取交集。
- `nullableElements` / `entryVariables` 是类型层面的事实，不放进每个程序点的状态；
  它们继续由现有的元素/Entry 逻辑处理。
- `unreachable` 用于 `return` / `throw` / `break` / `continue` 之后的路径。
- `join` 必须定义“变量只出现在一条分支”的语义：缺失一侧按 `UNKNOWN` 处理（局部变量、
  参数和字段的初始值分别定义），不能直接 `putAll` 或取交集。
- 所有规则检查（解引用、返回、传参、赋值）都从同一个 `FlowState` 读值，不再各自
  判断 `nullableVars` / `guardedVars`。
- Phase 0 应把新状态隔离在 strict 路径；默认 annotation 路径先保持旧实现，避免一次
  重写 1200 多行 visitor 的所有行为。

### 5.3 Nullness 格

```text
        UNKNOWN
           |
      MAYBE_NULL
       /      \
    NULL    NON_NULL
       \      /
      UNREACHABLE
```

| 值 | 含义 | 解引用时 |
| --- | --- | --- |
| `NON_NULL` | 确定非空 | 安全 |
| `NULL` | 确定为空 | 报“必定为空” |
| `MAYBE_NULL` | 某些路径可能为空 | 报“可能为空” |
| `UNKNOWN` | 分析不知道 | 默认不报；可作为低置信度选项 |
| `UNREACHABLE` | 该路径已退出 | 跳过 |

Join 规则：

```text
UNREACHABLE ⊔ x = x
UNKNOWN     ⊔ UNKNOWN = UNKNOWN
UNKNOWN     ⊔ NON_NULL = UNKNOWN
UNKNOWN     ⊔ NULL = MAYBE_NULL
UNKNOWN     ⊔ MAYBE_NULL = MAYBE_NULL
MAYBE_NULL  ⊔ NULL = MAYBE_NULL
MAYBE_NULL  ⊔ NON_NULL = MAYBE_NULL
NULL        ⊔ NON_NULL = MAYBE_NULL
NULL        ⊔ NULL = NULL
NON_NULL    ⊔ NON_NULL = NON_NULL
```

注意：

- 这是 **unknown-suppressing 的 must/may 混合策略**，不是纯 may 分析：单独的
  `UNKNOWN` 不报告（控制误报）；但只要有一条路径确定为空，`UNKNOWN ⊔ NULL` 就变成
  `MAYBE_NULL` 并报告，避免把“某条路径确定为空”静默丢掉。
- 这个 join 不是教科书上的格；实现时必须用单元测试固定结合律/交换律/幂等性，
  不能靠直觉扩展。
- 如果以后要区分“未知”与“可能为空”的置信度，应给 `UNKNOWN` 加来源，而不是直接
  把它当 `MAYBE_NULL`。
- `if (x)` 的 false 分支**不能**推出 `NULL`：Groovy truth 还包含 `false`、`0`、空串、
  空集合。false 分支最多是 `UNKNOWN`。
- `?.` 的结果不因为接收者非空就一定非空；`a?.b.c` 里的 `.c` 仍可能为空。

### 5.4 CFG

#### 节点

语句级基本块即可；表达式只在条件/短路场景需要细化：

- `Block`：顺序边；
- `If`：条件节点，true/false 两条边；
- `While` / `DoWhile` / `For`：header、body、back edge、exit；
- `Break` / `Continue`：到循环 exit/header 的边；
- `Return` / `Throw`：到方法 exit 的边；
- `Try`：try 正常边、异常边到 catch、finally 边；
- `Switch`：case/default 边、fall-through、break；
- `Assert`：true 边 fall-through，false 边 throw；
- `Synchronized`：body 顺序边。

#### 数据流

- 入口状态：形参/字段按注解初始化；
- worklist：`in[node]` / `out[node]`，沿着 CFG 边传播；
- join：所有前驱的 `in` 做 `FlowState.join`；
- 终止：nullness 格高度有限，单调 transfer 下 worklist 收敛；
- 循环：back edge 自然形成固定点，不需要手写“分析两遍”；
- 不可达节点：不报告。

#### 条件细化

条件可以继续复用 `GuardFacts`，只是把结果应用到 `FlowState` 的副本上，而不是维护
一个独立的 `guardedVars` 栈：

```groovy
FlowState trueIn  = state.with(facts.whenTrue)
FlowState falseIn = state.with(facts.whenFalse)
```

`&&` / `||` 的右操作数要在左操作数的 true/false 事实上分析；这可以做成表达式级
CFG，也可以保留当前递归处理，只要结果进入 `FlowState`。

### 5.5 Transfer function

| 语句/表达式 | 结果 |
| --- | --- |
| `x = null` | `NULL` |
| `x = new ...` / 非空字面量 / 非空 cast | `NON_NULL` |
| `x = nullableCall()` | `MAYBE_NULL`（按 contract/注解） |
| `x = unknownCall()` / 动态调用 | `UNKNOWN` |
| `x = y` | 复制 `y` 的 nullness |
| `x = c ? a : b` | 先求 `a` / `b` 的 nullness，再 `join` |
| `x = a ?: b` | 先求 `b` 与 `a` 非空分支的 nullness，再 `join`；结果可能为空 |
| `x += y` / `x++` | 默认 `UNKNOWN`，除非有明确规则 |
| `if (x != null)` | true `NON_NULL`，false `NULL` |
| `if (x == null)` | true `NULL`，false `NON_NULL` |
| `if (x)` | true `NON_NULL`，false `UNKNOWN` |
| `if (!x)` | true `UNKNOWN`，false `NON_NULL` |
| `x instanceof T` | true `NON_NULL` |
| `Objects.nonNull(x)` | true `NON_NULL`，false `NULL` |
| `Objects.requireNonNull(x)` | 调用后 `x = NON_NULL` |
| `containsKey(k)` | true `presentLookups += k`（仅当 value 类型不可空时用于压制 `get` 的可空结果）；false 不细化 |
| `isNotNullOrEmpty(x)` | true `NON_NULL` + `nonEmpty += x`；false 不细化 |
| `max` / `min` receiver | 在 `nonEmpty` 中则 `NON_NULL`；否则 `MAYBE_NULL` |
| `find` / `findResult` | `MAYBE_NULL`，集合非空也不能收窄（谓词可能不命中） |
| `first` / `head` | `NON_NULL`（空集合抛异常）；元素类型可空时仍按元素类型 |
| `any` / `every` | boolean，不参与 nullness |
| `return` / `throw` | `unreachable = true` |
| 方法调用 | 先用 contract 检查 receiver/参数，再按 contract 设置返回值；必要时 invalidate 字段路径 |

### 5.6 Join 的 may / must

这是最容易写错的地方：

- **nullness 是 may**：任一前驱可能为空，结果就是 `MAYBE_NULL`；
- **nonEmpty 是 must**：只有所有前驱都非空，结果才保留；
- **presentLookups 是 must**：只有所有前驱都证明 key 存在，结果才保留；
- **unreachable 前驱不参与 join**：已经 `return` / `throw` 的路径不能把状态“带回来”。

不要用同一个 `addAll` 处理所有集合。当前代码的 `withGuards` 只处理 `guardedVars`，
而 `nullableVars` 完全没参与，就是这种“不同 fact 不同语义”没有被建模的表现。

### 5.7 Access path

在局部变量稳定后，再引入有限 access path：

```text
x
this.f
x.f
x.f.g          // 深度上限 2，最多到第二层
```

规则：

- `x = ...`：kill 所有以 `x` 为根的路径；
- `x.f = ...`：设置 `x.f`，kill `x.f.*` 的更深路径；
- `x.f()` / 未知方法调用：保守地 kill `this.*`，以及可能逃逸的局部变量的字段路径；
- `x` 被传给未知方法：认为 `x.*` 可能被修改；
- 字段声明为 `@Nullable`：初始 `MAYBE_NULL`；但方法调用后不能无条件相信之前的流事实；
- 别名：`a = b` 时简单复制事实是**不健全**的；先接受这个限制，在文档和测试里写清楚，
  不要用“全别名分析”来解决。

第一阶段只做局部变量；`this.f` 和 `a.b` 放在第二阶段，避免一开始就掉进别名/堆抽象的坑。

### 5.8 闭包

闭包不要当成普通表达式内联；把它当作独立的分析单元：

- 每个 closure 有自己的 CFG；
- 捕获变量作为“参数/返回值”处理：读入当前状态，写出修改后的状态；
- 已知调用点（`each`、`collect`、直接 `call()`）可以用调用点状态分析一次；
- 调用可能执行 0 次或多次：after 状态要 join “未执行”和“执行后”；
- 闭包逃逸到未知方法：保守地把捕获的可变变量标成 `UNKNOWN`，或在 strict 下报
  “无法确认”；
- closure 内的 `return` 只退出 closure，不退出外层方法；CFG builder 需要这个上下文；
- `break` / `continue` 不能跨 closure 作用于外层循环；不要错误地连边。

### 5.9 诊断

- 区分 “definitely null” 和 “may be null”；keep 现有 `Potential null dereference`
  前缀以兼容 corpus 和下游分类。
- 每个 finding 带 reason，例如：
  - `declared @Nullable`；
  - `assigned null at line N`；
  - `may return null from 'get'`；
  - `safe-navigation chain result may be null`。
- 去重：同一 `(file, start, end, message)` 只报一次；条件分析可能重复访问表达式，
  用 finding set 兜底。
- `UNREACHABLE` 不报。
- 不改变 `failOn` 的语义；新的诊断仍归入现有 `null` 类别。若新增消息文本，检查
  `NullCheckFinding.NULL_FRAGMENTS` 与 `NullCheckHarness.categoryOf` 是否都需要更新。

---

## 6. 分阶段落地

### Phase 0：`FlowState` + 分支 join（最高 ROI）

**决定：Phase 0 已实施；Phase 1/2 按 [ROADMAP.md](ROADMAP.md) 继续推进。**

目标：修掉第 4 节的顺序依赖和 early exit 问题，不先写完整 CFG。

任务：

1. 新增 `FlowState`；Phase 0 先快照现有 `nullableVars` / `guardedVars` / `nonEmptyVars` /
   `lookupGuards`，只在 strict 路径启用；per-variable `Nullness` map 留给 Phase 1。
2. `visitIfElse`：
   - `before = flow.copy()`；
   - 用 `before` 复制出 then/else 两个状态；
   - 分别访问两个分支；
   - 若分支 `unreachable`，不参与 join；
   - 否则 `flow = thenOut.join(elseOut)`；
   - 没有 else 时，elseOut 就是 `before`。
3. `visitTernaryExpression`、`visitAssertStatement` 同样用副本和 join/refine。
4. early exit 不再 `applyFacts` 改全局集合；退出分支标记 `unreachable`，只让另一条
   路径参与 join。
5. `visitWhileLoop` 至少做到：循环体状态从入口复制，循环后状态 join “零次迭代”和
   “执行后”；真正的固定点留给 Phase 1。
6. `withGuards` 改成 `state.with(facts)`，不再维护独立的 `guardedVars` 栈。

验收：

- 第 4 节 5 个用例全部符合期望；
- 默认 annotation 模式的 corpus 零变化（不是要求 strict 零变化）；
- `FlowState.join` 的 union/intersection 和 copy 独立性有独立单元测试；
- `strict` 模式只新增预期的 finding；
- `FlowState.join` 有单元测试，覆盖 NULL/NON_NULL/MAYBE/UNKNOWN/UNREACHABLE。

### Phase 1：显式 CFG

状态：已批准，按 ROADMAP F1-F4 推进；不再以“是否有 3 个案例”为启动条件。性能和
corpus 验收仍然保留。

任务：

1. 构建语句级 CFG（`CfgNode` / `CfgEdge`），支持 if/loop/try/switch/break/continue/
   return/throw。
2. worklist 数据流，`in`/`out` 状态，join 所有前驱。
3. 循环 back edge 形成固定点；`break` 连到 loop exit；`continue` 连到 header。
4. `try`：try 正常边、异常边到 catch、finally 在所有出口执行；catch 参数初始
   `NON_NULL`。
5. 删除 `cannotCompleteNormally` 在流分析中的角色；它只保留给“语法上不可达”的快速
   判断，或直接由 CFG 可达性替代。
6. 诊断从“访问时立即报”改为“按 CFG 节点收集 + 去重 + 排序”，避免重复访问同一表达式
   产生重复 finding。

验收：

- 循环零次/多次、break/continue、try/catch/finally 的 corpus 全部通过；
- 默认模式无变化；
- 编译时间在可接受预算内（记录 baseline）。

### Phase 2：access path 与闭包

状态：Phase 1 完成后按 ROADMAP F5-F6 推进；仍在方法内边界，不做别名/堆抽象。

任务：

1. `AccessPath`（根变量 + 字段链，深度 ≤ 2）。
2. 赋值/方法调用/逃逸时的 kill 规则。
3. `this.f` 的流事实与声明注解合并；调用后 invalidate。
4. 闭包独立 CFG、捕获变量摘要、已知调用点/逃逸处理。
5. 元素/Map.Entry 的现有类型事实保持不变，不要重复建模。

验收：

- `if (this.f != null) { this.f.x() }` 等常见形态；
- 闭包 `each` 零次/多次、捕获变量写入；
- 文档明确写清别名和字段失效的保守边界。

### Phase 3：摘要与度量（可选）

- 只为项目内私有方法做轻量 summary（返回 nullness、参数是否被解引用）；
- 不做 k-CFA；需要更多上下文时优先加 contract；
- 用真实项目度量 FP/FN、编译时间、finding 数量，决定是否继续。

---

## 7. Groovy 语义清单

| 语义 | 对 nullness 的影响 |
| --- | --- |
| **Groovy truth** | `if (x)` 为 true ⇒ 非空；为 false 不 ⇒ 为空（false/0/空串/空集合） |
| **`?.`** | 接收者为空则整个表达式为空；接收者非空时结果仍可能为空；链式 `a?.b.c` 的 `.c` 仍可能 NPE |
| **`?:`** | `a ?: b` 的结果可能为空，取决于 `b`；不要假设非空 |
| **GString `${x}`** | null 渲染为 `"null"`，不会 NPE；不要报解引用 |
| **闭包** | `each` 可能执行 0 次或多次；捕获变量可能被修改；逃逸后不能假设捕获值不变 |
| **try/catch** | 任意语句都可能抛异常；catch 后状态要与 try 正常路径 join；finally 在所有出口执行 |
| **动态分派** | `methodMissing` / `propertyMissing` / `invokeMethod` ⇒ `UNKNOWN`；不要硬猜 |
| **字段** | `this.f` 可能被其他方法/回调修改（线程安全不在范围内）；方法调用后保守 invalidate |
| **switch / for-in** | `case null`、fall-through、元素可空；用 CFG 处理 |
| **`@CompileStatic` vs 动态** | 只有静态类型可靠；strict 分析未注解代码时 `UNKNOWN` 会更多 |

---

## 8. 项目约束与集成

### 8.1 与上游的关系

本仓库是 Groovy 6 `NullChecker` 的 fork。**上游一致性不再是发布门禁**：项目方向是更准确
的 nullchecker，允许在默认模式和 strict 模式逐步偏离。

因此：

- `dualRun` 是信息性对照，不再因 fixture 差异失败；只保留“参考侧必须真的产出结果”的
  sanity check。
- 不再维护“每个差异必须登记”的 expected list；差异进入 `diff.txt` 供人判断。
- 默认模式可以改进，但每次变化要有 corpus/外部 trial 证据，并写入 `PORT.md` /
  `CHANGELOG.md`。
- 向上游提交仍然可选；如果提交，默认模式的行为需要单独讨论，不再是本项目的硬约束。
- Phase 0 仍只在 strict 路径启用，目的是控制风险，不是兼容性要求。

### 8.1.1 实现说明（Phase 0）

Phase 0 先用快照修分支顺序问题；F1 已把分支快照升级为 per-variable `Nullness` map
（`FlowState` / `Nullness` / `FlowStateSpec`），visitor 在分支边界仍将其投影到旧的
`nullableVars` / `guardedVars` 集合。把 map 变成 visitor 的原生状态与完整 CFG 一起在 F2
落地；§4 的 5 个用例已经覆盖。

### 8.2 corpus

- 新行为必须有阳性/阴性对照；
- 建议新增 `corpus/strict/flow_*.groovy`：
  - branch order 两个方向；
  - no-else；
  - early exit safe/unsafe；
  - loop zero/多轮/break/continue；
  - try/catch/finally；
  - closure capture；
  - `?.` / `?:` / GString；
  - access path。
- 默认模式 corpus 不得变化；strict 模式新增的 finding 用 expected 文件锁定。

### 8.3 性能

- 方法内 CFG 是线性的；worklist 在有限格上收敛，开销可控；
- 真正的风险是跨过程摘要和 access path 深度；先限制深度，再测量；
- 记录 baseline：单文件/单方法分析时间、整个 corpus 时间、Gradle `check` 时间。

### 8.4 诊断与 CLI

- `--strict` 已存在，作为入口，不新增默认行为；
- 如需区分“稳定版 strict”和“实验性 CFG”，再加 `--flow=cfg` 之类的选项；
- 不改变 `failOn` 语义；新 finding 仍归入 `null` 类别；
- 更新 `NullCheckFinding` / `NullCheckHarness.categoryOf` 时注意两者重复分类的
  backlog 项。

---

## 9. 测试与评估

### 9.1 必测形态

- 分支：then/else 都赋 null；只有一个分支赋 null；没有 else；两个方向；
- early exit：`return` / `throw` / `break` / `continue`；
- 循环：零次迭代、循环体赋 null、循环携带、`break` 带出状态；
- try：try 内赋值、catch 内 return、finally 覆盖、异常路径；
- 闭包：`each` 零次/多次、捕获变量写、闭包逃逸；
- 条件：`&&` / `||` / `!` / `instanceof` / truthiness / `assert`；
- 表达式：`?.`、`?:`、GString、`Map.get` / subscript；
- access path：`this.f`、`a.b`、调用后的 invalidate。

### 9.2 评估指标

- corpus 阳性/阴性是否全部符合；
- 真实项目：finding 数量、人工 triage 的 FP 率、已知 NPE 的 FN 率；
- 编译时间：单文件、整项目；
- 与默认模式的差异是否全部可解释；
- `dualRun` 的差异是否都能解释；它是信息性对照，不作为门禁。

### 9.3 回归命令

```bash
./gradlew check
./gradlew dualRun
./gradlew :nullchecker-gradle-plugin:functionalTest
```

---

## 10. 开放问题

1. `UNKNOWN` 是否要在 strict 下报“无法确认”？当前采用 unknown-suppressing：
   单独 `UNKNOWN` 不报，`NULL ⊔ UNKNOWN` 报。该策略需要单元测试固定。
2. `this.field` 的流事实在哪些方法调用后失效？保守做法是任何非纯调用都失效。
3. 闭包逃逸时，捕获变量是标 `UNKNOWN` 还是 `MAYBE_NULL`？取决于误报容忍度。
4. Phase 0 是否直接作为最终方案？已决定继续推进到方法内 CFG；实现顺序见 ROADMAP。
5. 是否向上游提交？提交是可选动作，不再要求默认模式行为完全不变。
6. access path 的深度上限和别名策略需要一组真实代码来校准。

---

## 11. 参考

项目内：

- [`NullCheckVisitor.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/NullCheckVisitor.groovy)
- [`GuardFacts.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/GuardFacts.groovy)
- [`ContractRegistry.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/ContractRegistry.groovy)
- [`NullChecker.groovy`](../nullchecker/src/main/groovy/io/github/heyuch/groovy/nullchecker/NullChecker.groovy)
- [`docs/BACKLOG.md`](BACKLOG.md)（access path 属于 CFG work）
- [`PORT.md`](../PORT.md)（port 偏差与行为修复）

外部实现思路（不是本项目的移植目标）：

- [Checker Framework Nullness Checker](https://checkerframework.org/manual/#nullness-checker)
- [NullAway](https://github.com/uber/NullAway)
- [Infer Nullsafe](https://fbinfer.com/docs/checker-nullsafe/)
