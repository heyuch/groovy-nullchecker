# Changelog

Notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/).

## [0.10.0] - 2026-10-28

### Added

- **Assertion-condition narrowing.** Methods declared with `@Contract('false -> fail')` or
  listed in `assertsTrue` / `--asserts-true` establish that their first boolean argument was
  true when they return normally. The existing guard analysis then narrows compound conditions
  such as `outcome != null && (...)`.

- **Configurable @NonNull annotation names.** Built-ins already cover `NonNull`, `NotNull`,
  `Nonnull`, `NotEmpty` and `NotBlank`, including type-use annotations. Projects can add
  package-specific names through `nonNullAnnotations` / `--nonnull-annotation`.

### Fixed

- **Explicit `@Nonnull` locals own their contract.** A nullable value assigned to a `@Nonnull`
  local now reports `Cannot assign @Nullable value to @NonNull variable` at the assignment
  site; after that report the variable is treated as non-null, so the later dereference is not
  reported again.

### Changed

- **Dogfood moved to `0.9.0`.** Central serves the release, so the self-check plugin and
  `nullcheck.toolVersion` are bumped from `0.8.0`.

## [0.9.0] - 2026-10-27

### Fixed

- **Compile mode reads `@NullMarked` from a package-info class.** The compiler-worker
  classpath is passed to `PackageScopes`, which uses an isolated classloader for
  package-info so a package already defined by a parent loader cannot mask its annotations.
  This fixes markedOnly skipping static classes in packages marked only via package-info.class.

### Added

- **Opt-in `@Nullable` migration.** The separate `nullcheckAddNullable` task / annotator CLI
  adds annotations to high-confidence `@NullMarked` declarations: explicit null returns,
  null-initialised or null-assigned fields, and null-checked parameters. It supports
  `nullableDryRun` / `--dry-run`, skips complex declarations, and is never part of `check`.

### Changed

- **Dogfood moved to `0.8.0`.** Central serves the release, so the self-check plugin and
  `nullcheck.toolVersion` are bumped from `0.7.3`.

## [0.8.0] - 2026-10-27

### Added

- **Primitive unboxing rule (R11).** A nullable value entering a primitive declaration,
  assignment, method argument, return or cast now reports
  `Cannot unbox @Nullable value to primitive '...'`. After reporting, the primitive target is
  treated as non-null to avoid duplicate return/deref findings.

- **Closure return-nullness propagation.** Direct local closure literals are summarized: a
  closure that can return null makes `c.call()` nullable, so assigning it to an outer variable
  or returning it from a `@NonNull` method is tracked. Nested returns stay in their own
  closure; closures passed to another API are treated as escaping and are not trusted. See
  `docs/PERF.md` for the performance comparison.

### Changed

- **Dogfood moved to `0.7.3`.** Central serves the release, so the self-check plugin and
  `nullcheck.toolVersion` are bumped from `0.7.2`.

## [0.7.3] - 2026-10-27

### Fixed

- **A bare return inside a closure is no longer treated as the enclosing method's return.**
  `@NullMarked`/`@NonNull` methods that call `forEach`/`each` with an early `return` in the
  closure no longer report `Cannot return null from @NonNull method`. The closure depth is
  tracked while visiting the body, so a method-level return check applies only outside
  closures.

### Changed

- **Dogfood moved to `0.7.2`.** Central serves the release, so the self-check plugin and
  `nullcheck.toolVersion` are bumped from `0.7.1`.

## [0.7.2] - 2026-10-23

### Fixed

- **A missing signature type no longer aborts the whole module.** Before injection, the
  customizer probes receiver signatures; if a referenced type cannot be resolved it skips that
  class and reports a resolution finding with the missing type, e.g. `Cannot resolve referenced
  type 'demo.types.Doc' in 'demo.app.App'; the class was not analysed`. If no class can be
  analysed, the CLI reports an incomplete resolution finding instead of a General error or a
  silent zero.

### Added

- **Text report targets: file, stdout and stderr.** `NullCheckReportWriter` renders one stable
  report used by both CLIs. Add `--report-stdout` / `--report-stderr` (CLI) or
  `reportToStdout` / `reportToStderr` (plugin); all targets receive the same metadata, findings,
  coverage and raw text.

