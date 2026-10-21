# Backlog

Improvements found while using the checker on real codebases. Reproductions are synthetic: they
keep only the shape that triggers the behaviour, not any project's code or names. Priority
reflects impact on real projects, not implementation effort.

## Checker

### 1. Safe-navigation conditions do not narrow the receiver

```groovy
Item item = itemsById.get(id)
if (item?.details?.kind != Kind.SPECIAL) {
    continue
}
String value = item.details.value   // reported: item may be null
```

When `item` is null the safe-navigation chain yields null, `null != SPECIAL` is true, and the
loop continues; after the `if`, `item` and `details` are necessarily non-null. The checker does
not model the value of a safe-navigation chain, so it cannot narrow the receiver.

Direction: in `NullCheckVisitor`'s guard analysis, record that `a?.b != <non-null constant>`
implies `a` (and `b`, if the chain is longer) is non-null on the branch where the comparison is
false. The symmetric cases (`== null`, `== <non-null constant>`) should follow the same rule.

Priority: high. This idiom is common and currently produces false positives.

### 2. `max` / `min` remain nullable after a non-empty guard

```groovy
if (isNotEmpty(orders)) {
    Order newest = orders.max { it.id }
    return newest                       // reported: newest may be null
}
```

`max`/`min` are registered as partial collection methods, which is right for an empty
collection. In a branch where the same collection is known non-empty they cannot return null.
`find`/`findResult` must stay nullable even then: the predicate can miss.

Direction: let the built-in partial entries consult guard facts for the receiver variable; when
the branch proves the receiver non-empty, narrow `max`/`min` to non-null.

Priority: medium-high. The guard is already understood; only the call result is not.

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

### 6. Dogfood is pinned to 0.3.1

The self-check deliberately uses the last published artifact (`nullcheck.toolVersion = '0.3.1'`)
while the working tree is 0.5.0, so it can lag behind checker fixes.

Direction: bump on each release; consider a second, non-gating run against the local build to
catch regressions before publishing.

Priority: low (process).

### 7. SpotBugs does not analyse test bytecode

`spotbugsTest` is disabled because Spock's dynamic dispatch makes `UPM_UNCALLED_PRIVATE_METHOD`
noisy. Tests are covered by CodeNarc's basic rules and the corpus/functional gates.

Direction: a test-specific filter that keeps the meaningful patterns, if it becomes valuable.

Priority: low.

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
| false positive | safe-navigation + `continue` | tracked as item 1 |
| false positive | `max` after a non-empty guard | tracked as item 2 |
| resolution (report-only) | dynamic helper call; GString where `String` is expected | clean up in the project or keep `failOn` excluding `resolution` |

The trial did not change the external project's sources.
