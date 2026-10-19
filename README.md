# nullchecker

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

Three ways in. They ask different things of your build — in particular, **only two of them
need a dependency**:

| | what you add | where the checker comes from |
| --- | --- | --- |
| **A. Gradle plugin** | one line in `plugins {}` | the plugin resolves it when the task runs |
| **B. `@TypeChecked(extensions = …)`** | a `compileOnly` dependency | your compile classpath |
| **C. CLI** | a dependency, or an explicit `-cp` | whatever you put on the classpath |

Option A needs no dependency because the plugin never links the checker: it holds the artifact's
coordinates and resolves it at task time, so one line is genuinely enough. What it *does* need is
a repository that can serve the artifact — `mavenCentral()` once it is published, `mavenLocal()`
before that.

Options B and C do need the jar, because there the compiler has to load the extension class:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    // Compile time only. It never becomes part of your runtime classpath.
    compileOnly 'io.github.heyuch:groovy-nullchecker:0.3.0'
}
```

You do **not** need `groovy-typecheckers`, and you do not need to add an annotation library —
use whichever your project already has.

If you would rather not depend on a repository at all, every release also attaches the jars
directly. `groovy-nullchecker-<version>.jar` is self-contained — drop it on the compile
classpath and route B or C works with no coordinates and no repository:

```groovy
dependencies {
    compileOnly files('libs/groovy-nullchecker-0.3.0.jar')
}
```

That jar has no dependencies of its own: you supply Groovy and your annotations, which a
Groovy project has anyway. (The plugin is not usable this way — Gradle needs a plugin marker
in a repository, so install it by id.)

### A. Gradle plugin (recommended — one line)

Full DSL in [nullchecker-gradle-plugin](nullchecker-gradle-plugin/README.md):

```groovy
plugins {
    id 'io.github.heyuch.groovy-nullchecker' version '0.3.0'
}
```

```bash
./gradlew nullcheck
```

### B. Annotate the classes you want checked

No build changes beyond the `compileOnly` dependency above, which makes this the safest way to
try it on an existing codebase:

```groovy
import groovy.transform.TypeChecked

@TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')
class OrderService {
    // ...
}
```

> If your classes are `@CompileStatic`, an annotation alone is **not** enough — the checker
> must be registered at the right compiler phase. Use option A or C, or the wrapper described
> in [PORT.md §5.1](PORT.md).

### C. Run it over a source tree, unmodified

Everything the plugin does is available as a CLI, which suits any build system:

```bash
java -cp <checker jar>:<groovy 4 jar>:<your compile classpath> \
     io.github.heyuch.groovy.nullchecker.harness.NullCheckCli src/main/groovy
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
| Non-null | `NonNull`, `NotNull`, `Nonnull`, `NotEmpty`, `NotBlank` |
| Non-null by default (everything) | `NullMarked`, `NonNullByDefault`, `NonnullByDefault` |
| Non-null **parameters** by default | `ParametersAreNonnullByDefault`, `ParametersAreNonNullByDefault`, `NonNullApi` |
| Non-null **return values** by default | `ReturnValuesAreNonnullByDefault`, `NonNullApi` |
| Non-null **fields** by default | `NonNullFields` |
| Opt out of the default | `NullUnmarked` |

> Annotations must be `RUNTIME`-retained. With `CLASS` retention the checker cannot see them
> and the rules go quiet. All common nullness libraries use `RUNTIME`.

**Bean Validation's `@NotNull`, `@NotEmpty` and `@NotBlank`** (`jakarta.validation.constraints`,
`javax.validation...`, `org.hibernate.validator.constraints`) are non-null contracts and are
honoured as such: assigning null, passing null and returning null are reported. `@NotEmpty`
and `@NotBlank` reject null before checking content, so they imply non-null; the other
constraints (`@Size`, `@Pattern`, `@Email`, …) accept null and are ignored. None of them is
treated as a requirement to initialise the field in source,
because a bean populated by an ORM or a deserializer is valid as long as it is non-null when
validation runs — so strict mode does not report every mapped field. Inside a `@NullMarked`
class the initialisation requirement stands: the marking is the stronger contract.

### Guard patterns it accepts

These are recognised and do **not** produce findings:

`if (x != null)` · `if (x == null) else` · early `return` / `throw` · `?.` · `if (x)` and
`if (!x)` · `&&` and `||` short-circuit · `instanceof` · `Objects.nonNull(x)` /
`Objects.isNull(x)` · `assert x` / `assert x != null` · elvis `?:` · ternary ·
`while (x != null)` · `Objects.requireNonNull(x)` / `checkNotNull(x)` · `assertNotNull(x)`
(message arguments included) · `assertThat(x).isNotNull()`