### Changed

- **`markedOnly` does not inject type checking into unmarked dynamic classes.** They no longer
  produce checker-introduced resolution findings, but classes already annotated with
  `@CompileStatic`/`@TypeChecked` are still type-checked by Groovy itself; suppressing those
  resolution findings requires report-layer filtering.

- **Dogfood moved to `0.7.1`.** Central serves the patch release, so the self-check plugin and
  `nullcheck.toolVersion` are bumped from `0.7.0`.

## [0.7.1] - 2026-10-23

### Fixed

- **Compile mode no longer breaks private calls from closures on subclass instances.**
  Injecting `@TypeChecked` into an already `@CompileStatic` class changed closure dispatch to
  a runtime indy call; `new ReproChild().execute()` then failed with
  `MissingMethodException` for the private method. Compile mode now injects
  `@CompileStatic(extensions = ...)` for classes that are already static, preserving the
  compiler's static accessor. Covered by a TestKit regression using a four-class repro.

### Changed

- **Dogfood moved to `0.7.0`.** Central serves the release, so the self-check plugin and
  `nullcheck.toolVersion` are bumped from `0.6.0`.

## [0.7.0] - 2026-10-22

### Added

- **Progressive adoption with `markedOnly`.** When enabled, only classes in scope of
  `@NullMarked` (class/enclosing class, or exact package) are analysed; `@NullUnmarked` opts
  out. Unmarked classes are skipped, so legacy projects can migrate package by package.
  Available as the plugin `markedOnly` property and CLI `--marked-only`.
  `markedOnly` filters checker findings; core compiler resolution errors from unmarked classes
  are still reported (they are not nullness claims and are not fail-on by default).

### Changed

- **Dogfood moved to `0.6.0`.** Maven Central now serves the release, so the self-check plugin
  and `nullcheck.toolVersion` no longer stay on `0.5.0`.

## [0.6.0] - 2026-10-21

### Added

- **Static-only compile mode.** `compileCheck` now injects the checker only into
  `@CompileStatic` / `@TypeChecked` / `@NullCheck` classes or methods, so mixed static/dynamic
  projects build green while standalone mode still reports dynamic resolution findings.
- **Compile-mode canary.** A `nullcheckCanary` task runs the checker on a known-bad sample and
  must see one finding before `nullcheck` trusts the collected results.
- **Optional/Stream contracts.** `Optional.orElse` follows its argument's nullness,
  `orElseGet` is conservatively nullable, and `get` / `orElseThrow` / `Stream.findFirst` /
  `findAny` are non-null.
- **Report reasons for nullable values.** A return or argument report now explains where the
  nullability comes from, e.g. `Cannot return @Nullable value from @NonNull method
  'fragmentFor': map lookup 'RULES[rule]' may return null`, instead of only naming the target.

### Changed

- **Documentation versions are generated from `gradle.properties`.** Spotless's root `docs`
  format rewrites README, publishing docs and the consumer examples during `spotlessApply`.
  Historical CHANGELOG/BACKLOG entries are excluded, and this is not a stale gate: `check` does
  not fail merely because the docs were not refreshed.

- **Upstream parity is no longer a release gate.** `dualRun` now reports differences without
  failing, and the expected-divergence list is gone. The port intentionally evolves toward a
  more accurate checker; default-mode changes still need corpus/trial evidence and a
  `PORT.md`/`CHANGELOG.md` note.

- **Phase 0 + F1 of the flow design are implemented.** Strict mode now snapshots branch facts
  and joins them: may-null joins by the per-variable `Nullness` table, guards/non-empty/lookups
  by intersection, and early-exit branches are excluded. This fixes the branch-order and
  early-return FP/FN shapes from `docs/NULLNESS-FLOW.md`. Strict `for`/`do-while`/`switch`
  state joins are also in place. Strict loop bodies iterate to a fixed flow state and the
  reporter deduplicates repeated findings. Strict methods now run through a statement-level
  CFG worklist (`ControlFlowGraph` + `FlowAnalysis` + `NullnessFlowTransfer`); annotation mode
  keeps the old visitor. F2 is complete: switch fall-through is modelled and for-each variables
  receive the collection's nullable element facts. F3 adds try/catch/finally edges, non-null
  catch parameters and finally joins (abrupt exits through finally are still conservative).
  Findings are deduplicated and sorted at the report boundary; `docs/PERF.md` records the
  current flow-analysis baseline. F5 adds finite access paths (`this.f`, `a.b.c`, depth <= 2)
  with guard narrowing and call invalidation; alias handling is explicitly not sound. F6
  analyzes each closure body through its own CFG and joins the zero-execution path. F7 re-ran
  the external trial (251 files, 397 classes, 12 findings) with no new flow-only false-positive
  shape. Strict corpus fixtures and `FlowStateSpec` pin the behavior.

