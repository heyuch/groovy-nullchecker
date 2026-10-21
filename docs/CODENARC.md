# CodeNarc policy

## Trial

CodeNarc 3.5.0, every rule from the bundled rulesets: **405 rules**, **724 violations** across
**45 rules**.

The trial ruleset is `config/codenarc/all.xml`; run it with:

```bash
./gradlew :nullchecker:codenarcMain -PcodenarcAll=true
```

The committed `config/codenarc/codenarc.xml` enables **323 rules** and reports **0 violations**.
It is generated from the same rule set minus the excluded categories and the disabled rules
below; this file is the record of the triage.

## Excluded categories

| Category | Why |
| --- | --- |
| `groovyism` | The project intentionally writes Groovy in a Java-like style. All rules in this category are off: explicit collection instantiation, getter/setter style, GString/toString style, collect-call style, last-element style, and the rest. |
| `grails` | This is not a Grails project. |

## Disabled rules

### Conflicts with the coding baseline or the project layout

| Rule | Why |
| --- | --- |
| `UnnecessaryReturnKeyword` | Conflicts with the explicit-return rule |
| `Instanceof` | The checker is AST code; `instanceof` is the dispatch mechanism |
| `PublicMethodsBeforeNonPublicMethods` | Fights the grouped-by-responsibility layout |
| `StaticMethodsBeforeInstanceMethods` | same |
| `StaticFieldsBeforeInstanceFields` | same |
| `IfStatementCouldBeTernary` | Explicit `if` is preferred |
| `CouldBeSwitchStatement` | The visitor dispatches on AST node types; a value switch would be artificial |
| `SpaceAroundMapEntryColon` | Formatting style not part of the baseline |
| `MisorderedStaticImports` | Import order not part of the baseline |
| `LineLength` | No line limit in the baseline |
| `UnnecessaryCast` | Groovy static typing often needs the cast |
| `DuplicateStringLiteral` / `DuplicateNumberLiteral` | The repeated literals are messages/keys, not duplicated logic |
| `FactoryMethodName` | `build…` here builds a map, not a factory; false positive |

### Intentionally allowed behaviour

| Rule | Why |
| --- | --- |
| `JavaIoPackageAccess` | The CLI and corpus tooling work with files directly; the EJB rule does not apply |
| `SystemExit` | The CLI's exit codes are its contract |
| `Println` / `SystemErrPrint` | The CLI writes a report to stdout/stderr |
| `CatchThrowable` | Best-effort classpath/AST probing deliberately catches `Throwable` |
| `FileCreateTempFile` | The canary needs a temporary directory |
| `AssignmentToStaticFieldFromInstanceMethod` | `lastCoverage` is published for the harness |
| `SynchronizedOnThis` | The sink is package-scoped and the lock is private to it |
| `ReturnsNullInsteadOfEmptyCollection` | `parseFailOn`/`parseChecks` use null as "invalid input"; `lookupPackageAnnotations` uses null as "not found" |
| `BooleanMethodReturnsNull` | `booleanMember` is a tri-state result: true/false/absent |

### Size and complexity (pending a separate threshold decision)

`ClassSize`, `MethodCount`, `ParameterCount`, `MethodSize`, `NestedBlockDepth`,
`CyclomaticComplexity`, `AbcMetric`, `NestedForLoop`.

The visitor is deliberately large and the CLI's `run` is long; enabling these without a
baseline/threshold decision would gate on known structure rather than on a regression.

## Kept and fixed

- `ConfusingMethodName`: `NullCheckSinks.current()` renamed to `currentSink()` (the field is
  `CURRENT`).
- `NoWildcardImports`: `ignoreStaticImports = true`; the `AstSupport.*` static imports are
  intentional and explicit imports would add noise.
- `FieldName` / `VariableName`: type suffixes (`List`, `Map`, `Arr`, `Str`, `Count`) are
  forbidden by regex.
- `IllegalRegex`: separator-comment banners are forbidden.
- `ClassStartsWithBlankLine` / `ClassEndsWithBlankLine`: class bodies are padded with one blank
  line after the opening brace and before the closing brace.
- `BracesForForLoop`: the opening brace stays on the `for` line; the multi-line header in
  `ContractRegistry` was rewritten with a named list.
- All other enabled rules are unchanged and currently report zero violations.

## Maintenance

When CodeNarc is upgraded or a rule is reconsidered:

1. regenerate `config/codenarc/all.xml` from the new bundled rulesets;
2. run `./gradlew :nullchecker:codenarcMain -PcodenarcAll=true`;
3. triage new hits into fix / disable with a reason here;
4. regenerate `config/codenarc/codenarc.xml`, keeping the `groovyism` and `grails` categories
   excluded, and keep `codenarcMain` at zero.