Validator and assertion methods are matched by simple name too, so JUnit, TestNG, AssertJ,
Truth and Guava work without configuration.

Your own helpers are not guessed — declare them, and say which result means "non-null":

```groovy
nullcheck {
    // returns true  → the first argument is non-null (isNotEmpty, hasValue)
    guardNotNullWhenTrue  = ['com.acme.Utils#isNotEmpty']
    // returns false → the first argument is non-null (isNullOrEmpty, isBlank)
    guardNotNullWhenFalse = ['com.acme.MyCollectionUtils#isNullOrEmpty']
}
```

A helper can also declare its own contract, which is better than a central list when the
method belongs to the project or to a library you control:

```groovy
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf
import org.checkerframework.checker.nullness.qual.EnsuresNonNull

// non-null when the result is false; result = true for the isNotEmpty form
@EnsuresNonNullIf(expression = '#1', result = false)
static boolean isNullOrEmpty(Collection<?> values) { ... }

// non-null after a normal return, the annotation form of requireNonNull
@EnsuresNonNull(expression = '#1')
static <T> T require(T value) { ... }
```

Matched by simple name, so the annotation may come from Checker Framework, from another
library, or be declared by the project. `#1`, `#2` … name the arguments; a reference the
checker cannot interpret (a field path, an out-of-range index) is ignored rather than guessed
at. A reversed `result` narrows the wrong branch and therefore does **not** silence the code it
was meant to cover — that is a deliberate safety property, not an accident.

CLI: `--guard-when-true` / `--guard-when-false`, one entry per flag. Bare checker:
`-Dgroovy.nullchecker.guards.true=...` / `.false`. Entries are matched as
`declaringClass#method`, exactly, and only the first argument is considered. The polarity is
declared rather than inferred: reading it backwards would hide a real finding.

Common library helpers need declaring too — they are not guessed, because a `isBlank` in one
codebase does not always mean what it does in another:

```groovy
guardNotNullWhenFalse = [
    'com.acme.MyCollectionUtils#isNullOrEmpty',
    'org.apache.commons.lang3.StringUtils#isBlank',
]
guardNotNullWhenTrue = [
    'com.acme.MyCollectionUtils#isNotNullOrEmpty',
    'org.apache.commons.lang3.StringUtils#isNotBlank',
    'org.springframework.util.StringUtils#hasText',
]
```

Collecting this list once per project is the whole tuning cost: on a real 251-file module the
only findings left after declaring its own helpers were strict-mode ones — a safe-navigation
result dereferenced without a check.

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
> all. See [PORT.md §4.2](PORT.md).

### Scoped defaults

The JSR-305 and Spring annotations mark one scope at a time, which is usually what a legacy
codebase wants first: parameters are the cheapest thing to make non-null, return values need
`@Nullable` annotations to go with them, and fields are the noisiest (anything a container
assigns never has a constructor assignment).

| Annotation | Parameters | Return values | Fields |
| --- | --- | --- | --- |
| `@ParametersAreNonnullByDefault` (JSR-305) | ✅ | — | — |
| `@ReturnValuesAreNonnullByDefault` (JSR-305) | — | ✅ | — |
| `@NonNullApi` (Spring) | ✅ | ✅ | — |
| `@NonNullFields` (Spring) | — | — | ✅ |
| `@NullMarked` (JSpecify) | ✅ | ✅ | ✅ |

Measured on one real service package (71 files, `--strict`): no annotation 1 finding,
`@ParametersAreNonnullByDefault` 26, `@NonNullApi` 71, `@NullMarked` 195 — the difference is
entirely the scopes above, and the 124 extra under `@NullMarked` are field initialisation
reports for injected fields.

`@NullUnmarked` on a class opts out of whatever the package declares, for every scope.

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

## What fails a run

Findings are categorised, because the checker shares one error collector with Groovy's static
type checker:

| Category | Meaning | Fails by default |
| --- | --- | --- |
| `null` | every nullness rule above | yes |
| `initialization` | an explicitly non-null field no constructor assigns (strict) | yes |
| `resolution` | the static checker could not resolve something in code that is dispatched dynamically at runtime | **no** |

A resolution finding is not a nullness claim — such code compiles and runs — but it does mean
the analysis was incomplete, so it is still printed and still counted. Configure with
`failOn` (plugin) / `--fail-on` (CLI): default `null,initialization`; `all` makes everything
fail, as earlier versions did.

**Choosing what runs** is a separate switch, one boolean per rule family:

