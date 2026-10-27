# Performance baseline

Date: 2026-10-21.

This is a coarse local baseline, not a microbenchmark. Wall time includes Gradle daemon and
configuration overhead; use the same machine, JDK and commands when comparing a change.

Environment:

- openjdk version "17.0.20.1" 2026-08-18
- Gradle 8.14.3
- macOS, JDK 17 toolchain
- checker at the F4 commit (strict CFG worklist enabled)

Commands:

```bash
./gradlew :nullchecker:compileGroovy --rerun-tasks
./gradlew :nullchecker:test --rerun-tasks
```

Observed wall time:

| Command | real | user | sys |
| --- | ---: | ---: | ---: |
| `compileGroovy --rerun-tasks` | 2.41 s | 0.67 s | 0.05 s |
| `test --rerun-tasks` | 4.88 s | 0.78 s | 0.07 s |

The corpus includes the strict flow fixtures and the plugin's checker tests. A future CFG change
must re-run these commands and record the delta rather than claiming "linear" from theory.

## 0.5.0 vs 0.6.0 on an external service

A six-module external Groovy service (251 files, 397 classes; anonymized) was checked with the
same strict configuration, loading the published `0.5.0` and `0.6.0` plugin/checker pairs. No
project source was changed.

Method:

- six output modules with sources, one `nullcheck` task per module;
- same init-script guard/never-returns configuration for both versions;
- `--no-build-cache --no-configuration-cache`, alternating versions;
- one cold run per version discarded (the first `0.5.0` run included dependency resolution and
  first compilation and was not comparable), then three warm runs each;
- two series: with a warm Gradle daemon and with `--no-daemon` (JVM startup included).

Warm results (wall time, real seconds):

| Series | 0.5.0 median | 0.6.0 median | Delta |
| --- | ---: | ---: | ---: |
| warm daemon | 8.92 s | 9.04 s | +0.12 s (~1.3%) |
| no daemon | 15.02 s | 15.08 s | +0.06 s (~0.4%) |

Both versions reported the same 12 findings (10 null, 2 resolution). The deltas are within
run-to-run variance and include Gradle configuration/startup; no isolated checker timer was
collected. The conclusion is narrow: **on this workload the CFG work shows no measurable
regression**. A loop-heavy or much larger codebase should re-run this comparison rather than
generalizing from these numbers.

## Expanded validation: nine modules

The same external service was then checked across nine modules of different sizes and layers
(domain, application, components, output): 4,372 Groovy files and 4,675 classes. No source was
changed.

Findings under the same configuration:

| Mode | Findings | null | initialization | resolution |
| --- | ---: | ---: | ---: | ---: |
| annotation (strict off) | 400 | 86 | 0 | 314 |
| strict | 1,201 | 886 | 1 | 314 |

The resolution count is identical in both modes; strict adds 800 nullness findings. Message
shapes in strict mode were dominated by `Potential null dereference: 'X' may be null` (805) and
`... may return null` (81); 314 were resolution findings that are not nullness claims.

0.5.0 and 0.6.0 reported the **same total on every module** (1,201 combined). The CFG work
therefore did not add reports on this sample; it also did not remove the strict-mode findings
that the older flow analysis already produced.

Three alternating timing runs (warm daemon, no build/configuration cache) gave:

| Version | Runs (s) | Median |
| --- | --- | ---: |
| 0.5.0 | 35.61, 52.07, 44.54 | 44.54 s |
| 0.6.0 | 35.33, 43.96, 36.10 | 36.10 s |

The medians favor 0.6.0 by about 8 s, but the range is wide and the series is short; this is not a
claim that CFG made analysis faster. It is evidence that the expanded run did not show a
regression.

Practical reading: strict mode on a legacy, unannotated codebase is noisy (1,201 raw findings
across nine modules), so adoption needs triage and contracts rather than treating the raw count
as a defect count. Annotation mode reports 400, most of which are resolution rather than
nullness. The CFG itself is not the source of the extra reports on this code.

## Closure return tracking

Working-tree closure return tracking was compared with published `0.7.3` on the same nine
external modules (4,372 Groovy files, 4,675 classes), strict mode, warm daemon,
`--no-build-cache --no-configuration-cache`.

The first `0.7.3` run was cold (172.47 s, dependency/cache work) and is excluded. Three warm
runs after it:

| Version | Warm runs (s) | Median | Findings |
| --- | --- | ---: | ---: |
| 0.7.3 (Central) | 37.33, 37.94, 41.78 | 37.94 s | 1,620 |
| working tree + N1 | 37.84, 36.86, 42.04 | 37.84 s | 1,620 |

The medians are effectively identical and the ranges overlap. N1 only scans closure literals
that are assigned to direct local variables (plus direct literal invocation); it does not add a
second full AST pass. The sample contains no new closure-return finding, so this measures the
added summary/scoping work, not a changed finding volume.

