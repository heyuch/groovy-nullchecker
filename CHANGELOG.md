# Changelog

Notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/).

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
