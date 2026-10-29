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
- The latest published release is `0.11.0`.

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
- Analysis-budget exhaustion is diagnosed by H0-2 and routed through the internal-error
  channel.

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

Status: done.

- `spotbugsTest` is enabled in both modules.
- The shared filter removes only the Spock dynamic-dispatch artefact
  (`UPM_UNCALLED_PRIVATE_METHOD` on `*Spec` classes); the rest of the test bytecode is analysed.

Acceptance: `check` runs `spotbugsTest` in both modules with no findings.

### 5. Release

#### R1. Version and release

Status: done (0.11.0 released).

- `gradle.properties` is `0.11.0`; the tag and GitHub Release are `v0.11.0`.
- `clean check`, `dualRun`, `dist`, the release workflow and the plugin functional suite passed.
- GitHub Release assets and the Gradle Plugin Portal publication are live.
- Maven Central serves `0.11.0`; the dogfood plugin and `toolVersion` are bumped to `0.11.0`.

#### R2. Documentation

Status: done.

- README/README.zh-CN, CHANGELOG, PORT and NULLNESS-FLOW reflect the released behavior.
- Historical CHANGELOG/BACKLOG versions remain untouched.

## 6. Hardening after `0.11.0`

Source: [REVIEW-ACTIONS.md](REVIEW-ACTIONS.md). This section schedules only items with a
concrete action and acceptance criterion. Hypotheses that still need proof, and large redesigns
that would compete with the flow work, stay in the deferred group.

Work top to bottom. Each item must keep `check`, CodeNarc, Spotless, SpotBugs and the plugin
functional suite green, and must record its own acceptance evidence.

### 6.1 H0: correctness and trust

These are the next priority. Do not start H1/H2 while an H0 acceptance criterion is open.

#### H0-1. Stale sink protection (`P0-1`)

- Status: done.
- The cached file sink is compared with the current `groovy.nullchecker.findingsFile`; a new
  sink is installed when they differ. Explicitly installed sinks keep their own lifecycle.
- `NullCheckCustomizer` clears the property-created sink at compilation start and registers an
  output-phase cleanup.
- `NullCheckSinkSpec` covers worker reuse, explicit-sink precedence, two findings files, and
  post-compilation cleanup.

#### H0-2. Analysis-budget diagnostics (`P0-2`)

- Status: done.
- `FlowAnalysis` exposes `budgetExceeded`; the visitor reports a diagnostic with the method
  name and limit when the worklist or a loop fixed point stops early.
- Diagnostics use the same internal-error channel as checker exceptions, including the
  compile-mode `.errors` sidecar, so the harness/compile-mode CLI rejects a clean-looking
  result.
- `FlowAnalysisSpec` covers a synthetic over-budget worklist; `NullCheckReporterSpec` covers
  the internal-error and sidecar path.

#### H0-3. Fail-closed unknown checks (`P0-3`)

- Status: done.
- `NullCheckConfig` validates every checks value and throws on an unknown name; `NullChecker`
  records the failure through the internal-error channel instead of letting Groovy swallow it.
- The bare-checker path now rejects the run; CLI/plugin pre-validation remains unchanged.
- `CheckSelectionSpec` covers the invalid bare-checker path.

#### H0-4. Structured finding categories (`P0-4`)

- Status: open, next.
- Carry the category from the finding creation site; keep `NullCheckCategories.classify` only
  as a fallback for compiler messages that cannot be structured.
- Freeze the current message/category table in a test; every new message must be registered.
- Acceptance also covers `P3-4`'s full category table.

### 6.2 H1: lifecycle and architecture

Start after H0 is green.

#### H1-1. Bind `AstSupport` caches to a compilation (`P1-3`)

- Status: open.
- Clear or weakly reference the annotation caches at compilation/session end; evaluate the
  same lifecycle for `SESSIONS`, `PORT_OPTIONS` and `lastCoverage`.
- Add a repeated-compilation test that asserts cache sizes do not grow across runs.

#### H1-2. Remove test scaffolding from the published jar (`P1-5`)

- Status: open.
- Move `CorpusGate` / `CorpusRules` to test scope or a dedicated harness module; keep
  `tools/dual-run.groovy` working.
- Acceptance: the checker jar no longer contains those classes; corpus and `dualRun` still run.

