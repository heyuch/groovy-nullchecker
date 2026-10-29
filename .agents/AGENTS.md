# nullchecker Project Agent Development Rules

- Scope: all code, tests, corpus, documentation, and release operations in this repository.
- Priority: direct user instructions > this file > other repository documentation. If they conflict, explain the reason instead of choosing silently.
- Goal: preserve nullchecker's core promise: **trustworthy results, reproducible behavior, and one owner per concept.**

## 1. Before You Start

Read the following as needed:

- `README.md` / `README.zh-CN.md`: external behavior and build entry points.

Reproduce the problem before changing code. Do not treat historical plans, TODOs, or comments as current facts.

## 2. General Coding Standards

### 2.1 Readability First

- One statement per line; do not pack multiple statements onto one line with semicolons.
- Allow at most one method call on a receiver per line (`order.getItems()` is fine). When a second call is needed, assign the intermediate value to a named local variable first.
- Use only basic constructs such as `if / else if / else / for / foreach / while / switch / try-catch`.
- Always use braces for `if`, `for`, and `while`, even for a single statement.
- Do not use nested ternaries; a ternary is allowed only as a single level with simple values in both branches.
- Do not hide business decisions inside predicates, closures, lambdas, or annotation expressions.
- Comments should explain only the "why" that the code cannot express. Do not use comments to explain "what this block does"; extract a method with a clear intent instead.
- Do not use separator-comment banners; blank lines are the separation.

### 2.2 Naming

Name length follows scope:

| Scope | Requirement |
| --- | --- |
| Class attributes, globals, cross-module contracts | Precise and unambiguous; qualify when necessary |
| Method-local variable | One word; more than three words needs a reason |
| Loop index, scope of five lines or fewer | A single character, such as `i` or `n` |

- Use plural names for collections and singular names for single values: `orders` / `order`.
- Names must not carry type information: avoid `orderList`, `orderMap`, `orderStr`, `iCount`.
- Do not use layer suffixes: `orderDO`, `userPO`, and `paymentDTO` are forbidden; the declaration and package express the type.
- Do not invent abbreviations; use only domain-standard ones such as `id`, `url`, and `qty`.
- Prefix booleans with `is / has / can / should`.
- Use the same word for the same concept across the project; do not drift between `user / customer / member`.

### 2.3 Code Blocks and Functions

#### Blocks and Whitespace

- Divide a method body into "do one thing" blocks: validate -> load -> calculate -> write -> assemble response. Separate blocks with a blank line; do not insert blank lines inside a block.
- Declare variables close to their use; do not stack them at the beginning of the method.
- Collapse consecutive blank lines into one; do not leave blank lines at the start or end of a method body.
- The order of declarations, calls, and return values should read top to bottom; do not rely on comments to explain execution order.

#### Function Design: Small, Single Level of Abstraction, Self-Documenting

- **A method does one thing**: its name and return value should be describable in one sentence without "and / then / also"; if not, split it. The criterion is a single responsibility, not line count.
- **Method names are verb phrases that express intent**: use `calculateOverdueDays`, not `process`, `handle`, `doWork`, or `util`. A name containing `And` / `Or` usually means the method does two things.
- **One level of abstraction**: a high-level method should only orchestrate named steps such as `validate`, `load`, `calculate`, and `report`. Push index iteration, string concatenation, boundary checks, and type conversion into private methods. Do not mix "what to do" and "how to do it" in one method.
- **Extraction beats comments**: when a block needs a comment to explain "what it is doing" before it can be understood, extract it into a method named for its intent first. Comments should keep only the "why".
- **Use 0-3 parameters**. When there are more, collapse them into a parameter object. Do not use output parameters and do not mutate input parameters; return a new value when a new value is needed.
- **Use at most one boolean switch parameter**, with an `is / has` prefix. Use it only when both branches are short and share the same main flow. If one branch covers most of the method, split the method. Put safety, permission, and deletion semantics in the method name instead of a bare boolean.
- **Separate queries from commands (CQS)**: query methods such as `find`, `count`, and `exists` must not change state; commands should use verbs such as `save`, `cancel`, and `apply`.
- **Side effects must match the name**: a method called `validate` must not also write to a database; a method called `load` must not secretly modify global state.
- **Method order**: put the main flow first and the private steps it calls immediately after it, so the file reads top to bottom. Do not make the reader jump around the file.

