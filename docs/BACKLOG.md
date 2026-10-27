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

### SpotBugs analyses test bytecode

`spotbugsTest` is enabled in both modules. The shared filter removes only the Spock artefact
(`UPM_UNCALLED_PRIVATE_METHOD` on `*Spec` classes); the rest of the test bytecode is now
analysed.

### Finding classification has one owner

`NullCheckCategories` owns the message-to-category table and labels. `NullCheckFinding` and the
harness delegate to it; the harness keeps only the enum mapping. Compile mode, the CLI and the
harness therefore classify a message identically.

### Java Optional/Stream unwrapping

`Optional.orElse` now follows its argument (`orElse(null)` is nullable), `orElseGet` is
conservatively nullable, and `orElseThrow` / `get` / `Stream.findFirst` / `findAny` are
non-null. `positive/optional_or_else.groovy` covers both the nullable and non-null forms.

### Compile mode has a canary

Compile mode registers a `nullcheckCanary` task that runs the checker CLI on a generated
`@CompileStatic` sample with an `@Nullable` field. It expects exit code 1 (a finding) and
`nullcheck` depends on it when `canary = true`. A broken checker can no longer make compile-mode
findings look clean.

### Compile mode is static-only

`compileCheck` now injects the extension only into `@CompileStatic` / `@TypeChecked` / `@NullCheck`
classes or methods. Dynamic classes keep compiling untouched, so their resolution findings no
longer become compiler errors; mixed static/dynamic projects can use compile mode.

### Dogfood runs the current release

The self-check now uses the published plugin and checker `0.7.3`. It immediately found one real
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