#### H1-3. Merge the finding DTOs (`P1-6`)

- Status: open.
- Define one core finding shape; make the harness and report DTOs adapters/views.
- Keep `toTsv` / `fromTsv` / rendering responsibilities separate.
- Acceptance: one field definition source; existing classification and serialization tests pass.

#### H1-4. Per-compilation configuration (`P1-4`)

- Status: open after H1-1/H1-3.
- Replace direct global-property reads below the boundary with one configuration object passed
  to the session/visitor/registry; system properties remain the transport layer.
- Add an alternating-configuration test (strict/contracts/checks) and prove results do not leak.

### 6.3 H2: robustness and consistency

These are suitable for incremental, module-local work.

- **H2-1 (`P2-1`)**: report `PackageScopes` package-info failures as diagnostics instead of
  silently falling back to "no default"; test a forced read failure.
- **H2-2 (`P2-2`)**: do not swallow `VirtualMachineError` in `NullCheckReporter.guard`; rethrow
  fatal VM errors, keep ordinary recoverable failures visible.
- **H2-3 (`P2-3`)**: delete the Groovy-4-unreachable
  `INFERRED_NON_NULL_RETURN_VIOLATIONS` branch or guard it behind an explicit version check.
- **H2-4 (`P2-4`)**: split `NullCheckFindingsCli.main` into `run(args, out, err)` and `main`;
  test exit codes, `--help` and unknown arguments.
- **H2-5 (`P2-5`)**: make findings/coverage writers safe across sink instances sharing a file;
  use a per-path lock or per-compilation writer.
- **H2-6 (`P2-8`)**: keep the nullable annotator conservative; add diff output, explicit skip
  counts, idempotency and multi-line/Tab/CRLF tests.
- **H2-7 (`P2-9`)**: remove the duplicate `@Override`, duplicate import, stale Javadoc, duplicate
  non-null-assignment path, and unused rules; update the plugin functional version constant.
- **H2-8 (`P2-10`)**: audit all `expect=some` corpus positives; use exact counts unless the
  mismatch is genuinely version/type-inference dependent, and document why.
- **H2-9 (`P2-11`)**: fix the duplicate `PORT.md` headings, ROADMAP status contradictions,
  `allprojects`/`subprojects` comments, and stale symbol/line references.
- **H2-10 (`P3-6`)**: special-case `return`/`break`/`continue` through `finally` in the CFG, or
  record a permanent limitation with fixtures.

### 6.4 H3: cleanup, CLI and performance

- **H3-1 (`P3-1`)**: move task state to serializable `Property`/`ConfigurableFileCollection`
  and enable configuration-cache verification incrementally.
- **H3-2 (`P3-2`/`P3-3`)**: align CLI option validation, missing values, unknown options and
  values beginning with `-`.
- **H3-3 (`P3-4`)**: expand `NullCheckCategoriesSpec` into the full message table; new messages
  must fail until registered. This is shared with H0-4.
- **H3-4 (`P3-5`)**: refresh the `docs/PERF.md` baseline and add micro-benchmarks for
  `FlowState.join` and capture/restore.
- **H3-5 (`P3-7`)**: autofix provider SPI; keep it behind N3 and do not freeze an API before
  the H0/H1 work is stable.

### 6.5 Deferred and decision-only

Not in the executable queue:

- **`P1-1` visitor split**: a valid architecture debt, but an epic. Run it only after H0/H1 and
  with a behavior-preserving test plan; do not mix it with feature work.
- **`P1-2` dead strict-mode branches**: the claim that `visitIfElse`/loop branches are
  unreachable still needs instrumentation/proof. Do not delete them yet; add a counter/assertion
  first and record the result.
- **`P2-6` annotation-mode branch precision**: decide whether to add `FlowState` joins or
  document the limitation; implementation waits for that decision.
- **`P2-7` definite-null projection**: a precision improvement, not a correctness blocker;
  revisit after H0/H1.

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

### N2. Opt-in `@Nullable` migration

Status: done (0.9.0).

- Separate `nullcheckAddNullable` task / `NullableAnnotatorCli`; not part of `check`.
- High-confidence targets only: explicit null returns, null-initialised/assigned fields,
  null-checked parameters, in `@NullMarked` scopes.
