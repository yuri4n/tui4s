<!-- claude 2026-10-20: created (L5b): the AOT mode of the tui4s stack -->
# tui4s (AOT cache)

The program of `bench/impl/tui4s` (the same sources and the same jars),
started with a JDK 25 AOT cache and the start flags of the tui4s examples
(ADR 0015): `-XX:TieredStopAtLevel=1`, `-XX:+UseSerialGC`,
`-XX:AOTCache=<task>.aot` and `-Xlog:aot=off`. Only the perf rows measure this
mode: its code is the code of the tui4s stack.

| Item | Version |
| ---- | ------- |
| tui4s, Kyo, kyo-ui, jatatui, Scala | as in `bench/impl/tui4s` |
| JDK | 25 (the cache is valid only for the JDK that made it) |
| Cache | one per task, trained by `build.sh` in a pseudo-terminal (the keys of the task, then `q`) |

```
bench/impl/tui4s/build.sh             # the jars (or bench/run.sh build)
bench/impl/tui4s-aot/build.sh         # trains counter.aot and dashboard.aot
bench/impl/tui4s-aot/run.sh counter   # in a terminal
python3 bench/harness/bench.py perf --stacks tui4s-aot --runs 3
```

The caches are not in git (about 40 MB each). Train them again after a change
of the JDK or of the jars: the JVM rejects a cache whose jars changed, and
`-Xlog:aot=off` then starts the program without it, with no message.
