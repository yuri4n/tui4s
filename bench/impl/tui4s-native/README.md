<!-- claude 2026-10-20: created (L5b): the native mode of the tui4s stack; changed: the resilience rows (review fix) -->
# tui4s (native image)

The program of `bench/impl/tui4s` (the same sources and the same jars), as a
GraalVM native image (ADR 0015). The metadata of tui4s-runner
(`META-INF/native-image`) is in its jar; `build.sh` gives native-image no
option of tui4s. The perf, conformance and resilience rows measure this mode
(the restore after a quit, Ctrl-C, SIGTERM, SIGINT and a failure); the change
and mutant rows do not: its code is the code of the tui4s stack.

| Item | Version |
| ---- | ------- |
| tui4s, Kyo, kyo-ui, jatatui, Scala | as in `bench/impl/tui4s` |
| GraalVM | Community Edition for JDK 25 (native-image) |
| Options | `--no-fallback -march=compatibility -Os` |

```
bench/impl/tui4s/build.sh              # the jars (or bench/run.sh build)
bench/impl/tui4s-native/build.sh       # out/counter and out/dashboard
bench/impl/tui4s-native/run.sh counter # in a terminal
python3 bench/harness/bench.py perf --stacks tui4s-native --runs 3
python3 bench/harness/bench.py resilience --stacks tui4s-native
```
