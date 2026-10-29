<!-- claude 2026-10-20: created: the tui4s stack of the bench (L5) -->
# tui4s

The two tasks in tui4s: kyo-ui values on the tui4s runner, a `TuiApp` each.
The dashboard uses the widgets of tui4s-widgets (`ListView.virtual`, `Gauge`,
`Sparkline`, `StatusBar`, `Theme`) and the composables of tui4s-use
(`useSelection`, `useNow`).

| Item | Version |
| ---- | ------- |
| tui4s-core (runner, TuiApp, use, widgets) | the local snapshot of this repository (`bench/tui4s.version`, written by `bench/run.sh`) |
<!-- cursor 2026-10-29: changed: the version row is tui4s-core (ADR 0024) -->
| Kyo, kyo-ui | 1.0.0-RC7 |
| jatatui | 0.31.0 |
| Scala | 3.9.0 |
| JDK | 25 |
| Formatter | scalafmt 3.11.5 (defaults for Scala 3, `bench/.scalafmt.conf`) |
| Lock file | none (sbt has no standard lock file; the versions are exact in `bench/build.sbt`) |

```
bench/impl/tui4s/build.sh            # sbt stage of both tasks (the bench build)
bench/impl/tui4s/run.sh counter      # in a terminal
bench/impl/tui4s/run.sh dashboard
```

## Notes

- The forced failure (Ctrl-X) is contained: the runner logs the exception of
  the key handler, and the run goes on. The log is printed after the restore.
- SIGTERM and SIGINT: the shutdown hook of the run restores the terminal; the
  exit code is 143 or 130.
- `ListView.virtual` takes a fixed height. The dashboard mounts a new list for
  each terminal height (`component(key)` with the height as the key); the
  selection stays, because it is outside the list. The page of the selection
  (`useSelection(page = ...)`) is the height at the start: after a resize,
  PageDown moves by the old page. Both are limits of the widget API of
  tui4s-widgets, not of the runner.