#### When to Split

Consider extraction when any of the following is true:

- The method body exceeds roughly 20-30 lines.
- There are three or more business steps that can each be named in one sentence.
- `if` / `for` / `while` nesting exceeds two levels.
- A named intermediate result is needed to connect one step to the next.
- The same logic appears a second time.
- A block cannot be understood without a comment explaining "what it is doing".

After splitting, the main flow should still be understandable within three levels of calls.

#### Avoid Over-Extraction

- Do not extract just to reduce line count. It is fine if the total number of lines grows, as long as each level has a clearer intent and the main flow is shorter.
- Do not extract a one-line method that is called only once and whose name conveys no more information than the expression.
- Do not turn "read a field plus one simple operation" into a chain of getter methods.
- If the extraction only moves code to another method without shortening the reading path or isolating change, return to the original block structure.

### 2.4 Allowed Project Patterns

- Groovy closures are part of the compiler/AST API and test fixtures. Short, in-memory `collect` / `findAll` transformations with no business branching are allowed. Use explicit loops for complex flows, IO, branching, and state mutation.
- AST/compiler dispatch requires `instanceof`; keep it in one place and do not spread it into long type-checking chains.
- Spock tests may use `def`, dynamic fixtures, and framework-idiomatic return values; production source must still use explicit types.
- Existing code ported from upstream may retain a few patterns that do not match this document. Do not perform unrelated refactors for formatting or chained calls; new code follows this document.
- `System.exit`, `println`, and direct file access in the CLI are part of its interface and are not business-layer violations.

### 2.5 Static Types and Data Modeling

- Production code must declare types explicitly; do not use `def` / `var` for business object types.
- Containers must carry element types: `List<Order>`, not a raw `List`; do not use `Object` as an escape hatch.
- Use data classes (Groovy class / record / DTO) for business data with fixed fields; do not carry it in a `Map`, array, or tuple.
- Use `Map` only for true key-value dictionaries, raw external message payloads, and unordered key sets at API boundaries. Convert to a data class before entering business logic.
- Do not use dynamic properties, `set/get` magic, or string keys to bypass modeling.
- Production Groovy source defaults to `@CompileStatic`; new dynamic code needs an explicit reason.

### 2.6 Class Design: SOLID + GRASP

Check each class before modifying it:

- **SRP**: Is there more than one reason to change? If so, split it.
- **OCP**: Does adding a business scenario require editing an existing `if/else` or `switch`? Find an extension point first.
- **LSP**: A subtype must not strengthen preconditions, weaken postconditions, throw exceptions not declared by the interface, or hide missing behavior behind an empty implementation.
- **ISP**: Split interfaces by caller needs; do not force implementors to implement methods they do not use.
- **DIP**: Business classes depend on abstractions, not concrete infrastructure or implementations they instantiate directly.

Assign responsibilities in GRASP order:

1. **Information Expert**: whoever holds the data provides the operation.
2. **Creator**: whoever has the context needed to create an object creates it.
3. **Low Coupling / High Cohesion**: minimize the dependency surface and keep responsibilities cohesive.
4. **Controller**: an entry point receives parameters, orchestrates, and assembles a response; it does not contain domain rules.
5. **Polymorphism**: use polymorphism for type-based branching, not `instanceof` or long type-enum chains.
6. **Pure Fabrication**: introduce a service or factory only when no domain object owns the responsibility.
7. **Indirection**: before adding an intermediary, explain which two things it decouples.
8. **Protected Variations**: put volatile protocols, rules, sorting, and presentation choices behind stable interfaces.

### 2.7 Design Pattern Trade-offs

Before introducing a pattern, abstraction layer, or Service/Factory/Strategy/Manager/Helper, answer five questions:

1. Can this be done without a pattern? "No pattern" is also a valid conclusion.
2. Which pattern?
3. What does it simplify?
4. What does it complicate (indirection, boilerplate, debugging path)?
5. Is it worth it?

An interface with one implementation, a factory called from one place, or a strategy with one branch is usually not worth it.

## 3. Project-Specific Constraints

### 3.1 Trustworthiness First

- **Zero findings must be trustworthy.** When adding a rule, widening analysis, or skipping a path, first prove that it cannot cause a silent miss.
- Fail closed: unknown configuration, unparseable package defaults, exhausted analysis budgets, and internal errors must be visible; they must not silently become "clean".
- Do not hide a false positive with suppression, message matching, or disabling checks. Fix the fact, contract, or flow first.
- Conservative false positives are acceptable; unproven soundness claims are not.

