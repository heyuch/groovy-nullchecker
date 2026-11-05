# Groovy compatibility inventory

- Status: G1 inventory; multi-version support is still not implemented.
- Current primary target: Groovy **4.0.30** (`groovyVersion` in `gradle.properties`).
- Reference implementation for `dualRun` only: Groovy **6.0.0** (`referenceGroovyVersion`).
- Java baseline: 17.
- Goal: [ROADMAP.md](ROADMAP.md) G1.

## Current matrix

| Groovy line | Status |
| --- | --- |
| 4.0.x | supported; main release lane |
| 3.x | not supported |
| 5.0.2 | compile + per-fixture CorpusGate pass; plugin/artifact support not claimed |
| 6.0.0 | compile + per-fixture CorpusGate pass; plugin/artifact support not claimed |

Do not change this table without corpus and plugin-functional evidence for the named line.

## Compiler API inventory

| API / surface | Used by | Why it matters |
| --- | --- | --- |
| `GroovyTypeCheckingExtensionSupport.TypeCheckingDSL` | `NullChecker` | entry point and lifecycle of the type-checking extension |
| `TypeCheckingContext` | `NullChecker` | source/compilation context and error reporting |
| `ASTTransformationCustomizer` | `NullCheckCustomizer` | injects `@TypeChecked` / `@CompileStatic` checking |
| `CompilationCustomizer`, `CompilePhase`, `CompilationUnit` | customizer / harness | registration phase and per-compilation state |
| `StaticTypesMarker.INFERRED_TYPE`, `DIRECT_METHOD_CALL_TARGET` | `AstSupport`, `NullCheckVisitor`, `ContractRegistry` | type and call-target metadata used by contracts and guards |
| `ClassNode`, `MethodNode`, `FieldNode`, `Parameter`, `AnnotatedNode` | checker | declaration/annotation model |
| `PackageNode` / `package-info` | `PackageScopes` | package-level nullness defaults |
| `Phases.CONVERSION` / `CANONICALIZATION` | annotator / customizer | parsing vs type-checking phase |
| `CompilerConfiguration` | harness | customizer and classpath setup |
| `Thread.currentThread().contextClassLoader` | harness / plugin | extension loading and project classpath |

## Known version-sensitive points

These are already visible in `PORT.md` and the source comments; each needs a regression
test per supported Groovy line:

1. **`ASTTransformationCustomizer` construction** writes `annotationNode` dynamically; Groovy
   subclassing is not portable, hence composition in `NullCheckCustomizer`.
2. **Type-checking registration phase** must be explicit (`CANONICALIZATION`); a bare
   customizer silently misses `@CompileStatic` classes.
3. **Marker metadata** such as `INFERRED_NON_NULL` / `INFERRED_NON_NULL_RETURN_VIOLATIONS`
   is not present in Groovy 4. The checker already skips an unreachable marker branch.
4. **Type-use annotation placement** differs between Groovy 4 and 6:
   `AstSupport.declAnnoCarrier` deliberately checks both declaration and variable nodes.
5. **`package-info` visibility** differs between source and precompiled class; the
   classpath loader path must be verified per major.
6. **Closure dispatch** changes when a class is made static; the existing compile-mode test
   must stay in the matrix.
7. **Classloader separation** between the customizer and extension matters; per-major
   artifacts must not mix checker/compiler classloaders.

## Proposed version boundary

Do **not** ship one binary that claims every Groovy major. A type-checking extension is a
compiler plugin; binary compatibility is not guaranteed. The likely shape is:

- one source implementation with a small `GroovyCompat` layer for marker names,
  annotation carriers and phase/lifecycle differences;
- one artifact per supported Groovy major (classifier or coordinate), with explicit
  `toolVersion` values in the Gradle plugin;
- unsupported versions fail with a clear diagnostic, not a fallback to an incompatible jar;
- the existing Groovy 4 artifact stays the primary release lane until another lane passes all
  gates.

## CI and acceptance

For each advertised line:

- compile the checker against that Groovy major;
- run the complete positive/negative corpus in annotation and strict modes;
- run the Gradle plugin functional suite;
- run the CLI on a small project;
- record performance and known limitations.

The main Groovy 4 lane must remain free of behavioral and performance regressions.

## Compile spike results

Run with the existing build but an overridden compiler version:

```bash
./gradlew :nullchecker:compileGroovy -PgroovyVersion=5.0.2 --rerun-tasks
./gradlew :nullchecker:compileGroovy -PgroovyVersion=6.0.0 --rerun-tasks
```

Both compilations now succeed after three portable fixes:

- replace ambiguous `System.properties.with { setProperty(...) }` calls in `NullCheckCli` with
  an explicit helper;
- cast Groovy 6's `Number` result from `Collection.count(Closure)` in `Coverage` and
  `NullableAnnotator`.

The earlier `classes=0` runtime result was traced to the **test classpath**: the Spock 4 global
AST transform is discovered by Groovy 5/6 and fails the compilation before the extension runs.
Using the main runtime classpath (without Spock/JUnit/test classes), a single fixture works under
both 5.0.2 and 6.0.0 and reports the expected nullness finding with `classes=1`.

A positive-corpus run with compiled support stubs:

| Groovy | Result |
| --- | --- |
| 5.0.2 | 51 findings, `classes=37`, no internal error |
| 6.0.0 | per-fixture positive/negative runs complete without internal errors |

The exact `CorpusGate` was then run on each version with the main classpath, compiled support
stubs and no Spock/JUnit jars:

| Groovy | CorpusGate |
| --- | --- |
| 5.0.2 | 94 results, 0 failures |
| 6.0.0 | 94 results, 0 failures |

The Groovy 5 `UnionType` failure was fixed portably: the two ternary guard branches no longer use
a closure, because Groovy 5 generated an unloadable
`<UnionType:MethodCallExpression+TernaryExpression>` class for that closure. Explicit
`pushGuards`/`popGuards` preserves the same behavior without the generated type.

This is still a **runtime/corpus spike**, not a support claim. Remaining before any release claim:
Gradle plugin functional tests under 5/6, package-info/artifact boundaries, and a documented
version/toolVersion mapping. A single bulk CLI compilation of all positive fixtures previously
hit a Groovy 6 closure interaction; the per-fixture gate is the accepted corpus model.

## Work order

1. **Inventory** (this document): API surface and known differences.
2. **Compile/runtime/corpus spike** (done for 5.0.2/6.0.0: exact CorpusGate 94/0; plugin
   functional and artifact boundaries remain): try the checker against the next candidate major without promising a
   release; record exact failures.
3. **Compatibility layer**: isolate marker/carrier/phase differences; no duplicated visitor
   or contract parser.
4. **Artifact and plugin boundary**: decide classifier/coordinate and `toolVersion` mapping.
5. **CI matrix** and release documentation.

Steps 2-5 are explicitly after the current feature line is stable.
