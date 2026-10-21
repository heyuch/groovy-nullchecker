# Changelog

Notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Report reasons for nullable values.** A return or argument report now explains where the
  nullability comes from, e.g. `Cannot return @Nullable value from @NonNull method
  'fragmentFor': map lookup 'RULES[rule]' may return null`, instead of only naming the target.

### Changed

- **Documentation versions are generated from `gradle.properties`.** Spotless's root `docs`
  format rewrites README, publishing docs and the consumer examples during `spotlessApply`.
  Historical CHANGELOG/BACKLOG entries are excluded, and this is not a stale gate: `check` does
  not fail merely because the docs were not refreshed.

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
real code; each divergence is recorded in `nullchecker/tools/upstream-divergences.txt` and
checked by `./gradlew dualRun`.

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
