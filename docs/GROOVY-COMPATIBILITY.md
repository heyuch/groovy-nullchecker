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
| 5.0.2 | **compile spike only**: main checker compiles; no corpus/runtime/plugin support |
| 6.0.0 | **compile spike only**: main checker compiles; no corpus/runtime/plugin support |

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

This is a **compile spike only**. A CLI runtime spike against Groovy 5.0.2 and 6.0.0 produced
`coverage classes=0` and a resolution finding for the same source; the extension was not
registered/visited. Therefore runtime/corpus behavior is **not** supported yet.

The next gate is diagnosing the customizer/extension lifecycle under each major, then rerunning
the corpus. Do not turn the compile spike into a release claim.

## Work order

1. **Inventory** (this document): API surface and known differences.
2. **Compile spike** (done for 5.0.2/6.0.0 main sources; runtime CLI currently reports no
   visited classes, so registration/lifecycle diagnosis is next): try the checker against the next candidate major without promising a
   release; record exact failures.
3. **Compatibility layer**: isolate marker/carrier/phase differences; no duplicated visitor
   or contract parser.
4. **Artifact and plugin boundary**: decide classifier/coordinate and `toolVersion` mapping.
5. **CI matrix** and release documentation.

Steps 2-5 are explicitly after the current feature line is stable.
