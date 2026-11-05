# CFG / Dataflow comparison with the Checker Framework

- Status: analysis, not a commitment to port.
- Source: [Checker Framework Dataflow Manual](https://checkerframework.org/manual/checker-framework-dataflow-manual.pdf)
- Scope: compare the Checker Framework dataflow design with nullchecker's method-local CFG.

## 1. Can we reuse it directly?

No. The Checker Framework implementation is a Java compiler plugin built around javac
`Tree`/`Element`/`Symbol` and its qualifier framework. nullchecker operates on Groovy
`org.codehaus.groovy.ast` nodes inside a Groovy type-checking extension. The two compilers expose
different ASTs, expression identities, annotation models and extension lifecycles.

The useful part is the **design**:

- expression-level nodes and basic blocks;
- explicit conditional transfer results (then/else stores);
- flow rules for short-circuiting;
- exceptional blocks and an exceptional exit;
- a store with join/equality/copy and an `AnalysisResult` for queries.

The Java-specific node set, javac desugaring, `JavaExpression` keys and qualifier hierarchy
should not be copied.

## 2. What the Checker Framework does

### 2.1 CFG shape

The manual defines basic blocks with four roles:

- regular blocks with exactly one successor;
- entry, normal exit and **exceptional exit** special blocks;
- exception blocks containing a potentially throwing operation, with exceptional successors;
- conditional blocks with exactly one predecessor and two successors.

Edges are regular, conditional (true/false) or exceptional (labeled by exception type).
See "The Control-Flow Graph", especially 3.1 and 3.2.12 of the manual.

### 2.2 Nodes and evaluation order

Nodes represent minimal operations (literals, variable reads, field/array access, calls,
binary/unary operations, conversions, returns). Expression evaluation order is explicit in the
graph; control-flow constructs are translated to blocks and edges rather than represented as a
single statement node.

### 2.3 Conditional stores and flow rules

A boolean-valued expression produces a `ConditionalTransferResult` with **then** and **else**
stores. The framework has five propagation rules:

- `EACH_TO_EACH`;
- `THEN_TO_BOTH` / `ELSE_TO_BOTH`;
- `THEN_TO_THEN` / `ELSE_TO_ELSE` for short-circuit `||` / `&&`.

This preserves facts from the branch just taken instead of immediately taking the least upper
bound. See manual sections 3.2.4-3.2.6 and 4.4.

### 2.4 Store and analysis result

A `Store` maps expressions (`JavaExpression`) to abstract values, with least-upper-bound,
equality and copy operations. Only stores at key points (typically block starts) are retained;
the framework recomputes intermediate points on demand. `AnalysisResult` maps nodes/trees to
values and lets clients query the store before/after a tree. See manual 2.2.7, 2.2.10 and 4.1.

### 2.5 Other properties

- forward **and backward** analyses;
- exceptional edges and an exceptional exit;
- finally copies for abrupt exits (`return`/`break`/`continue`);
- an optional concurrent-semantics mode;
- immutable CFG after construction, plus DOT/string visualization.

## 3. What nullchecker currently does

`ControlFlowGraph` is deliberately **statement-level**:

- nodes are statements; entry and a single exit node are special;
- edges are normal, true, false or exception;
- `if`/`while`/`for`/`switch` are statement nodes whose outgoing edges carry facts;
- `try` adds catch edges from the try node; there is no per-operation exception block and no
  exceptional exit;
- `throw`/`return` connect to the same `exit`;
- `FlowAnalysis` is a forward worklist over `FlowState`;
- `NullnessFlowTransfer` evaluates one statement by calling the visitor and stores
  `GuardFacts` per condition node; `refine` applies true/false facts on the edge;
- `FlowState` is a fixed struct: locals, finite paths, non-empty facts, lookup facts;
- no `AnalysisResult`, tree-to-node index, basic blocks or visualization;
- no backward analysis; nullness does not need one today.

The statement-level choice is a reasonable budget decision: it keeps the CFG small and lets the
existing visitor own Groovy-specific rules. It is not a full expression-level dataflow model.

## 4. Gap matrix

| Area | Checker Framework | nullchecker | Impact |
| --- | --- | --- | --- |
| Granularity | operation nodes + basic blocks | statement nodes | less precise evaluation order; much smaller graph |
| Conditional state | then/else stores + five flow rules | `GuardFacts` + true/false edge facts | nested short-circuit/ternary/switch can lose precision |
| Exception flow | exception blocks/edges + exceptional exit | catch edges from try node only; one exit | catch state is conservative; finally/throw paths incomplete |
| Abrupt finally | duplicated finally for jumps | not special-cased | documented F3 limitation |
| Store | expression -> abstract value, LUB/copy/equality | fixed `FlowState` fields | sufficient for nullness, not reusable for other analyses |
| Analysis result | node/tree values and stores | no query API | harder to debug/test post-hoc |
| Fixed point | worklist to convergence | worklist with 10000-step cap; visitor loop cap 8 | cap is now diagnosed, but still a hard cutoff |
| Direction | forward/backward | forward only | backward not needed for nullness |
| Concurrency | optional mode | sequential | out of current scope |
| Visualization | DOT/string CFG | tests only | debugging cost |

## 5. Improvement options, in priority order

### D1. Conditional flow rules (precision, medium effort)

The current `GuardFacts` already models many conditions and we fixed path/non-empty/lookup
inheritance for `&&`/`||`. The remaining gap is that conditional state is attached to a
statement node rather than represented as then/else states at subexpression level.

Incremental option:

- keep the statement-level CFG for now;
- represent a condition's true/false state explicitly and make edge refinement use the
  appropriate rule;
- cover nested `&&`/`||`, ternary, assert and switch case selection with corpus fixtures.

Do not start with a full expression-level CFG; that would rewrite the visitor and is not
justified by current findings.

### D2. Exceptional CFG (correctness, higher effort)

Add an explicit exceptional exit and model exceptional edges from potentially throwing
operations instead of only adding a catch edge from the try node. The largest concrete gaps:

- `throw` inside a `try` body currently connects to the normal `exit`, not to `catch`/`finally`;
- catch state starts from the pre-try state, losing facts from the try body;
- `return`/`break`/`continue` through `finally` are not modeled.

This is the highest-value correctness item, but it is a CFG-shaped change and needs its own
corpus and regression work. It should not be mixed with rule tuning.

### D3. Fixed-point discipline (robustness, low effort)

`H0-2` now makes worklist-budget exhaustion visible, which removes the silent-clean failure.
The remaining question is whether the 8-iteration visitor loop and the 10000-step worklist cap
are artificial. Prefer convergence with a global safety budget and a diagnostic, rather than a
low fixed loop cap.

### D4. Analysis result and diagnostics (maintainability, optional)

A small `AnalysisResult`-like view (node -> in/out state, and a tree/node lookup) would make
regression tests and CFG debugging easier. It is not required for current checking and should
not be a prerequisite for D1/D2.

### D5. Basic blocks / expression nodes (not now)

These improve reuse and granularity, but are a substantial rewrite. The current statement-level
CFG is adequate for nullness rules; revisit only if D1/D2 show a concrete need.

## 6. What not to copy

- javac `Tree`/`Element`/`Symbol` APIs and Java desugaring;
- `JavaExpression` as the universal store key (Groovy properties/dynamic calls differ);
- the Checker Framework qualifier hierarchy and `AnnotatedTypeMirror`;
- backward analysis and concurrent semantics;
- full CFG immutability/visualization infrastructure before there is a consumer.

## 7. Recommendation

Keep the current statement-level CFG and the visitor's Groovy-specific rules. Borrow two
ideas, in this order:

1. **exceptional control flow** (D2) because it affects correctness around `try`/`catch`/`finally`;
2. **explicit conditional states / flow rules** (D1) because it improves precision without a
   full CFG rewrite.

D3 is a useful robustness cleanup. D4/D5 wait for a concrete debugging or performance need.

These are tracked as D1-D5 in ROADMAP section 6.6; they remain behind the H0/H1 correctness and
lifecycle work.
