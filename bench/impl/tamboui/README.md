<!-- claude 2026-10-20: created: the TamboUI stack of the bench (L5) -->
# TamboUI

The two tasks in Java 25 with TamboUI, a Java library for terminal UIs in the
style of ratatui (`TuiRunner`, widgets, layouts).

The bench program listed TamboUI as a .NET library. TamboUI is a Java library
on Maven Central (`dev.tamboui`); NuGet has no TamboUI package. The bench
thus runs it on the JVM, as a second JVM stack next to jatatui-plain. No .NET
SDK is needed.

| Item | Version |
| ---- | ------- |
| tamboui-tui, tamboui-widgets, tamboui-panama-backend | 0.6.0 |
| JDK | 25 |
| Formatter | google-java-format 1.37.0 (`sbt javafmt` in `bench/`) |
| Lock file | none (sbt has no standard lock file; the versions are exact in `bench/build.sbt`) |

```
bench/impl/tamboui/build.sh
bench/impl/tamboui/run.sh counter
bench/impl/tamboui/run.sh dashboard
```

## Notes

- `TuiRunner` owns raw mode, the alternate screen, the event loop, a tick
  event (100 ms in the dashboard) and a shutdown hook that restores the
  terminal on SIGTERM and SIGINT.
- The forced failure (Ctrl-X): the runner catches the exception of the
  handler and shows an error screen; the keys then go to that screen, and `q`
  ends the program with code 0. The harness records this as "error-screen".