- Dry-run support and source-position edits for single-line declarations; complex declarations
  are skipped rather than reformatted.

### N3. Autofix provider framework

Status: prototype on the working tree (Unreleased); design not final.

- A controlled `FixProvider` SPI: built-in high-confidence fixes and user providers share the
  same proposal/apply path.
- Match declarations/call sites/findings; emit text edits or structured assertion insertion.
- Dry-run and diff by default; no automatic mutation as part of `check`.
- Open questions: script vs compiled provider, source-edit safety, idempotency, edit ordering,
  and the public API surface.

### N4. Repeated lookup guard

Status: future, low priority.

- `map.get(k) != null && map.get(k).isBlank()` currently reports on the second lookup.
- Add the same lookup fact that `containsKey(k)` uses when a nullable lookup call is compared
  with null, so the repeated call with the same receiver/key is narrowed.
- Keep different keys reporting.
- Corpus: same-key silent, different-key reported.

## Long-term goal G1: broader Groovy version support

Status: deliberately deferred, low priority.

nullchecker currently targets Groovy 4.0.x and has grown beyond the upstream Groovy null
checker in both coverage and features. That makes broader Groovy support valuable for users
who cannot move to Groovy 4 yet. This is a product-expansion goal, not part of the current
correctness/flow work.

### Why now is not the time

- finish the H0/H1 reliability work and the remaining flow/plugin capabilities first;
- keep the checker behavior stable before multiplying the test/build matrix;
- avoid freezing a compatibility abstraction based on a moving implementation.

Do not start compatibility work in a feature branch. Treat it as a separate program after the
current feature line is stable.

### Target

Gradually support the mainstream Groovy compiler majors, with Groovy 4 remaining the primary
target until the other variants have equivalent corpus and functional coverage. The exact
version window (for example 3.x/4.x/5.x/6.x) must be confirmed by an investigation, not assumed
from release numbers.

### Work required

1. **Compatibility inventory**
   - map every compiler API the checker depends on (type-checking extension SPI, AST nodes,
     `StaticTypesMarker`, phase/`CompilationUnit`, annotation/type-use handling, transforms);
   - record which APIs differ by Groovy major, with a source reference and a test.

2. **Version boundary**
   - decide whether each supported major gets a separate checker artifact (for example a
     Groovy-major-specific classifier/coordinate) or whether a shared artifact can isolate
     differences safely;
   - keep the Gradle plugin's `toolVersion` resolution explicit; never silently load a checker
     built for another Groovy major;
   - document the Java baseline for each supported Groovy line.

3. **Shared implementation, minimal forks**
   - put version-specific code behind a small compatibility layer, not duplicated visitors or
     contract parsers;
   - keep the corpus, report format, CLI options and annotation semantics identical across
     versions;
   - reject a version with an explicit diagnostic when its compiler API cannot be supported,
     rather than degrading silently.

4. **CI and release matrix**
   - run the full positive/negative corpus, strict and annotation modes, CLI tests and plugin
     functional tests against every supported major;
   - keep one canonical Groovy 4 lane for the main release gate;
   - record per-version performance and known limitations in `docs/PERF.md` and README;
   - make the release process attach/version each artifact without ambiguity.

### Acceptance

The goal is complete only when:

- every advertised Groovy major passes the same corpus and plugin gates;
- unsupported majors fail with a clear diagnostic;
- the main Groovy 4 path has no behavioral or performance regression;
- README/PUBLISHING document the compatibility matrix and artifact coordinates;
- there is no duplicated core rule implementation kept only for one version.

### Non-goals

- supporting every Groovy patch release individually;
- promising binary compatibility of the checker jar across compiler majors;
- weakening nullness semantics or corpus expectations to make an older compiler pass;
- starting this before H0/H1 and the remaining flow/plugin items are stable.

## Tracking

- Work top to bottom; do not start a later phase while an earlier acceptance criterion is open.
- Every behavioral change needs a corpus positive/negative pair; strict-only behavior needs a
  strict fixture.
- `dualRun` is diagnostic, not a gate.
- Keep `check`, Spotless, CodeNarc and the plugin functional tests green after each item.
- Update this file when an item starts or finishes; stale roadmap states are worse than no
  roadmap.
