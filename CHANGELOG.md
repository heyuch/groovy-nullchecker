# Changelog

Notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/).

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