- **Finding classification has one owner.** `NullCheckCategories` supplies the labels and
  message mapping used by the core, CLI and harness.

- **SpotBugs analyses test bytecode.** `spotbugsTest` is enabled in both modules with a narrow
  Spock dynamic-dispatch filter.

- **Dogfood moved to `0.5.0`.** The self-check now applies the published plugin and checker
  `0.5.0`. It found one real issue: `CorpusRules.fragmentFor` returned the nullable result of a
  map lookup from a non-null method; it now declares `@Nullable`.

## [0.5.0] - 2026-10-21

### Added

- **Opt-in compile-time checking for the Gradle plugin** (`nullcheck { compileCheck = true }`):
  the checker runs inside `compileGroovy`, so the build pays for one compilation instead of a
  normal compile plus a separate CLI compile. Findings are collected into a findings file (not
  added as compiler errors), so `failOnError = false` still reports without failing; the
  `nullcheck` task then reports and applies the usual `failOn`/`failOnError` policy. A checker
  internal error or a compile that visited no classes makes the task exit with code 2 rather
  than look clean. The default remains the standalone CLI, which can still report on code that
  does not compile and runs the canary.

- **A single contract registry.** `@Contract`, `@EnsuresNonNullIf`, `@EnsuresNonNull`,
  `@NoReturn`, the `neverReturns` / `guardNotNullWhenTrue/False` configuration and the
  built-in table for `Map.get`, `find`, `max`, `Objects.requireNonNull` and friends now resolve
  to the same model and behave identically, instead of being five separate implementation
  paths.
- **`contracts` configuration** (plugin) and **`--contract`** (CLI) declare a contract for any
  method by `declaringClass#method`, in JetBrains' syntax. Configuration wins over annotations
  and built-ins, so a library whose contract is wrong or missing can be corrected without
  changing the library.
- The registry also recognises `_, !null -> !null` (the result is nullable exactly when that
  argument is, as in `getOrDefault`) and `-> !null` / `-> null` (a constant result nullness),
  which the annotation reader did not support before.

### Changed

- **The project checks itself, strictly, under `@NullMarked`.** The published Gradle plugin
  (0.3.1) is applied to the `nullchecker` module, `nullcheck` is part of `check`, `strict` is on,
  and both checker packages carry a `package-info.java` with `@NullMarked` (JSpecify is a
  `compileOnly` dependency). The first run found a real nullable `Package.getPackage()`
  dereference; the strict run then exposed 50 nullability mismatches, now made explicit with
  `@Nullable` contracts and non-null defaults for data fields. Coverage reports
  `nonNullByDefault=38`, i.e. every class is under the marking. Bump `nullcheck.toolVersion`
  when a new checker is released, since this dogfood deliberately uses the published artifact
  rather than the working tree.
- **The last Java sources became Groovy.** `NullCheckCoverage` is now a `@CompileStatic` Groovy
  DTO (its public fields become Groovy properties; the only callers are Groovy), and the two
  `package-info.java` files are `package-info.groovy` next to the sources they apply to, instead
  of sitting in `src/main/java` while governing `src/main/groovy`. `src/main/java` no longer
  contains sources; the `javadoc` jar is empty but still produced for Central.
- **Flow refinements for safe navigation and non-empty collections.** A safe-navigation chain
  compared with null or a non-null constant now narrows its receiver variables on the branch
  where the chain is known non-null. `isNotNullOrEmpty(...)` guards make `max`/`min` on the same
  collection non-null; `find`/`findResult` remain nullable. `first`/`head` throw on empty and
  `any` is boolean, so they are not part of this refinement; N24 pins that distinction. Both
  changed shapes have corpus regressions.
