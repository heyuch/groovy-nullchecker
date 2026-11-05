# Autofix provider SPI: design proposal

- Status: minimal hook, built-in migration, CLI load and plugin `autofixProviders`/`nullcheckAutofixProviders` classpath implemented; public API freeze and conflict/idempotency policy pending.
- Related: [ROADMAP.md](ROADMAP.md) N3 / H3-5, [DATAFLOW-COMPARISON.md](DATAFLOW-COMPARISON.md).
- Current implementation: report-driven autofix in `NullableAnnotator.runFromFindings` plus
  the AST-only `nullcheckAddNullable` migration.

## Goal

Let built-in and user-defined fixes share one safe pipeline:

```text
checker findings
    -> FixProvider.propose(FixContext)
    -> FixProposal (edits)
    -> dry-run / diff
    -> apply
    -> re-run check
```

The provider only **proposes** edits. It does not write files, run compilation, or suppress
findings.

## Non-goals

- arbitrary scripts that bypass review and mutate source during `check`;
- a public plugin API before the proposal/apply model is proven;
- modeling every nullness violation;
- Java source edits.

## Proposed model

### FixContext

A read-only view for one finding:

- source file and line;
- category/message;
- parsed `CompilationUnit` / `ClassNode` / method/parameter context when available;
- the surrounding statement and assignment target;
- optional source line text.

The context must be immutable for the duration of a proposal.

### FixProposal

A deterministic list of source edits:

- file path;
- offset + replacement text, or a structured action such as
  `insertAfter(statement, text)`;
- description;
- confidence (`high` only at first);
- idempotency key.

Proposals are deduplicated by idempotency key before apply. They must not overlap; overlapping
proposals are rejected or require an explicit conflict policy.

### FixProvider

```groovy
interface FixProvider {
    List<FixProposal> propose(FixContext context)
}
```

Built-in providers:

- `NullableReturnProvider` (method returns nullable);
- `NullableParameterProvider` (only when the target body checks null);
- existing AST-only `@Nullable` migration provider.

User providers should be loaded by class name from a separate provider jar or a configured
classpath, not by a Gradle configuration-time closure. A provider is arbitrary code, so:

- loading is opt-in;
- the task remains a separate `nullcheckAutofix` task;
- dry-run is the default for user providers until explicitly applied.

## Safety rules

- Only findings whose category/message is recognized by a provider are candidates.
- A provider may skip; skip counts must be explicit.
- No source edit if the target cannot be located unambiguously.
- Re-run the checker after apply; a fix that creates a new finding is reported, not hidden.
- Apply order is deterministic by file/offset; overlapping edits are rejected.
- Every built-in proposal has a fixture; user providers are their owner's responsibility.

## Staged plan

1. Extract a non-public `FixContext` / `FixProposal` model and move the current return/parameter
   rules into built-in providers behind the existing `nullcheckAutofix` task.
2. Add dry-run diff, idempotency keys and proposal conflict detection.
3. Add a provider classpath option and test a minimal external provider.
4. Decide whether a Groovy-script DSL is worth the safety cost; do not add it before the
   compiled-provider path is stable.

## Open questions

- exact provider classpath/classloader boundary;
- whether providers receive a parsed unit or only source text;
- how to represent structured insertions without leaking a formatter API;
- whether user providers default to dry-run or require an explicit `--apply` flag;
- how to version the provider API before a stable release.
