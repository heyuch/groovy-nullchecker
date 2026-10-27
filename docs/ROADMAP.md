# Roadmap

Status: active. Last updated: 2026-10-21.

This is the execution order for the remaining work. It is intentionally ordered: finish the
method-local nullness flow work, then the plugin and the smaller items, then release.

## Scope boundary

Complete the **method-local** flow analysis described in [NULLNESS-FLOW.md](NULLNESS-FLOW.md):
locals, branches, loops, `try/catch/finally`, `switch`, break/continue, finite access paths and
closures.

Stop at the boundary. Explicit non-goals remain:

- k-CFA / CFA2 / PDCFA and interprocedural fixed points;
- full alias analysis, heap abstraction and thread analysis;
- precise resolution of dynamic dispatch (`methodMissing`, `propertyMissing`, `invokeMethod`);
- soundness claims.

Interprocedural information stays at the current contract/annotation boundary. A method summary
is only reconsidered after the method-local work is complete and a real project needs it.

## Current state

- Phase 0 is implemented: strict-mode branch snapshots, may-null union, must-fact intersection,
  early-exit branch exclusion (`FlowState`, `FlowStateSpec`, `strict/flow_branch_merge.groovy`).
- Phase 0 is deliberately a snapshot over the existing sets, not yet the per-variable
  `Nullness` map described in NULLNESS-FLOW.
- `dualRun` is informational; upstream parity is not a release gate.
- The latest published release is `0.8.0`.

## Order

### 1. Flow analysis (NULLNESS-FLOW)

#### F1. `FlowState` v2: per-variable nullness

Status: done.

- The branch snapshot is now `Map<Variable, Nullness>` plus `nonEmpty` and `presentLookups`
  must-facts.
- Join implements the unknown-suppressing policy, including variables present on only one
  branch.
- The visitor still projects the map to the legacy sets at branch boundaries; making the map
  the native visitor state is folded into F2, where the CFG needs it directly.
- Unit tests cover each join pair, absent variables, copy independence and Phase 0 corpus.

Acceptance: `FlowStateSpec` covers the table; Phase 0 corpus stays green; no annotation-mode
corpus change.

#### F2. Statement-level CFG and worklist

Status: done.

- F2a (done): strict `for`/`do-while`/`switch` state snapshots and joins; zero-iteration
  paths and switch outcomes are covered by `strict/flow_loop_switch.groovy`.
- F2b (in progress): the structural CFG is in place (`CfgNode` / `CfgEdge` /
  `ControlFlowGraph`), with if/while/for/do/switch/break/continue/return/throw edges and
  `ControlFlowGraphSpec`. Strict `while`/`for` bodies now iterate to a flow-state fixed point,
  and duplicate findings are suppressed by the reporter (`flow_loop_report_once`).
  The generic `FlowAnalysis` worklist is in place (join at predecessors, fixed-point loop,
  `FlowAnalysisSpec`), and `NullnessFlowTransfer` now drives `strict` methods through the CFG;
  annotation mode keeps the old visitor. Condition facts are applied on TRUE/FALSE edges and
  no-return expressions stop propagation.
  Switch fall-through is modelled (a case's normal flow reaches the next case, `break` goes to
  after), and the for-each variable receives the collection's nullable element facts. Remaining
  F3 work is try/catch/finally; break/continue edges are structural and covered by the CFG
  builder tests.
- The `FlowState` map is ready; the visitor still projects it until the CFG owns the state.

Acceptance: branch, no-else, zero/multi-iteration loop, break/continue and switch fixtures;
no duplicate findings; default annotation mode unchanged.

#### F3. `try/catch/finally`

Status: done.

- The try node has a normal edge into the try body/finally and one exception edge into each
  catch. Catching starts from the state before the try (conservative), and the catch parameter
  is marked non-null.
- Normal try and catch paths flow through `finally`; the fixture covers a normal try path, a
  catch assignment and a finally overwrite.
- Abrupt exits (`return`/`break`/`continue`) through `finally` are not yet special-cased; this
  remains a documented limitation, not a silent claim.

Acceptance: `strict/flow_try_catch.groovy` covers try/catch/finally state joins.

#### F4. Diagnostics, deduplication and budget

Status: done.

- Findings are emitted by the flow transfer, deduplicated by message + source position in the
  reporter, and sorted by file/line/message at the harness boundary.