- **Spotless handles basic Groovy formatting.** Both modules use Spotless 8.10.4 with import
  ordering, semicolon removal, trailing-whitespace trimming and a final newline. Greclipse is
  deliberately not used: it is large and the project does not need a full re-formatter.
  `spotlessCheck` is part of `check`; use `./gradlew spotlessApply` to fix.
- **CodeNarc enables every rule that fits the project.** A trial of all 405 bundled rules found
  724 violations across 45 rules; 42 rules are disabled with reasons recorded in
  `docs/CODENARC.md` (conflicting style, intentional CLI/best-effort behaviour, and size/
  complexity pending a threshold decision). The committed ruleset enables 363 rules and reports
  zero violations. Two findings were fixed: the `current`/`CURRENT` name clash and an unnecessary
  last-element expression.
- **Static analysis runs in `check`.** CodeNarc enforces explicit types, explicit returns and
  braces on main sources (tests keep the correctness rules only), and SpotBugs analyses
  production bytecode. The SpotBugs filter records the patterns that do not describe Groovy
  bytecode -- closure serialization, synthetic fields, compiler-generated null checks and casts,
  and unused-private-method reports for dynamic classes -- so a new real finding fails the build.
  SpotBugs is the maintained successor to FindBugs, which does not run on JDK 17.
- The resolver caches per resolved `MethodNode`, including the negative answer, so contract
  lookup is a map hit rather than a repeated hierarchy walk. `containerLookups` now gates only
  the built-in entries; an explicitly declared contract still applies when the family is off.

## [0.4.0]

### Added

- **Container lookups that can miss are nullable.** `Map.get`, the subscript, `remove`,
  `put`, `compute*` and `merge`, plus `find`, `findResult`, `max` and `min`, can return null
  even when the element type is non-null: the null comes from the operation, not from the
  stored value. `Map<String, String>` therefore no longer makes `get(missingKey)` look safe.
- **`containsKey` narrows a lookup** in the true branch, and `getOrDefault` stays non-null
  when its default is. `first()`/`last()` stay non-null: they throw on an empty collection.
  Narrowing requires non-null values -- a present key in `Map<K, @Nullable V>` may still hold
  an explicit null.
- **`containerLookups` switch** (plugin; `--no-container-lookups` on the CLI) turns the
  family off while a project migrates, leaving the type-based findings in place.

### Performance

- **Package-annotation lookups are cached** per class and per package, instead of resolving
  `package-info.class` through up to four classloaders for every scope decision. On a
  319-file module this was the largest single checker cost (about 10% of JFR samples, mostly
  in class loading) and is now gone.
- **Annotation simple names are cached per node**, removing repeated declaration and type
  annotation walks while deciding a scope.
- Measured end to end on that module: about 10-15% faster; the checker still roughly doubles
  the compilation of one module, which is dominated by Groovy's own parser and static type
  checker. See `PORT.md` §5.8 for the profile and the remaining structural option.

## [0.3.1]

### Fixed

- **A call that never returns no longer leaves the code after it reported.** A helper that
  always throws is invisible in its signature, so a fallback `return null` after a
  `try`/`catch` that ends with such a helper was reported as returning null from a non-null
  method even though it can never run. A declared no-return call now terminates the block for
  the checker as well, and narrows like a `throw`: `if (x == null) giveUp()` leaves `x`
  non-null. Declare the helper with `@NoReturn` (also `@NeverReturns` /
  `@DoesNotReturn`), with JetBrains `@Contract('_, _ -> fail')`, or with the new
  `neverReturns` list. A partial `'null -> fail'` is deliberately not treated as "never
  returns".

## [0.3.0]

### Added

- **Contract annotations**: `@EnsuresNonNullIf(expression = '#1', result = false)` and
  `@EnsuresNonNull(expression = '#1')`, matched by simple name, so a helper carries its own
  guard contract (the `isNullOrEmpty` case) instead of every caller configuring it. `#N`
  references are supported; anything else is ignored rather than guessed at. A reversed
  `result` still reports the guarded code, so a mislabelled contract cannot hide a finding.
