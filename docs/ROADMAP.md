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
- The latest published release is `0.5.0`.

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

Status: in progress.

- F2a (done): strict `for`/`do-while`/`switch` state snapshots and joins; zero-iteration
  paths and switch outcomes are covered by `strict/flow_loop_switch.groovy`.
- F2b (next): explicit statement-level CFG: block, if, while/do/for, break/continue,
  return/throw; worklist dataflow; loop fixed points; switch fall-through/break edges;
  condition refinement on CFG edges.
- The `FlowState` map is ready; the visitor still projects it until the CFG owns the state.

Acceptance: branch, no-else, zero/multi-iteration loop, break/continue and switch fixtures;
no duplicate findings; default annotation mode unchanged.

#### F3. `try/catch/finally`

Status: pending F2.

- Define the exception-edge policy (do not silently insert an edge from every expression).
- Catch parameter starts non-null; try normal/exception states join at catch.
- `finally` runs on normal and abrupt exits; model `return`/`break`/`continue` through it.

Acceptance: try assignment, catch return, finally overwrite and exception-path fixtures.

#### F4. Diagnostics, deduplication and budget

Status: pending F2/F3.

- Collect findings per CFG node, deduplicate by `(file, start, end, message)`, then sort.
- Keep the `Potential null dereference` prefix and `null` category.
- Record a performance baseline for a single file, the corpus and `check`; set a budget before
  Phase 2.
- Ensure unreachable nodes produce no findings.

#### F5. Finite access paths

Status: pending F4.

- Add `x`, `this.f`, `x.f`, depth <= 2.
- Kill rules on assignment, field write and unknown calls; conservative field invalidation.
- State clearly that alias handling is not sound; do not grow a heap abstraction.
- Keep element/`Map.Entry` type facts in the existing logic.

Acceptance: `if (this.f != null) { this.f.x() }`, `a.b` and post-call invalidation fixtures.

#### F6. Closures

Status: pending F4.

- Analyze each closure as its own CFG; capture variables as read/write summaries.
- Known call sites (`each`, `collect`, direct `call()`) analyze once; after-state joins
  "not executed" and "executed".
- Escape to unknown code conservatively invalidates captured mutable variables.
- Closure `return` exits only the closure; `break`/`continue` do not cross it.

Acceptance: zero/multi-execution, captured write and escape fixtures.

#### F7. Flow validation and documentation

Status: pending F5/F6.

- Run the external trial again; triage FP/FN and record the numbers.
- Update NULLNESS-FLOW, PORT, README and CHANGELOG with the new behavior and limits.
- Confirm dualRun remains informational and the report is explainable.

### 2. Gradle plugin

#### P1. Static-only compile mode (#3)

Status: pending flow work.

- Apply the extension only to `@CompileStatic` / `@TypeChecked` classes, or to an explicit
  opt-in list.
- Dynamic classes keep using standalone mode; resolution findings stay report-only.

Acceptance: a mixed static/dynamic fixture builds green with `compileCheck = true` and
`failOnError = false`, while a real nullness finding still fails under `failOnError = true`.

#### P2. Compile-mode canary (#4)

Status: pending P1.

- Add a small standalone canary task that `nullcheck` depends on, or an equally strong coverage
  invariant.
- A checker that visits nothing must not look clean.

### 3. Checker interop

#### C1. Java `Optional` / Stream unwrapping (#8)

Status: pending.

- `Optional.orElse`: nullable if its argument is nullable.
- `orElseGet`: unknown/nullable; `orElseThrow` and `get`: non-null.
- `Stream.findFirst` / `findAny`: non-null `Optional`.
- Add corpus fixtures, including a nullable `orElse(null)`.

### 4. Cleanup

#### Q1. One finding-classification owner (#5)

Status: pending.

- Move the fragment/category mapping into core; have the harness delegate.
- Compile mode and CLI must classify the same message identically.

#### Q2. SpotBugs test filter (#7)

Status: pending, low priority.

- Re-enable `spotbugsTest` only with a test-specific filter that removes the Spock dynamic
  dispatch noise.

### 5. Release

#### R1. Version and release

Status: after flow work (and optionally P1/P2/C1, depending on scope).

- Choose the next version (`0.6.0` is the likely minor bump for the flow work).
- Update `gradle.properties`; `spotlessApply` updates the installation examples.
- Run `clean check`, `dualRun`, `dist`, and the release workflow.
- After publication, bump the dogfood plugin and `nullcheck.toolVersion` to the new release.

#### R2. Documentation

Status: with R1.

- README/README.zh-CN, CHANGELOG, PORT and NULLNESS-FLOW reflect the released behavior.
- Keep historical CHANGELOG/BACKLOG versions untouched.

## Tracking

- Work top to bottom; do not start a later phase while an earlier acceptance criterion is open.
- Every behavioral change needs a corpus positive/negative pair; strict-only behavior needs a
  strict fixture.
- `dualRun` is diagnostic, not a gate.
- Keep `check`, Spotless, CodeNarc and the plugin functional tests green after each item.
- Update this file when an item starts or finishes; stale roadmap states are worse than no
  roadmap.
