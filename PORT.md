# Port notes, deviations and known limitations

This checker is a source-level back-port of Apache Groovy's `NullChecker` (tag
`GROOVY_6_0_0`) to Groovy 4.0.x. This document records what differs from upstream, why, and
what is known not to work — so you can judge whether it is safe for your codebase instead of
discovering the edges yourself.

Upstream baseline:

| file | sha256 |
| --- | --- |
| `NullChecker.groovy` | `0ed46916b516157883d1fb593eb1891f66c7839a926966f3904ef8e5837c3e0b` |
| `CheckingVisitor.groovy` | `99df010de442efd25184200a1bcdf8331b5e1e41a661580950057e53a30d6e8f` |

---

## 1. Why the source was ported rather than the jar reused

Groovy 4's `org.apache.groovy:groovy-typecheckers` ships only `RegexChecker`; `NullChecker`
was added in 6.0.0 and is not present in any 4.x release (verified through 4.0.33). Moving
the Groovy 6 jar onto a Groovy 4 classpath fails at link time, because Groovy 6's
`groovy-macro` module needs AST classes that do not exist in 4.

Recompiling the source against Groovy 4 works. No analysis logic was rewritten: the
deviations below are the complete set.

---

## 2. Deviations from upstream

### 2.1 Compile-time adaptations (3)

| # | Upstream | Groovy 4 reality | What was done |
| --- | --- | --- | --- |
| 1 | `@Incubating` on the class | Annotation added in 6.0 | Removed. It is a marker only, with no semantics |
| 2 | `StaticTypesMarker.INFERRED_NON_NULL_RETURN_VIOLATIONS` | Absent: Groovy 4's `StaticTypesMarker` has 19 fields against Groovy 6's 23 | Read by string key. **Capability loss**, see §3.1 |
| 3 | `StaticTypesMarker.INFERRED_NON_NULL` | Absent, as above | Read by string key. **Capability loss**, see §3.1 |

An exhaustive audit of every Groovy-side class member referenced by both source files found
exactly these, plus the two items below. No Groovy API class is missing.

### 2.2 Extension options: `strict` is configured out of band (1)

Upstream reads `boolean strict = options?.strict`, which Groovy 6 populates from a
parameterised extension name:

```groovy
@TypeChecked(extensions = 'groovy.typecheckers.NullChecker(strict: true)')   // Groovy 6 only
```

**Groovy 4 cannot do this.** Its `GroovyTypeCheckingExtensionSupport.setup()` calls
`loadClass()` on the *whole* string, so a parameterised name is never found; and
`StaticTypesTransformation` hard-codes that support class, so a subclass is never consulted
either. The resulting message — `... was not found on the classpath` — reads like a typo,
which makes this easy to misdiagnose.

Use one of these instead:

```groovy
// programmatic, for a plugin or a custom task
NullChecker.setOptions([strict: true])

// build-wide
-Dgroovy.nullchecker.strict=true

// environment, most convenient for CI
GROOVY_NULLCHECKER_STRICT=true
```

### 2.3 Local variable declarations: annotations are read from a different node (1)

For `@Nullable String x`, Groovy 6 attaches the annotation to `decl.variableExpression` while
Groovy 4 attaches it to the `DeclarationExpression` itself. Upstream's single-node lookup
therefore finds nothing on Groovy 4, making **every annotation rule about local declarations
silently skip**.

The port checks whichever node carries the annotation, so the same code is correct on both.
This is the one place where behaviour differs from upstream — see §4.1.

### 2.4 Package name

Upstream's two classes live in `groovy.typecheckers` and `org.apache.groovy.typecheckers`.
Neither name can be reused:

- `org.apache.groovy.typecheckers.CheckingVisitor` **already exists in Groovy 4.0.x**, and
  Groovy 4's own `RegexChecker` uses it. The two are not interchangeable: Groovy 4 declares
  `findTargetVariable` as `static`, Groovy 6 as an instance method. Shadowing it would break
  `RegexChecker`.

Both classes therefore live in `io.github.heyuch.groovy.nullchecker`.

### 2.5 Java 9+ APIs avoided (2)

The build targets Java 17, so upstream's `Set.of` calls would compile here without complaint.
The port uses the Java 8 equivalents anyway, so that lowering the target JDK later — if that is
ever wanted — stays a build-config change rather than a source change.

