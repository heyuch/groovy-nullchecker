# Backlog

Improvements found while using the checker on real codebases. Reproductions are synthetic: they
keep only the shape that triggers the behaviour, not any project's code or names. Priority
reflects impact on real projects, not implementation effort.

> The method-local nullness flow design is in [NULLNESS-FLOW.md](NULLNESS-FLOW.md), and the
> execution order for the remaining work is in [ROADMAP.md](ROADMAP.md).

## Completed in 0.5.0

### Safe-navigation conditions narrow the receiver

`a?.b?.c == null` / `!= null`, `== <non-null constant>` / `!= <non-null constant>` and a truthy
safe-navigation chain now add the chain's receiver variables to the branch facts. With
`if (item?.details?.kind != Kind.SPECIAL) { continue }`, `item` is non-null after the `if`.

Limitation: only receiver **variables** are tracked. A nullable property in the middle of the
chain (for example an annotated `Details details` field) is still judged by its declaration;
tracking access paths is part of the CFG work, not this fix.

### Dogfood runs the current release

The self-check now uses the published plugin and checker `0.5.0`. It immediately found one real
issue in `CorpusRules.fragmentFor`: the map lookup was nullable under `@NullMarked`. The method
now declares `@Nullable`, which is what its callers already handle.

### `max` / `min` are non-null after a non-empty guard

A branch guarded by `isNotNullOrEmpty(collection)` / `isNotNullOrEmptyDeeply(collection)` now
records the collection as non-empty; `max`/`min` on that same receiver return non-null.
`find`/`findResult` stay nullable because the predicate can still miss.

Limitation: the non-empty fact currently comes from those two conventional helper names. A
project-specific helper with another name still needs a future non-empty guard declaration;
`guardNotNullWhenTrue` only proves non-null, not non-empty.

`first()`/`head()` do **not** need this treatment: Groovy throws `NoSuchElementException` on an
empty collection, so a normal return is already non-null (unless the element type itself is
nullable). `any`/`every` are boolean, and `find`/`findResult` stay nullable because the predicate
can still miss. N24 pins those semantics.

## Gradle plugin: compile mode

### 3. Compile mode injects `@TypeChecked` into dynamic code

```groovy
class Renderer {
    static String render(String input) {
        return DynamicTemplates.render(input)   // unresolved by the static checker
    }
}
```

`NullCheckCustomizer` registers the extension for the whole compilation. A `resolution`
finding that the standalone mode reports without failing becomes a core compiler error, so
`failOnError = false` cannot keep the build green. This is recorded in the plugin README.

Direction: add a static-only mode to the customizer for compile mode, applying the extension
only to classes already annotated `@CompileStatic`/`@TypeChecked` (or explicitly opted in).
Dynamic classes then stay with the standalone mode, where their resolution findings are
report-only.

Priority: high for making `compileCheck` usable on mixed codebases.

### 4. Compile mode has no canary

The standalone run proves it can still see a violation before trusting a result. Compile mode
only has coverage and internal-error sidecars; it warns when `canary = true` is ignored.

Direction: a small standalone canary task (one sample) that `nullcheck` depends on, or a
stronger coverage invariant that proves declarations were actually visited.

Priority: medium.

### 5. Duplicate finding classification

`NullCheckFinding.NULL_FRAGMENTS` duplicates the fragment list in
`NullCheckHarness.categoryOf`. They must stay in step or compile mode and the CLI classify the
same message differently.

Direction: move classification to the core (one owner), have the harness delegate.

Priority: low.

### 7. SpotBugs does not analyse test bytecode

`spotbugsTest` is disabled because Spock's dynamic dispatch makes `UPM_UNCALLED_PRIVATE_METHOD`
noisy. Tests are covered by CodeNarc's basic rules and the corpus/functional gates.

Direction: a test-specific filter that keeps the meaningful patterns, if it becomes valuable.

Priority: low.

### 8. Java Stream/Optional unwrapping is not modelled

`Stream.findFirst()` / `findAny()` return `Optional<T>`, which is itself non-null, but
`orElse(null)`, `orElseGet { null }`, `map` and `flatMap` can produce a nullable value. The
checker has no Optional built-ins, so unwrapping can hide a null.

Direction: register `Optional.orElse` as nullable-if-argument-nullable (`orElseGet` unknown,
`orElseThrow`/`get` non-null), and keep `findFirst`/`findAny` non-null. This is Java interop,
not a Groovy collection guard.

Priority: low/medium (only when the project uses Java streams).

## Real-world trial (anonymized)

A multi-module Groovy service (six modules, 251 Groovy files) was checked in strict mode. After
configuring the project's own contracts, 13 findings remained: two modules had findings (4 and
9), the other four had none.

The project needed its own codebase conventions declared; generic examples:

```groovy
nullcheck {
    strict = true
    neverReturns = [
        'com.example.support.Guard#fail',
        'com.example.support.Guard#unexpected',
    ]
    guardNotNullWhenFalse = [
        'com.example.support.Collections#isEmpty',
    ]
    guardNotNullWhenTrue = [
        'com.example.support.Collections#isNotEmpty',
    ]
}
```

Triage of the remaining findings:

| Class | Shape | Action |
| --- | --- | --- |
| likely real | map lookup then dereference; collection lookup before `size()`/`sort()` | guard the lookup or prove the key is present |
| fixed | safe-navigation + `continue` | receiver narrowing added; see Completed |
| fixed | `max` after a non-empty guard | non-empty guard fact added; see Completed |
| resolution (report-only) | dynamic helper call; GString where `String` is expected | clean up in the project or keep `failOn` excluding `resolution` |

The trial did not change the external project's sources.

### Post-flow re-run

After F1-F6, the same external service was checked again in strict mode without changing its
sources: six modules with sources, 251 Groovy files, 397 classes. It reported 12 findings
(10 null, 2 resolution). Triage found the same shape families as before: map/collection
lookups that can miss, nullable locals, and dynamic resolution. No new false-positive shape
attributable to the CFG work was identified; the two previous flow false positives remain
fixed.