### 3.2 Behavior Changes Require Evidence

- New rules/guards: add both positive and negative corpus fixtures.
- Strict-only changes: add a strict fixture and verify annotation-mode behavior.
- Bug fixes: write a minimal reproduction or a failing test before changing the implementation.
- Message changes: update `NullCheckCategories`, `CorpusRules`, and the classification tests together.
- Contract changes: cover configuration > annotations > built-ins precedence, plus malformed input.

### 3.3 Java/Groovy Boundary

- nullchecker checks Groovy source only; ordinary `.java` files are not checked.
- `package-info.java` is read to provide package-level defaults; Java API `@Nullable` / `@NonNull` / `@Contract` annotations can serve as contracts for Groovy callers.
- Do not claim that Java method bodies are checked by this tool; use a separate Java null checker when Java null checking is needed.
- Autofix processes Groovy source files only.

## 4. Architecture Ownership

Before modifying code, ask: "Who already answers this question?"

| Question | Owner |
| --- | --- |
| What does a call promise? | `Contract` / `ContractRegistry` |
| Can this value be null here? | `FlowState` / `Nullness` / `FlowAnalysis` |
| How are annotations and AST details read? | `AstSupport` |
| Does this package/class default to non-null? | `PackageScopes` |
| Which rule families run, and what are the strict settings? | `NullCheckConfig` |
| How is a finding created, classified, and written? | `NullCheckReporter` / `NullCheckSinks` / `NullCheckReportWriter` |
| How are rule coverage and corpus gates enforced? | `CorpusRules` / `CorpusGate` |
| How are high-confidence source changes made? | `NullableAnnotator` / `NullCheckAutofixCli` |
| How are plugin options and tasks wired? | `NullCheckExtension` / `NullCheckPlugin` |

Rules:

- Before adding a second or third implementation of the same semantics, use `rg` to enumerate the existing locations; prefer extending an existing table, model, or dispatch point.
- **A third implementation that answers the same semantic question is a design defect**, not a new feature. Merge only when it also shares the same invariants, lifecycle, priority, and failure semantics.
- Do not abstract just because the shapes look similar; follow the rule of three and prove behavioral equivalence first.
- New concepts and owners must update this table or the corresponding documentation, not exist only in a commit message.
- Do not use message strings as the source of a finding's type; new findings should be classified structurally, and the string mapping must not grow before P0-4 is complete.

## 5. Static Checks and Gates

### 5.1 Rules Already Enforced Mechanically

The following rules are enforced by Gradle/CodeNarc/Spotless/SpotBugs/the corpus gate, not by Agent memory:

| Rule | Enforcement |
| --- | --- |
| Production code is `@CompileStatic`; `def` is forbidden | CodeNarc `CompileStaticRule`, `NoDefRule` |
| Variables/fields/parameters/return values declare explicit types | CodeNarc `VariableTypeRequiredRule`, `FieldTypeRequiredRule`, `MethodParameterTypeRequired`, `MethodReturnTypeRequired` |
| Braces for `if/for/while/else` | CodeNarc `BracesFor*`, `IfStatementBraces` |
| Formatting, indentation, blank lines, trailing whitespace | Spotless + CodeNarc formatting rules |
| Naming, imports, unused symbols, obvious bug patterns | CodeNarc + SpotBugs |
| Rule/guard corpus coverage | `CorpusGate`, run by `check` |
| Contract parsing has exactly one owner | `ArchitectureSpec` |
| Production code in the checker module does not use Java streams | `ArchitectureSpec` |
| nullchecker itself has zero findings | `:nullchecker:nullcheck` (dogfood) |

`./gradlew check` and the plugin functional tests are the gates. CodeNarc/Spotless currently run at the **check/lint stage**, not as compiler warnings. For immediate feedback, use an IDE plugin or run the relevant task locally.

### 5.2 Do Not Duplicate Mechanically Checkable Rules as Prose

If a rule can be checked mechanically:

1. Add it to CodeNarc/Spotless/ArchitectureSpec first.
2. Update `docs/CODENARC.md` or the corresponding test.
3. Do not describe it only in this document.

