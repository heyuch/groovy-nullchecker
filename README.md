# groovy4-nullchecker

Compile-time null checking for **Groovy 4**.

Groovy 4 ships no null checker: its `groovy-typecheckers` artifact contains only
`RegexChecker`, and the `NullChecker` that understands `@Nullable` / `@NonNull` was added in
Groovy 6. This library back-ports that checker to Groovy 4.0.x, so null dereferences are
caught while the code compiles instead of in production.

```groovy
@NullMarked
class OrderService {

    String findName(@Nullable String id) {
        return id.length()          // ← reported at compile time
    }
}
```

- **Compile time, no runtime cost.** The check runs during compilation and adds nothing to
  your runtime classpath or published POM.
- **Works on `@CompileStatic` code** — which matters, because that is most real Groovy.
- **No dependency to adopt.** Annotations are matched by simple name, so it works with
  JSpecify, JSR-305, JetBrains, Spring, or your own.
- **Never reports an unverified result.** Before printing anything it proves it can still
  find a violation, and fails loudly if it cannot. See [Why `0` is trustworthy](#why-0-is-trustworthy).

Requires **Groovy 4.0.x** and **Java 17+**. Not for Groovy 3, 5 or 6: a type-checking
extension is a compiler plugin and must match the compiler's major version.

---

## Getting started

### 1. Add the dependency

```groovy
repositories {
    mavenCentral()
}

dependencies {
    // Compile time only. It never becomes part of your runtime classpath.
    compileOnly 'io.github.heyuch:groovy4-nullchecker:0.1.0'
}
```

You do **not** need `groovy-typecheckers`, and you do not need to add an annotation library —
use whichever your project already has.

### 2. Turn the check on

Pick whichever fits. The plugin is the least work; the CLI works anywhere.

**Option A — Gradle plugin (recommended).** Full DSL in
[nullchecker-gradle-plugin](nullchecker-gradle-plugin/README.md):

```groovy
plugins {
    id 'io.github.heyuch.groovy4-nullchecker' version '0.1.0'
}
```

```bash
./gradlew nullcheck
```

**Option B — annotate the classes you want checked.** No build changes at all, which is the
safest way to try it on an existing codebase:

```groovy
import groovy.transform.TypeChecked

@TypeChecked(extensions = 'io.github.heyuch.groovy4.nullchecker.NullChecker')
class OrderService {
    // ...
}
```

> If your classes are `@CompileStatic`, an annotation alone is **not** enough — the checker
> must be registered at the right compiler phase. Use option A or C, or the wrapper described
> in [PORT.md §5.1](PORT.md).

**Option C — run it over a source tree, unmodified.** Everything the plugin does is available
as a CLI, which suits any build system:

```bash
java -cp <checker jar>:<groovy 4 jar>:<your compile classpath> \
     io.github.heyuch.groovy4.nullchecker.harness.NullCheckCli src/main/groovy
```

Runnable consumer projects for both paths are in [examples/](examples/).

---

## What it checks

| Situation | Example |
| --- | --- |
| `@NonNull` method returning null | `@NonNull String f() { null }` |
| Returning a `@Nullable` value | `@NonNull String f() { maybe() }` |
| Assigning null to a non-null field or local | `@NonNull String s = null` |
| Reassigning null after a non-null value | `cached = 'x'; cached = null` |
| Dereferencing a `@Nullable` value | `@Nullable String s; s.length()` |
| Dereferencing a `@Nullable`-returning call | `maybe().length()` |
| Dereferencing a safe-navigation result | `a?.b.c` — write `a?.b?.c` |
| Passing null to a `@NonNull` parameter | `take(null)` |
| Passing a `@Nullable` value to a `@NonNull` parameter | `take(maybe())` |
| **strict mode:** flow-sensitive dereference | `String s = null; s.length()` |
| **strict mode:** uninitialised `@NonNull` field | declared, never assigned |

### Annotations it recognises

Matched by **simple name**, so the package does not matter:

| Meaning | Names |
| --- | --- |
| Nullable | `Nullable`, `CheckForNull`, `MonotonicNonNull` |
| Non-null | `NonNull`, `NotNull`, `Nonnull` |
| Non-null by default | `NullMarked`, `NonNullByDefault`, `NonnullByDefault` |
| Opt out of the default | `NullUnmarked` |

> Annotations must be `RUNTIME`-retained. With `CLASS` retention the checker cannot see them
> and the rules go quiet. All common nullness libraries use `RUNTIME`.

### Guard patterns it accepts

These are recognised and do **not** produce findings:

`if (x != null)` · `if (x == null) else` · early `return` / `throw` · `?.` · `if (x)` and
`if (!x)` · `&&` and `||` short-circuit · `instanceof` · `Objects.nonNull(x)` /
`Objects.isNull(x)` · `assert x` / `assert x != null` · elvis `?:` · ternary ·
`while (x != null)` · `Objects.requireNonNull(x)` / `checkNotNull(x)` · `assertNotNull(x)`
(message arguments included) · `assertThat(x).isNotNull()`

Validator and assertion methods are matched by simple name too, so JUnit, TestNG, AssertJ,
Truth and Guava work without configuration.

---

## Package-level defaults

Mark a package once and every class in it becomes non-null by default, instead of annotating
each class:

```java
// src/main/java/com/example/order/package-info.java
@org.jspecify.annotations.NullMarked
package com.example.order;
```

```groovy
package com.example.order

class OrderService {
    String findName() {
        return null        // ← reported: the package default says this is non-null
    }
}
```

Both `package-info.java` and `package-info.groovy` work, and a precompiled
`package-info.class` in a dependency is read as well. Scope is the annotated package itself:
subpackages are **not** covered and must declare their own.

> This goes beyond upstream — Groovy 6's `NullChecker` does not read package annotations at
> all. See [PORT.md §4.3](PORT.md).

---

## Strict mode

Also tracks values that become null through assignment, in code with no annotations at all:

```groovy
String s = null
s.length()        // strict off: not reported.  strict on: 's' may be null
```

It reports more, so treat it as a second step rather than a starting point.

Groovy 4 **cannot** express this as a parameterised extension name — the Groovy 6 form
`'NullChecker(strict: true)'` fails with a misleading "not found on the classpath". Use one
of these instead:

```groovy
NullChecker.setOptions([strict: true])           // programmatic
```
```bash
-Dgroovy.nullchecker.strict=true                 # JVM property
GROOVY_NULLCHECKER_STRICT=true ./gradlew build   # environment, handy for CI
```

With the Gradle plugin it is just `nullcheck { strict = true }`.

---

## Why `0` is trustworthy

A null checker's dangerous failure is silence: a check that never ran reports `0 findings`,
which is indistinguishable from clean code. Every entry point here guards against that.

**It verifies itself first.** Before reporting any result, the CLI compiles a built-in sample
that is deliberately broken — including a `@CompileStatic` class — and refuses to report
anything unless that sample is caught:

```
canary     = ok (2 findings on the built-in sample, including its @CompileStatic class)
findings   = 0
coverage   = files=43 classes=50 compileStatic=46 nonNullByDefault=50 methodsWithReturnType=226
```

`coverage` is what makes the number below it interpretable: it shows how much was actually
looked at. `compileStatic=46` is the important figure — it is the class shape that silent
misconfiguration skips first.

**Exit codes are meaningful:**

| Code | Meaning |
| --- | --- |
| `0` | No findings |
| `1` | Findings |
| `2` | **The checker did not run** — bad configuration, unresolvable tool, failed self-check |

Exit `2` is never treated as success, because that would turn "nothing was checked" into a
clean bill of health.

---

## Gradle plugin

A `nullcheck` task: no source changes, no compiler configuration script, nothing added to
your runtime classpath.

```groovy
plugins {
    id 'groovy'
    id 'io.github.heyuch.groovy4-nullchecker' version '0.1.0'
}

nullcheck {
    failOnError = true                        // default false: report only
    strict      = true                        // optional
    targets     = ['src/main/groovy', 'src/main/java']
}
```

Full DSL and behaviour: [nullchecker-gradle-plugin/README.md](nullchecker-gradle-plugin/README.md).

---

## Migrating from Groovy 6

| Item | Difference |
| --- | --- |
| Class name | `io.github.heyuch.groovy4.nullchecker.NullChecker`, not `groovy.typecheckers.NullChecker` |
| `strict` | Configured out of band, not as an extension parameter |
| Annotations | Must be `RUNTIME`-retained |
| Local declarations | This port reports a locally declared `@NonNull` initialised with null; Groovy 6 does not |
| Type inference | Checks relying on inferred nullness may report less than Groovy 6 |
| Package annotations | Supported here, not upstream |

The complete list, with reasons and limits, is in **[PORT.md](PORT.md)**.

---

## Project layout

```
src/main/groovy/io/github/heyuch/groovy4/nullchecker/
├── NullChecker.groovy           the checker (back-ported from Apache Groovy)
├── CheckingVisitor.groovy       its base visitor
├── NullCheckCustomizer.groovy   registration that also covers @CompileStatic classes
└── harness/
    ├── NullCheckHarness.groovy  the compiler invocation, in one place
    ├── NullCheckCli.groovy      command-line entry point
    ├── NullCheckCanary.groovy   the built-in self-check sample
    ├── CorpusGate.groovy        corpus gate used by the tests
    └── CorpusRules.groovy       rule inventory behind the coverage gate

corpus/                      paired fixtures: every rule must report, every guard must stay silent
examples/                    runnable consumer projects (plugin and CLI)
tools/                       the CLI wrapper and the comparison probe
nullchecker-gradle-plugin/   the Gradle plugin (a separate build)
docs/                        publishing notes
```

---

## Building and testing

```bash
./gradlew check                # corpus gate: every rule covered, every guard silent
./gradlew dualRun              # run the corpus under this port and under Groovy 6, then diff
./gradlew publishToMavenLocal
```

`./gradlew check` is the core of the project rather than a formality. Every rule needs a
fixture that **must** be reported, every guard form needs one that must stay silent, and a
rule no fixture exercises fails the build — so a rule cannot be added without evidence that
it fires.

The Gradle plugin is a separate build:

```bash
./gradlew -p nullchecker-gradle-plugin check    # drives real Gradle builds via TestKit
```

Releasing is documented in [docs/PUBLISHING.md](docs/PUBLISHING.md).

---

## License

Back-ported from Apache Groovy under the Apache License 2.0. The ported source files keep
their original license headers and carry upstream fingerprints; see [PORT.md](PORT.md).
