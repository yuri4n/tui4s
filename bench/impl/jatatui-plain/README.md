<!-- claude 2026-10-20: created: the jatatui-plain stack of the bench (L5) -->
# jatatui-plain

The two tasks in Java 25 on the same jatatui and crossterm jars as tui4s, with
no Kyo and no Scala. This stack is the JVM floor of the bench: the difference
between this row and the tui4s row is the cost of Kyo, kyo-ui and the tui4s
runner, not the cost of the JVM.

Each task is one loop on the main thread, as in a ratatui app: draw, wait for
an event (at most until the next tick), handle it. A shutdown hook stops the
loop on SIGTERM and SIGINT and waits (at most 2 s) until the loop restored the
terminal, so the hook does not race with a draw.

| Item | Version |
| ---- | ------- |
| jatatui-core, jatatui-crossterm, jatatui-widgets, crossterm (JNI) | 0.31.0 |
| JDK | 25 |
| Formatter | google-java-format 1.37.0 (`sbt javafmt` in `bench/`) |
| Lock file | none (sbt has no standard lock file; the versions are exact in `bench/build.sbt`) |

```
bench/impl/jatatui-plain/build.sh
bench/impl/jatatui-plain/run.sh counter
bench/impl/jatatui-plain/run.sh dashboard
```

## Notes

- jatatui-widgets (the list, the gauge, the sparkline) is a jar that tui4s
  does not use; tui4s draws its own widgets into jatatui buffers.
- The forced failure (Ctrl-X) ends the program: the exception leaves the loop,
  `Jatatui.runIo` restores the terminal, and the JVM prints the stack trace
  and exits with 1.