After adding a CodeNarc rule, evaluate all hits with `-PcodenarcAll=true` and record them in `docs/CODENARC.md`; known violations must not silently become the baseline.

### 5.3 Rules That Require Semantic Judgment

The following are intentionally not forced into static checks. Plans, reviews, and this document govern them:

- SOLID/GRASP responsibility assignment and whether an abstraction is worthwhile;
- the trade-off between false positives and soundness;
- data-modeling choices, API compatibility, and whether a name expresses domain meaning;
- whether the corpus truly covers the semantics (`CorpusGate` proves registration and firing only);
- whether CHANGELOG/README are accurate;
- whether two implementations really represent the same concept;
- recognizing duplicate concepts during code review.

Record these in plans, commit messages, and review comments instead of disguising them as lint rules.

## 6. Change and Test Workflow

### 6.1 Before Starting

1. Reproduce the problem and record the minimal input and actual output.
2. Use `rg` to find every location that answers the same question.
3. Decide whether this is a bug, a behavior extension, or a pure refactor.
4. List every owner, corpus fixture, and compatibility path that must change.
5. Break large changes into steps; do not mix a feature and a refactor in one commit.

### 6.2 Implementation

- Establish a behavioral baseline before moving code.
- Change one semantic layer at a time; differences between the strict and annotation paths must have tests.
- Do not delete a branch that "looks unreachable" without an assertion, counter, or test proving it.
- New configuration needs a default, behavior for invalid values, and tests.
- New public/API behavior must update README/CHANGELOG.
- Fix false positives by fixing the guard/fact first, not by adding suppression.

### 6.3 Verification Commands

```bash
./gradlew :nullchecker:test
./gradlew :nullchecker:codenarcMain :nullchecker:nullcheck
./gradlew check
./gradlew dualRun
./gradlew :nullchecker-gradle-plugin:functionalTest
```

- `check` is the core gate: every rule needs a fixture that is reported, and every guard needs a fixture that stays silent.
- `dualRun` is an informational comparison, not a release gate; the reference side must produce output.
- Before release, run `./gradlew clean check dualRun dist` and follow `docs/PUBLISHING.md`.
- Do not claim that a command passed unless it was actually run; report the environment and results.

## 7. Agent Collaboration and Concept Consolidation

### 7.1 Inventory Before Writing Code

Before adding a new branch, string table, annotation parser, or finding rule, list:

- Which `file:symbol` locations currently answer this question?
- Do their inputs, lifecycle, priority, and failure strategies agree?
- If they are not merged, why not?

Put the inventory in the plan or delivery notes. Do not skip the inventory because of a "minimal diff", and do not merge immediately just because similar code was found.

### 7.2 Two-Pass Review

After the feature is complete, perform an independent consolidation review:

- Read only the diff and the repository, without being anchored by the implementation rationale.
- Find places where "the same question is answered more than once".
- Check whether the new code bypasses the ownership table.
- Provide a test that preserves behavior.

Prefer separate commits for implementation and consolidation review; do not mix a pure refactor into a feature.

### 7.3 Delivery Notes

State:

- what behavior changed;
- which modes are affected (strict / annotation / compile / CLI);
- which commands were actually run and what the results were;
- which corpus fixtures and documents were added or changed;
- whether a new concept, owner, or compatibility change was introduced;
- if the change intentionally deviates from the baseline, why and what the impact is.

Do not replace concrete commands and results with "done" or "tests pass".

### 7.4 Do Not

- Release, tag, or change the version unless the user asks.
- Disable checks or expand suppressions just to make a gate pass.
- Put real business project names, internal class names, or unredacted code into repository documentation or corpus fixtures.
- Mark open items in `docs/REVIEW-ACTIONS.md` as done without being asked.
- Delete "dead code" without evidence, especially strict flow-analysis paths.

## 8. Definition of Done

- [ ] The reproduction/input is clear, and behavior changes have positive and negative corpus fixtures.
- [ ] No existing owner was bypassed; concept changes updated the ownership table or documentation.
- [ ] Both strict and annotation mode expectations are tested.
- [ ] Finding classification, messages, and tests are updated together.
- [ ] Autofix remains dry-run capable and idempotent.
- [ ] `check` and the plugin functional tests pass (or it is explicitly stated that they were not run).
- [ ] Affected CHANGELOG/README/ROADMAP content is updated.
- [ ] No unexplained deviation from the baseline.