| # | Upstream | Groovy 4 reality | What was done |
| --- | --- | --- | --- |
| 1 | `Set.of(...)` for the annotation-name constants | Java 9+ | `Collections.unmodifiableSet([...] as Set)`. Same immutability and iteration order; these are only ever queried with `contains` |
| 2 | `Set.of(var)` in `narrowToNonNull` | Java 9+ | `Collections.singleton(var)`. The null case is already excluded by the enclosing guard |

Both carry a `PORT:` marker at the point of change, so a reader diffing against GROOVY_6_0_0
will see them. Nothing else in either file reaches past Java 8.

Worth recording for the day the target does move: the toolchain, not
`sourceCompatibility`/`targetCompatibility`, is what limits which JDK APIs the compiler can see.
Java sources get an extra guarantee from `options.release`; Groovy sources get none, so a Groovy
file can reach a newer API without a warning and fail only on the consumer's machine.

---

### 2.6 Behavioural fixes adopted deliberately (3)

The port is a fork: three upstream defects are fixed here rather than reproduced. Each is
listed in `nullchecker/tools/upstream-divergences.txt`, and each has a corpus fixture that
pairs the tolerated shape with one that must still be reported.

| # | Upstream behaviour | What this port does | Why |
| --- | --- | --- | --- |
| 1 | `xs*.prop` is flagged safe, so its result is treated as nullable and the following call is reported | the spread property result is a `List` and never null | Groovy marks it safe because it tolerates a null receiver, not because it can be null. The defect fires in default mode as well, on the most ordinary collection code |
| 2 | `continue` and `break` do not terminate a path, so `if (x == null) continue` narrows nothing | both count as path exits, like `return` and `throw` | The statements after the `if` run only when the branch was not taken. This is the narrowest loop guard in real code |
| 3 | `jakarta`/`javax` validation `@NotNull` is honoured as a nullness contract *and* as a demand for definite initialisation | the contract is kept; only the initialisation requirement is suspended, and not inside a `@NullMarked` class | A runtime validation constraint promises non-null at validation time, not that the constructor assigned the field. Beans populated by an ORM or a deserializer start empty by design |

### 2.7 Scoped package-level defaults (3)

Upstream reads package annotations not at all, and this port previously modelled a default as
a single on/off switch, so only `@NullMarked`-style markings could be expressed at package
level. JSR-305 and Spring split the default per scope, so the switch is now resolved per
scope: parameters (`@ParametersAreNonnullByDefault`), return values
(`@ReturnValuesAreNonnullByDefault`), fields (`@NonNullFields`), and both at once
(`@NonNullApi`), with `@NullMarked` covering all three.

| Scope | Names honoured in addition to `@NullMarked`/`@NonNullByDefault` |
| --- | --- |
| Parameters | `ParametersAreNonnullByDefault`, `ParametersAreNonNullByDefault`, `NonNullApi` |
| Return values | `ReturnValuesAreNonnullByDefault`, `NonNullApi` |
| Fields | `NonNullFields` |

`ReturnValuesAreNonnullByDefault` is part of the JSR-305 specification but absent from the
reference jar, so a project that uses it supplies the annotation itself; matching is by simple
name, as everywhere else. `@NullUnmarked` still opts out of every scope.

### 2.8 Contract annotations and stronger validation constraints (2)

Two additions upstream does not have, both about *where* a non-null guarantee is written:

| # | Addition | Effect |
| --- | --- | --- |
| 1 | `@EnsuresNonNullIf(expression='#N', result=?)` and `@EnsuresNonNull(expression='#N')`, matched by simple name | A helper carries its own guard contract, so callers need no configuration and the contract is visible where the method is defined. Argument references other than `#N`, and out-of-range indexes, are ignored rather than guessed at |
| 2 | `@NotEmpty` and `@NotBlank` are non-null annotations, alongside `@NotNull` | Both constraints reject null before examining content. The other Bean Validation constraints accept null and are deliberately not included |

`org.hibernate.validator.constraints` is treated as part of the Bean Validation family for the
initialisation rule, so its constraints do not demand initialisation either.

A reversed `result` on `@EnsuresNonNullIf` narrows the wrong branch, so the guarded code is
still reported. That is the intended failure direction: a mislabelled contract must not be
able to hide a finding.

### 2.9 Container elements handed out by iteration (2)

Upstream recognises the element of `List<@Nullable E>` only where the value comes from a
subscript, `get(...)` or a DGM accessor such as `first()`. A `for`-each variable and a closure
parameter receive the same element, but Groovy does not copy the type-use annotation onto the
variable it introduces, so both used to look non-null.