```groovy
nullcheck {
    nullnessCheck       = true    // default
    initializationCheck = false   // stop reporting uninitialised fields
}
```

That is not the same as `failOn`: disabling a family removes its findings from the report,
which is what a project needs when it has decided it cannot satisfy the initialisation
requirement yet (fields assigned by a container, for example) rather than teaching everyone
to ignore a wall of output. Both are on by default; turning both off is rejected, because a
run that checks nothing must not look like a clean result. The CLI spells the same choice as
`--checks null`. A project with several modules can set this per module.

With package-level `@NullMarked`, the difference is large: on a service package with injected
fields, `initializationCheck = false` turns 195 findings into 71 — the 124 initialisation reports are
injected fields the container assigns, and the remaining finds are methods that return null
without `@Nullable` and nulls passed to non-null parameters, which is the signal worth acting
on.

```
findings   = 3 (null=2, initialization=0, resolution=1)
fail-on    = null,initialization
note       = 1 finding(s) outside the failing categories (reported, not failing)
```

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
    id 'io.github.heyuch.groovy-nullchecker' version '0.3.0'
}

nullcheck {
    failOnError = true                        // default false: report only
    strict      = true                        // optional
    failOn      = ['null', 'initialization']   // default; 'all' to fail on resolution too
    initializationCheck = true                 // default; false drops uninitialised-field reports
    targets     = ['src/main/groovy', 'src/main/java']
}
```

Full DSL and behaviour: [nullchecker-gradle-plugin/README.md](nullchecker-gradle-plugin/README.md).

---

## Migrating from Groovy 6

| Item | Difference |
| --- | --- |
| Class name | `io.github.heyuch.groovy.nullchecker.NullChecker`, not `groovy.typecheckers.NullChecker` |
| `strict` | Configured out of band, not as an extension parameter |
| Annotations | Must be `RUNTIME`-retained |
| Local declarations | This port reports a locally declared `@NonNull` initialised with null; Groovy 6 does not |
| Spread results | `xs*.prop` is never null here; Groovy 6 reports the following call |
| Loop exits | `continue` and `break` narrow like `return` here; Groovy 6 only recognises `return`/`throw` |
| Validation constraints | Bean Validation `@NotNull` keeps its contract but does not require initialisation; Groovy 6 requires it |
| Type inference | Checks relying on inferred nullness may report less than Groovy 6 |
| Package annotations | Supported here, not upstream |

The complete list, with reasons and limits, is in **[PORT.md](PORT.md)**.

---

## Project layout

Two sibling subprojects under a root that holds no source of its own:

```
nullchecker/                 the checker itself
├── src/main/groovy/io/github/heyuch/groovy/nullchecker/
│   ├── NullChecker.groovy           the checker (back-ported from Apache Groovy)
│   ├── CheckingVisitor.groovy       its base visitor
│   ├── NullCheckCustomizer.groovy   registration that also covers @CompileStatic classes
│   └── harness/
│       ├── NullCheckHarness.groovy  the compiler invocation, in one place
│       ├── NullCheckCli.groovy      command-line entry point
│       ├── NullCheckCanary.groovy   the built-in self-check sample
│       ├── CorpusGate.groovy        corpus gate used by the tests
│       └── CorpusRules.groovy       rule inventory behind the coverage gate
├── corpus/                  paired fixtures: every rule must report, every guard must stay silent
└── tools/                   the CLI wrapper and the comparison probe

nullchecker-gradle-plugin/   the Gradle plugin
examples/                    runnable consumer projects (plugin and CLI)
docs/                        publishing notes
```

The plugin does not depend on `:nullchecker`. It passes the checker's class names as string
constants and lets a detached configuration resolve the artifact by coordinate at execution
time, so the two modules compile independently.

---

## Building and testing

```bash
./gradlew check                # corpus gate: every rule covered, every guard silent
./gradlew dualRun              # run the corpus under this port and under Groovy 6, then diff
./gradlew publishToMavenLocal  # install both modules into ~/.m2
```

`./gradlew check` is the core of the project rather than a formality. Every rule needs a
fixture that **must** be reported, every guard form needs one that must stay silent, and a
rule no fixture exercises fails the build — so a rule cannot be added without evidence that
it fires.

The plugin's functional tests drive real Gradle builds through TestKit, so they are the
slower half of the same gate:

```bash
./gradlew :nullchecker-gradle-plugin:functionalTest
```

Releasing is documented in [docs/PUBLISHING.md](docs/PUBLISHING.md).

---

## License

Back-ported from Apache Groovy under the Apache License 2.0. The ported source files keep
their original license headers and carry upstream fingerprints; see [PORT.md](PORT.md).
