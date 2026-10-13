# tui4s

A terminal runner for [kyo-ui](https://github.com/getkyo/kyo), in Scala 3.

<!-- claude 2026-10-12: changed these lines: tui4s runs kyo-ui values (C1) -->
A kyo-ui value describes the screen; tui4s lays it out in terminal cells and
paints it with [jatatui](https://github.com/oyvindberg/jatatui), a Java port of
[ratatui](https://ratatui.rs). The core runs on [Kyo](https://getkyo.io).
Integrations for [ZIO](https://zio.dev) and
[Cats Effect](https://typelevel.org/cats-effect/) are planned.

> **Status:** under construction, first usable version. `Tui.run` runs a
> kyo-ui UI in the real terminal: it renders the value, follows its signals
> and routes keys (focus, handlers, text editing, paste); a headless kit tests
> it without a terminal. Components and hooks are next. Nothing is published
> yet.
<!-- claude 2026-10-12: changed the status line (C2) -->
<!-- claude 2026-10-13: changed the status line (C3): keys -->
<!-- claude 2026-10-13: changed the status line (T1): the real terminal -->

## Quick start

<!-- claude 2026-10-13: created this section (T1): the Counter example -->
The Counter example (`examples/src/main/scala/examples/counter/Counter.scala`)
is plain kyo-ui. `Tui.run` gives the UI a quit value; `+` and `-` change the
count, `q` and Ctrl-C quit:

```scala
import kyo.*
import kyo.UI.*
import tui4s.Tui

object Counter:
    def ui(quit: Unit < Sync): UI < (Async & Scope) =
      Signal.initRef(0).map(count => view(count, quit))

    def view(count: SignalRef[Int], quit: Unit < Sync): UI =
      div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('+'), _, _) => count.updateAndGet(_ + 1)
        case KeyboardEvent(Keyboard.Char('-'), _, _) => count.updateAndGet(_ - 1)
        case KeyboardEvent(Keyboard.Char('q'), _, _) => quit
        case _                                       => ()
      }(
        h1("Counter"),
        span("Count: ", count.map(_.toString)).id("count"),
        // ... two buttons and a help line
      )
end Counter

object CounterKyoApp extends KyoApp:
    run(Tui.run(quit => Counter.ui(quit)))
```

Start it in a terminal. The script uses `$JAVA_HOME/bin/java`, or `java` on
`PATH` when `JAVA_HOME` is not set: it must be JDK 25.
<!-- claude 2026-10-13: changed (T1, review R3-6): JAVA_HOME comes first -->

```sh
sbt examples/launchers
examples/target/bin/counter
```

Do not use `sbt run`: sbt also reads the keyboard. The script starts the app
in its own JVM with the flags that a run needs:
`--enable-native-access=ALL-UNNAMED` (the terminal library is native) and the
tui4s scheduler values (see `examples/README.md`). Test a UI without a
terminal with `tui4s.testkit.Headless` (`examples/src/test/scala/examples/counter/CounterSuite.scala`).

## Modules

Each module is released on its own, with its own version.

| Artifact                         | For                                    |
| -------------------------------- | -------------------------------------- |
| `io.github.yuri4n::tui4s-core`   | Kyo users: run a kyo-ui value in the terminal. The base of the others. |
| `io.github.yuri4n::tui4s-zio`    | ZIO users (planned).                   |
| `io.github.yuri4n::tui4s-cats`   | Cats Effect users (planned).           |

## Requirements

- JDK 25 (Kyo 1.0.0-RC7 needs it).
- Scala 3.9.0 or later.

## Contributing

Read [AGENTS.md](AGENTS.md). It has the commands, the code rules, and the
release process for humans and for AI agents.

## License

[Apache-2.0](LICENSE)