This port records those variables as they enter scope and treats them as nullable for the
existing dereference rules. A check narrows them, safe navigation avoids the dereference, and
`List<String>` without the annotation reports nothing — pinned by
`negative/element_iteration_guarded.groovy` and its positive counterpart.

Maps are tracked per half: the entry is never null, but `entry.key` and `entry.value` carry
the key and value types of the map being iterated, and `each`/`eachWithIndex` receive
`(key, value)` rather than the entry. Reading through the map, `values()`, `keySet()` and
`collect { it.value }` are covered too. An untyped `Map.Entry` variable and `entrySet()`
iteration lose those types before the checker sees them, and are left alone.

## 3. Known capability loss

### 3.1 Type-inference-driven checks are weaker

The two absent `StaticTypesMarker` fields (§2.1) mean the two code paths that consult
Groovy's *inferred* non-null information go quiet on Groovy 4. The annotation-driven paths
still cover the same situations when you annotate explicitly, but if you rely on inference
alone this port may report **less** than Groovy 6 would.

### 3.2 Locally declared annotations behave differently

See §4.1. In short: this port reports a locally declared `@NonNull` initialised with null;
Groovy 6 does not.

### 3.3 Precompiled package annotations need runtime retention

Package-level defaults are resolved from three sources: a source `package-info` in the same
compilation, Groovy's package node, and — for a precompiled dependency — a reflective lookup
of `<pkg>.package-info`. The reflective path can only see annotations with `RUNTIME`
retention. The common nullness annotations (JSpecify, JSR-305, JetBrains, Checker Framework)
are runtime-retained, so this rarely matters, but a `CLASS`-retained package annotation in a
dependency will be invisible.

### 3.4 Monotonic state does not cross method boundaries

For `@MonotonicNonNull`, the state that tracks "this has held a non-null value" is per
visited method body. Setting the field in one method and reassigning it to `null` in another
is not reported. Keep the initialisation and the invalidating assignment in the same method
if you rely on this rule.

---

### 3.5 Map iteration is deliberately not tracked

Iterating a `Map<@Nullable K, V>` yields `Map.Entry`, not the key; `Map.each` on the other hand
passes the key and the value as two closure parameters. Distinguishing the two requires knowing
each method's closure arity and semantics, and getting it wrong would make every entry
dereference in a map with nullable keys a finding. Maps are therefore excluded from element
tracking entirely: a nullable key or value is not followed into iteration. Accessing an element
directly (`map[key]`, `map.get(key)`) still yields the value type and is checked as before.

## 4. Behavioural comparison with upstream

The corpus is run under both implementations and the results are diffed
(`./gradlew dualRun`). Line numbers are normalised away first, so a finding that merely moves
is not reported as a difference.

Five divergences remain, all intended: two additions (4.1, 4.2) and three fixes (4.3).

### 4.1 Locally declared `@NonNull`

| Fixture | This port | Groovy 6 |
| --- | --- | --- |
| `positive/nonnull_local_declaration.groovy` (`@NonNull String s = null` as a *local*) | **reports** | does not report |

This is deliberately kept rather than aligned away. Removing the local-declaration check
would cost Groovy 4 users a whole rule family, and the difference is an overlay of
implementation, AST and Groovy-version behaviour that cannot be isolated without changing
verified behaviour.

### 4.2 Package-level defaults

| Fixtures | This port | Groovy 6 |
| --- | --- | --- |
| `pkg/marked/MarkedByPackage.groovy`, `pkg/marked/FieldsFromPackage.groovy` | **report** | do not report |

Upstream does not read package annotations at all, so a `@NullMarked` package has no effect
there. That difference appears on the `only-in-port` side of the diff, and it is the feature
described in the README rather than a regression.

### 4.3 The three fixes

| Fixture | This port | Groovy 6 |
| --- | --- | --- |
| `negative/spread_property_result.groovy` | silent | reports the dereference (and, in strict mode, the local it is assigned to) |
| `negative/guards_loop_exit.groovy` | silent | reports the guarded variable after `continue` / `break` |
| `negative/validation_notnull_field.groovy` | silent in strict mode | reports both fields as uninitialised |

These are the FP-1, FP-2 and FP-4 cases from §2.6. They appear on the `only-in-reference` side
of the `dualRun` diff, and `dualRun` fails when one of them stops occurring, so the list
cannot drift into a description of a comparison that no longer happens.

### 4.4 Everything that does match

