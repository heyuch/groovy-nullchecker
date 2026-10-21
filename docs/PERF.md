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
