# nullchecker

English | [简体中文](README.zh-CN.md)

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
    compileOnly 'io.github.heyuch:groovy-nullchecker:0.10.0'
}
```

You do **not** need `groovy-typecheckers`, and you do not need to add an annotation library —
use whichever your project already has.

If you would rather not depend on a repository at all, every release also attaches the jars
directly. `groovy-nullchecker-<version>.jar` is self-contained — drop it on the compile
classpath and route B or C works with no coordinates and no repository:

```groovy
dependencies {
    compileOnly files('libs/groovy-nullchecker-0.10.0.jar')
}
```

That jar has no dependencies of its own: you supply Groovy and your annotations, which a
Groovy project has anyway. (The plugin is not usable this way — Gradle needs a plugin marker
in a repository, so install it by id.)

### A. Gradle plugin (recommended — one line)

Full DSL in [nullchecker-gradle-plugin](nullchecker-gradle-plugin/README.md):

```groovy
plugins {
    id 'io.github.heyuch.groovy-nullchecker' version '0.10.0'
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

## Annotating your code

How you annotate is what decides what the check can see. Annotations are matched by **simple
name**, so any library works; the examples use JSpecify, which is the one to pick for new code:

```groovy
compileOnly 'org.jspecify:jspecify:1.0.0'
```

Whatever you choose must be on the **compile classpath** of the modules being checked. The
checker resolves the annotation classes themselves, including ones inside compiled
dependencies — an annotation it cannot resolve is silently ignored.

### What you mean, and what to write

| You mean | Write | Place |
| --- | --- | --- |
| this package is non-null by default | `@NullMarked` | `package-info.java` |
| this **value** may be null | `@Nullable` | return type, parameter, field, local variable |
| this **container's elements** may be null | `@Nullable` inside the type argument | `List<@Nullable X>`, `Set<@Nullable X>` |
| this **map value** may be null | `@Nullable` in the value position | `Map<K, @Nullable V>` |
| this **map key** may be null | `@Nullable` in the key position | `Map<@Nullable K, V>` |
| this package is not ready for this yet | `@NullUnmarked` | class or `package-info.java` |
| this helper proves its argument non-null | `@EnsuresNonNullIf`, `@EnsuresNonNull`, JetBrains `@Contract` | on the helper |

### Start with one package

```java
// src/main/java/com/example/order/package-info.java
@org.jspecify.annotations.NullMarked
package com.example.order;
```

Everything in that package is non-null by default, so you only mark the exceptions. Packages
you have not touched behave as before; adopt one at a time.

### The nullable value

```groovy
@NullMarked
class OrderService {
    @Nullable
    Order find(String id) {          // may return nothing
        return repository.find(id)
    }

    void apply(@Nullable String note) {   // caller may pass nothing
    }

    @Nullable
    String remark                      // may be absent on this bean
}
```

Callers must then check the value before dereferencing it, and the check accepts every normal
guard: `if (order != null)`, `order?.total() ?: 0`, an early `return`, `continue` in a loop.

### The nullable container versus its elements

These two are different, and the difference is the whole feature:

```groovy
@Nullable List<String> maybeMissing    // the list itself may be null
List<@Nullable String> mayContainNull  // the list exists, some elements are null
```

For the second, every way of taking an element out yields something nullable, so it is
reported unless checked:

```groovy
names[0].length()               // subscript
names.get(0).length()           // get
names.first().length()          // DGM accessor
for (String n : names) n.length()        // for-each variable
names.each { it.length() }               // closure parameter and implicit it
```

Maps work per half:

```groovy
Map<String, @Nullable String> byCode     // value may be null
Map<@Nullable String, String> byName     // key may be null

byCode.get(code).length()
byCode[code].length()
for (String v : byCode.values()) v.length()
for (String k : byName.keySet()) k.length()
for (Map.Entry<String, String> e : byCode) e.value.length()
byCode.each { k, v -> v.length() }
```

The annotation describes what reading an element may give you; it does not by itself police
what is put into the collection. Detailed rules, and the two shapes that are not tracked, are
in [Container elements](#container-elements).

### The helper that proves non-null

When the guard is a method, say so on the method instead of at every call site:

```groovy
// returns false when the collection is null or empty
@EnsuresNonNullIf(expression = '#1', result = false)
static boolean isNullOrEmpty(Collection<?> values) { ... }

// throws for a null argument, so after the call it is non-null
@EnsuresNonNull(expression = '#1')
static <T> T require(T value) { ... }

// the same two, for a project that already annotates helpers for IntelliJ
@Contract('null -> true')  static boolean isNullOrEmpty(Collection<?> values) { ... }
@Contract('null -> false') static boolean isNotNullOrEmpty(Collection<?> values) { ... }
```

No build configuration is involved, and it works from a compiled dependency. The polarity has
to match what the method actually does: a reversed declaration narrows the wrong branch and
the guarded code is still reported.

### Bean Validation constraints

`@NotNull`, `@NotEmpty` and `@NotBlank` are read as non-null contracts, because all three
reject null before examining anything. They are not a requirement to initialise a field in
source — a bean populated by an ORM or a mapper is valid as long as it is non-null when
validation runs. Use `@Nullable` (not the absence of `@NotNull`) to say that a value may be
missing; `@Size`, `@Pattern`, `@Email` and the rest accept null and say nothing about it.

### Framework-managed fields

Under `@NullMarked` every field is non-null, so strict mode asks where it is assigned — and a
field injected by Spring, an ORM or a mapper never is. Turn that one rule off for those
modules:

```groovy
nullcheck {
    initializationCheck = false
}
```

### An order that works

1. `@NullMarked` on one leaf package (`package-info.java`), run the check with `strict = true`.
2. Mark the return values that can be empty with `@Nullable`, then fix the callers.
3. Mark parameters that accept nothing, then the fields that can be absent.
4. Where a collection may hold nulls, put the annotation on the type argument rather than on
   the collection.
5. Declare the project's own guards once (`@Contract` or the guard lists), so they stop being
   findings.
6. Only then turn on `failOnError` for that module, and repeat in the next package.

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
| Dereferencing a nullable **element** | `List<@Nullable String> xs; xs[0].length()`, `xs.get(0)`, `xs.first()` |
| Dereferencing a nullable element from iteration | `for (String s : xs) s.length()`, `xs.each { it.length() }` |
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

> Retention: annotations written in source are always visible. In a compiled dependency a
> declaration annotation is read from the class file, so `CLASS` works there too — the
> annotation's own class has to be on the compile classpath. A precompiled
> `package-info.class` is read through reflection and does need `RUNTIME`. Type-use
> annotations in compiled classes are safest with `RUNTIME`, which every common nullness
> library uses.

**Bean Validation's `@NotNull`, `@NotEmpty` and `@NotBlank`** (`jakarta.validation.constraints`,
`javax.validation...`, `org.hibernate.validator.constraints`) are non-null contracts and are
honoured as such: assigning null, passing null and returning null are reported. `@NotEmpty`
and `@NotBlank` reject null before checking content, so they imply non-null; the other
constraints (`@Size`, `@Pattern`, `@Email`, …) accept null and are ignored. None of them is
treated as a requirement to initialise the field in source,
because a bean populated by an ORM or a deserializer is valid as long as it is non-null when
validation runs — so strict mode does not report every mapped field. Inside a `@NullMarked`
class the initialisation requirement stands: the marking is the stronger contract.

### Container elements

The annotation can sit on a type argument, and then it describes the elements rather than the
collection:

```groovy
List<@Nullable String> names
```

Every way of taking an element out of it yields a nullable value, so all of these are
reported unless the value is checked:

```groovy
names[0].length()              // subscript
names.get(0).length()          // get()
names.first().length()         // DGM accessor
for (String name : names) {    // for-each variable
    name.length()
}
names.each { it.length() }     // closure parameter and implicit it
```

A null check narrows the element like any other nullable value (`if (name != null) …`), safe
navigation avoids the dereference, and `List<String>` — without the annotation — reports
nothing.

Maps work the same way, with the key and the value tracked separately:

```groovy
Map<String, @Nullable String> byCode      // the value may be null, the key may not
Map<@Nullable String, String> byName      // and here it is the other way round
```

```groovy
byCode.get(code).length()                 // value
byCode[code].length()                     // value
for (String v : byCode.values()) v.length()          // value
for (String k : byName.keySet()) k.length()          // key
for (Map.Entry<String, String> e : byCode) e.value.length()   // entry half
byCode.each { k, v -> v.length() }        // pairwise closure
byCode.collect { it.value.length() }      // entry inside a closure
```

Two shapes are not tracked: an untyped `Map.Entry` variable, and `entrySet()` iteration — both
lose the key and value types before the checker sees them. Reading through the map still works
in those cases, and there are no false positives from them.

### Lookups that can miss

A container's element type says whether a **stored** value may be null. Some operations can
return null even when it cannot, because the lookup itself may miss:

| Call | Result | Why |
| --- | --- | --- |
| `map.get(k)`, `map[k]` | nullable | the key may be absent |
| `map.remove(k)`, `map.put(k, v)` | nullable | they return the previous value |
| `map.getOrDefault(k, d)` | non-null when `d` is non-null | there is a fallback |
| `list.find { }`, `findResult { }` | nullable | nothing matched |
| `list.max()`, `min()` | nullable | the collection may be empty |
| `list.first()`, `last()` | non-null | on an empty collection they throw |

This is why `Map<String, String>` does not make `get` safe: the values are non-null, but the
key may not be there. Check the lookup, or make it total:

```groovy
map.getOrDefault(code, '').length()
if (map.containsKey(code)) {
    map.get(code).length()      // narrowed: containsKey proved the key is present
}
map.get(code)?.length()
```

`containsKey` only narrows when the values are non-null; with `Map<String, @Nullable String>`
a present key may still hold an explicit null.

The family can be turned off while a project migrates:

```groovy
nullcheck {
    containerLookups = false
}
```

CLI: `--no-container-lookups`.

### Contracts

Everything above is one model internally: a call may narrow its arguments, make its result
nullable, or never return. Three sources feed it, in this order of precedence:

1. **Configuration** — `contracts` in the plugin, `--contract` on the CLI;
2. **Annotations** — JetBrains `@Contract`, `@EnsuresNonNullIf`, `@EnsuresNonNull`,
   `@NoReturn`;
3. **Built-ins** — `Map.get`, `getOrDefault`, `find`, `max`, `Objects.requireNonNull` and the
   rest.

Configuration wins so a library whose annotation is wrong can be corrected without changing
the library:

```groovy
nullcheck {
    contracts = [
        // this project knows the key is present
        'java.util.Map#get'         : '-> !null',
        // the library's own annotation is backwards
        'com.acme.Utils#isNotEmpty' : 'null -> false',
    ]
}
```

CLI: `--contract "java.util.Map#get=-> !null"`.

The syntax is JetBrains':

| Contract | Meaning |
| --- | --- |
| `null -> fail` | throws for a null argument; after the call it is non-null |
| `-> fail` | never returns |
| `null -> true` / `null -> false` | the result reveals whether the argument was null |
| `_, !null -> !null` | the result is nullable exactly when that argument is |
| `-> !null` / `-> null` | the result is always non-null / always null |

Entries are matched by `declaringClass#method`, including on superclasses and interfaces, so
`java.util.Map#get` also covers `HashMap.get`. A malformed contract is ignored rather than
guessed at.

Two things are deliberately **not** contracts: `containsKey` narrowing a later lookup is a fact
about two calls, and an element type (`List<@Nullable E>`) is a type rather than a behaviour.

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

If the project already annotates helpers for IntelliJ, the JetBrains `@Contract` annotation
carries the same information and is understood too:

```groovy
@Contract('null -> fail')      // throws on null, so after the call the argument is non-null
static <T> T require(T value) { ... }

@Contract('null -> false')     // a null argument returns false, so true proves non-null
static boolean isNotEmpty(Collection<?> values) { ... }

@Contract('null -> true')      // and false proves non-null for the isEmpty form
static boolean isNullOrEmpty(Collection<?> values) { ... }
```

Only those two shapes are interpreted. `!null`, `_`, `param1`, `new` and the rest of the
grammar are left alone rather than guessed at.

The real annotation is `CLASS`-retained, which is fine: annotations are read from the class
file, so a contract compiled into a dependency works, including one in another module of the
same build. The annotation's own class must be on the compile classpath — normally true,
because the project needs it to compile the annotation in the first place. Without it the
annotation is silently dropped and the contract does nothing.

A helper that never returns is a guard too. Declare it once and the statements after a call
become unreachable, so the fallback `return null` at the end of the method is not reported:

```groovy
@NoReturn                     // or @Contract('-> fail'), or the neverReturns list
static void giveUp() { throw new IllegalStateException('give up') }

static Object execute() {
    try {
        return doSomething()
    } catch (Exception e) {
        giveUp()
    }
    return null               // not reported: giveUp never returns
}
```

It narrows as well: `if (x == null) giveUp()` leaves `x` non-null afterwards, exactly like
`throw`. `@NoReturn` is matched by simple name, and `@Contract('-> fail')` means "any call fails" —
the empty argument list is valid contract syntax, and `'_, _ -> fail'` says the same thing
per-arity. A single `'null -> fail'` is not enough, because the call returns for every other
argument.

If the method belongs to a library you cannot annotate, declare it instead:

```groovy
nullcheck {
    neverReturns = ['com.acme.Panic#notExpectedResult']
}
```

CLI: `--never-returns com.acme.Panic#notExpectedResult`, repeatable.

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
    markedOnly          = true    // only @NullMarked classes/packages
}
```

`markedOnly` is the progressive-adoption switch: a class is checked only when it (or an
enclosing class) is `@NullMarked`, or its exact package's `package-info` is `@NullMarked`.
`@NullUnmarked` opts out. Unmarked classes are skipped entirely, so a legacy package can be
brought in one leaf package at a time. A referenced signature type that cannot be resolved is reported as a resolution finding and
the affected class is skipped instead of aborting the module; if nothing can be analysed, the
CLI reports an incomplete resolution finding. The switch filters checker findings and does not inject
`@TypeChecked` into unmarked dynamic classes. Groovy's own resolution errors from classes that
already use `@CompileStatic`/`@TypeChecked` still appear (not fail-on by default); hiding those
would require a report-layer filter. To migrate annotations separately, run `./gradlew nullcheckAddNullable` (opt-in; see the plugin README). Extra @NonNull annotation names can be declared with `nonNullAnnotations` (plugin) or
`--nonnull-annotation` (CLI). Assertion helpers can be declared with `assertsTrue` or
`@Contract('false -> fail')`. CLI: `--marked-only`. Report destinations can be combined: `--report file.txt
--report-stdout --report-stderr` write the same full text to each target.

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
    id 'io.github.heyuch.groovy-nullchecker' version '0.10.0'
}

nullcheck {
    failOnError = true                        // default false: report only
    strict      = true                        // optional
    failOn      = ['null', 'initialization']   // default; 'all' to fail on resolution too
    initializationCheck = true                 // default; false drops uninitialised-field reports
    markedOnly  = false                        // true: only @NullMarked classes/packages
    reportToStdout = false                    // also write the full report to stdout
    reportToStderr = false                    // also write the full report to stderr
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
| Annotations | `RUNTIME` always works; a precompiled `package-info.class` needs it |
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
./gradlew dualRun              # informational: run the corpus under both checkers and diff
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