- **Map keys and values** are tracked separately: `Map<String, @Nullable String>` and
  `Map<@Nullable String, String>` are followed through `get`, subscript, `values()`,
  `keySet()`, `Map.Entry` iteration and the pairwise closure of `each`.
- **JetBrains `@Contract`** is understood for the shapes that constrain an argument:
  `null -> fail`, `null -> false` and `null -> true`. The rest of its grammar is ignored rather
  than guessed at, so one annotation serves IntelliJ and this checker.
- **Container element checking** covers iteration as well as access: a `for`-each variable and
  a closure parameter of `List<@Nullable E>` are nullable values, so `for (String s : xs)
  s.length()` and `xs.each { it.length() }` are reported. Subscript, `get()` and `first()`
  already were; a null check or safe navigation still silences them.
- **`@NotEmpty` and `@NotBlank` are non-null contracts**, alongside `@NotNull`, because both
  reject null before checking content. They keep the validation constraint's exemption from
  the initialisation rule. `org.hibernate.validator.constraints` joins the family.

## [0.2.0]

This release diverges from Groovy 6's `NullChecker` on purpose. The project is maintained as
a fork rather than a faithful back-port, because several upstream behaviours are defects in
real code; each divergence is exercised by a corpus fixture and visible in the `dualRun`
comparison.

### Fixed

- **`xs*.prop` no longer looks nullable.** Groovy marks a spread property expression safe
  because it tolerates a null receiver, but the result is always a `List`. The following call
  was reported — including in default mode, on ordinary collection code.
- **`continue` and `break` now narrow like `return`.** `if (x == null) continue` is the
  narrowest loop guard in real code and used to leave `x` nullable for the rest of the body.
- **Bean Validation `@NotNull` no longer demands initialisation.** The non-null contract is
  kept (assigning, passing and returning null are still reported); only the requirement that
  a constructor assigns the field is suspended, so ORM-mapped data objects stop being
  reported wholesale in strict mode. Inside a `@NullMarked` class the requirement stands.
- **A checker that fails internally now fails the run.** Groovy swallows exceptions raised
  inside a type-checking extension, so a broken checker used to report a clean-looking zero
  with a passing self-check. Internal failures are recorded and reported as exit code 2.

### Added

- **Finding categories.** `null`, `initialization` and `resolution`, with the failing set
  configurable by `--fail-on` (CLI) and `failOn` (plugin). Resolution errors — the static
  checker failing on dynamically dispatched code — are still reported but no longer fail by
  default.
- **Scoped package-level defaults.** `@ParametersAreNonnullByDefault`,
  `@ReturnValuesAreNonnullByDefault`, Spring's `@NonNullApi` and `@NonNullFields` are now read
  from `package-info`, each covering only its own scope. Previously only `@NullMarked`-style
  markings worked at package level, and the JSR-305 ones worked only on a class.
- **Selectable rule families.** `initializationCheck` / `nullnessCheck` (plugin) and
  `--checks` (CLI) turn a family off entirely, distinct from `failOn`, which only decides what
  fails. One named boolean per family rather than a list of strings, so the values are
  discoverable where the DSL is written instead of only in the documentation.
  This is what makes package-level `@NullMarked` usable where fields are assigned by a
  container: on a service package it removes 124 of 195 findings, all of them injected fields.
- **Declarable guard methods.** `guardNotNullWhenTrue` / `guardNotNullWhenFalse` (plugin),
  `--guard-when-true` / `--guard-when-false` (CLI), `groovy.nullchecker.guards.*` (system
  properties). Polarity is declared, because a reversed declaration would hide a real finding.
- **`dualRun` is a gate.** Every disagreement with upstream must be listed with a reason; an
  unlisted one fails, and so does a listed one that stopped happening.

### Changed

- **Breaking for CI scripts that relied on any finding failing:** a run now exits `1` only
  when a finding is in a failing category. Use `--fail-on all` for the previous behaviour.
- The `nullcheck` task reports `no failing findings` instead of `no findings`, since
  resolution-only runs are green by design.

## [0.1.0]

First public release: Groovy 6's `NullChecker` back-ported to Groovy 4.0.x, plus a Gradle
plugin. Supported `@CompileStatic` classes, package-level `@NullMarked`, and a self-check that
refuses to report an unverified zero.