- The `Potential null dereference` prefix and the `null` category are unchanged.
- `docs/PERF.md` records a baseline for \`compileGroovy\` and the checker test suite.
- Unreachable CFG nodes are never visited by the worklist, so they produce no findings.

#### F5. Finite access paths

Status: done.

- `AccessPath` tracks a root (`this`/variable) plus at most two field names; `this.f` and
  `a.b.c` are in scope.
- `FlowState` carries path facts; guards apply them on TRUE/FALSE edges and the lexical
  annotation path uses the same facts.
- Assignments update the path fact; method calls invalidate paths rooted at the receiver
  (including `this`), so `reset(); this.f.length()` reports again.
- Alias handling remains deliberately unsound and documented; no heap abstraction.
- Element/`Map.Entry` facts stay in the existing logic.

Acceptance: `strict/flow_access_path.groovy` covers a guarded `this.value`, an unguarded read
and a post-call read.

#### F6. Closures

Status: done.

- Each closure body is analyzed as its own CFG with the current captured-variable state.
- The closure exit state joins the pre-call state with the post-body state, so a zero-execution
  path cannot be dropped.
- A closure `return` exits the closure CFG, not the outer method; break/continue cannot cross
  the closure boundary.
- Escape is handled conservatively by keeping the joined state (the analysis never assumes the
  closure ran exactly once).
- Captured writes are not summarized as a separate API; the state is shared through the CFG.

Acceptance: `strict/flow_closure_capture.groovy` covers each-may-skip, a guarded captured write
and a closure return.

#### F7. Flow validation and documentation

Status: done.

- The external trial was re-run without changing its sources: six modules with sources, 251
  Groovy files, 397 classes; strict mode reported 12 findings (10 null, 2 resolution).
- Triage found the same shape families (partial lookups, nullable locals, dynamic resolution)
  and no new flow-only false-positive shape. The two earlier flow false positives stay fixed.
- NULLNESS-FLOW, BACKLOG and CHANGELOG record the result; dualRun remains informational.

### 2. Gradle plugin

#### P1. Static-only compile mode (#3)

Status: done.

- `NullCheckCustomizer` has a static-only mode; compile mode passes it to the customizer.
- Only `@CompileStatic` / `@TypeChecked` / `@NullCheck` classes or methods are injected.
- Dynamic classes compile untouched; resolution findings stay report-only in standalone mode.
- The functional suite has a mixed static/dynamic fixture; the static violation is still
  reported and the dynamic unresolved call does not break compilation.

Acceptance: met by the mixed fixture and the existing failOnError gate test.

#### P2. Compile-mode canary (#4)

Status: done.

- Compile mode registers `nullcheckCanary`, a standalone CLI run on a generated
  `@CompileStatic` sample with an `@Nullable` field.
- It expects exit code 1 (a finding); `nullcheck` depends on it when `canary = true`.
- A checker that visits nothing can no longer make compile mode look clean.

Acceptance: the compile-mode functional tests run the canary; a failed self-check fails the
build.

### 3. Checker interop

#### C1. Java `Optional` / Stream unwrapping (#8)

Status: done.

- `Optional.orElse`: nullable if its argument is nullable, including a literal `null`.
- `orElseGet`: conservatively nullable; `orElseThrow` and `get`: non-null.
- `Stream.findFirst` / `findAny`: non-null `Optional`.
- `positive/optional_or_else.groovy` covers `orElse(null)`, `orElse('x')`, `get()` and
  `Stream.findFirst().orElse(null)`.

Acceptance: the Optional fixture passes in both annotation and strict modes.

### 4. Cleanup

#### Q1. One finding-classification owner (#5)

Status: done.

- `NullCheckCategories` owns the fragment/category mapping and the category labels.
- `NullCheckFinding` and the harness delegate to it; the harness keeps only the enum mapping.
- Compile mode and the CLI classify the same message identically.

Acceptance: `NullCheckCategoriesSpec` plus the existing sink/corpus tests.

#### Q2. SpotBugs test filter (#7)

Status: pending, low priority.

- Re-enable `spotbugsTest` only with a test-specific filter that removes the Spock dynamic
  dispatch noise.

### 5. Release

#### R1. Version and release

Status: done (0.8.0 released).

- `gradle.properties` is `0.8.0`; the tag and GitHub Release are `v0.8.0`.
- `clean check`, `dualRun`, `dist`, the release workflow and the plugin functional suite passed.
- GitHub Release assets and the Gradle Plugin Portal publication are live.
- Maven Central serves `0.7.3`; the dogfood plugin and `toolVersion` are bumped after `0.8.0` propagation.

#### R2. Documentation

Status: done.

- README/README.zh-CN, CHANGELOG, PORT and NULLNESS-FLOW reflect the released behavior.
- Historical CHANGELOG/BACKLOG versions remain untouched.

## Future work

### N1. Closure return-nullness propagation

Status: done (0.8.0).

Current behavior: a closure that returns `null` is not reported at its call site, and assigning
`c.call()` to an outer variable does not make that variable nullable. Assigning `null` to a
captured outer local **is** tracked in strict mode.

Planned scope:

- summarize the nullable-return behavior of direct, non-escaping closure literals;
- propagate that summary through `Closure.call()` / direct closure invocation;
- keep alias/escape cases conservative (`MAYBE_NULL` or `UNKNOWN`) rather than guessing;
- add corpus fixtures for closure-return-to-variable and closure-side-effect tracking;
- document that annotation mode remains non-flow-sensitive.

Acceptance: direct local closure return null is reported at the receiver; an outer variable
assigned from that call is nullable; existing closure-side-effect tests stay green.

## Tracking

- Work top to bottom; do not start a later phase while an earlier acceptance criterion is open.
- Every behavioral change needs a corpus positive/negative pair; strict-only behavior needs a
  strict fixture.
- `dualRun` is diagnostic, not a gate.
- Keep `check`, Spotless, CodeNarc and the plugin functional tests green after each item.
- Update this file when an item starts or finishes; stale roadmap states are worse than no
  roadmap.