Annotated returns, parameters and fields; `@MonotonicNonNull` reassignment; dereferences of
`@Nullable` values, `@Nullable`-returning calls, safe-navigation results and `@Nullable`
properties; `null` passed to `@NonNull` parameters; and in strict mode flow-sensitive
dereferences and definite-initialisation of `@NonNull` fields.

All guard forms are silent in both implementations: `if (x != null)`, `else` branches, early
`return`/`throw`, `?.`, Groovy truth, `&&`/`||`, `instanceof`, `Objects.nonNull`/`isNull`,
`assert`, elvis, ternary, `while`, `requireNonNull`/`checkNotNull`, `assertNotNull` and
fluent `assertThat(x).isNotNull()` chains.

---

## 5. Notes on the porting work

These were the parts that were not obvious, recorded so they are not rediscovered.

### 5.1 `@CompileStatic` classes need an explicit registration phase

Registering the checker with a bare `ASTTransformationCustomizer` never reaches
`@CompileStatic` classes, and the failure is silent: a project whose classes are all
statically compiled reports zero findings that mean "nothing was checked". A source-level
`@TypeChecked` does not help either.

The fix is to declare the registration phase, which needs a wrapper because
`ASTTransformationCustomizer` cannot be subclassed from Groovy — its constructor assigns
`annotationNode` through a dynamic property write, which throws
`MissingPropertyException: No such property: annotationNode` as soon as a Groovy subclass is
instantiated. `NullCheckCustomizer` wraps it by composition.

Measured against a `@CompileStatic` class: `CONVERSION`, `SEMANTIC_ANALYSIS` and
`CANONICALIZATION` all report; `INSTRUCTION_SELECTION` does not, because the type-checking
transformation has already been processed by then.

### 5.2 A comparison tool needs the same fix as the thing it compares

The corpus is also compiled by a separate probe (`nullchecker/tools/dual-run.groovy`) so the
same fixtures can run under both implementations. That probe originally registered the
extension the obvious way, with a bare `ASTTransformationCustomizer` — the same mistake
described in §5.1, in the tool rather than the product.

The effect was worse than a wrong number. `@CompileStatic` fixtures reported nothing under
**both** implementations, so the diff showed them as agreeing while measuring neither. The
comparison silently stopped covering exactly the class shape it exists to guard, and the
fixtures' own gate could not notice, because that gate checks findings rather than the
absence of a comparison.

The probe now declares the registration phase the same way the product does. Worth
remembering when adding any second way to invoke the checker: it needs the same
configuration, and the way to tell is to check that the comparison still moves when the
product changes.

### 5.3 Groovy instantiates the type-checking DSL once per class

Coverage counters must not be reset inside `run()`: Groovy creates a new DSL instance and
calls `run()` once per checked class (measured: 50 calls for 43 sources), so a reset there
leaves the counters describing only the last class seen. The reset belongs to the caller,
once per compilation.

### 5.4 Gradle compiles configuration scripts with an isolated classloader

A Gradle `configurationScript` cannot import classes from the project's compile classpath,
and `Class.forName` does not help — it throws `ClassNotFoundException` even with the checker
declared as a dependency, including when the dependency is changed from `compileOnly` to
`implementation`. A wrapper used from a configuration script must therefore be self-contained.
`NullCheckCustomizer` is intended for programmatic use; the `nullcheck` task avoids the
problem entirely by running the CLI in its own process.

### 5.5 `extensions` must be a String

The annotation member is declared `Class[]`, but at runtime only a `String` is honoured.
Passing a `Class` or `ClassExpression` is silently ignored, producing a green build that
checked nothing.

### 5.6 Annotations must be `RUNTIME`-retained

With `CLASS` retention the checker does not see the annotation and every rule based on it
goes silent. This one is easy to hit with a hand-written annotation.

### 5.7 `x in y` is not an obstacle

`ScriptBytecodeAdapter.isIn` does not exist in Groovy 4.0.30, but because the source is
recompiled against Groovy 4 rather than the jar being moved, the compiler emits Groovy 4's own
implementation. No rewriting was needed.

---

## 6. What is not verified

- **Semantic equivalence beyond the corpus.** The comparison in §4 covers the fixtures in
  `corpus/`. Type inference in Groovy 4 and 6 is not identical, and the corpus is not a proof
  of equivalence on arbitrary code.
- **Noise and miss rates on large codebases.** The checker has been run on multi-module
  projects of moderate size, but the false-positive and false-negative rate on a large legacy
  codebase is not measured. Start in report mode.
