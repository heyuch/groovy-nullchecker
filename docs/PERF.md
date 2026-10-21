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

