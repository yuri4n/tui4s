# tui4s

A terminal runner for [kyo-ui](https://github.com/getkyo/kyo), in Scala 3.

<!-- claude 2026-10-12: changed these lines: tui4s runs kyo-ui values (C1) -->
A kyo-ui value describes the screen; tui4s lays it out in terminal cells and
paints it with [jatatui](https://github.com/oyvindberg/jatatui), a Java port of
[ratatui](https://ratatui.rs). The core runs on [Kyo](https://getkyo.io).
Integrations for [ZIO](https://zio.dev) and
[Cats Effect](https://typelevel.org/cats-effect/) are planned.

> **Status:** under construction, first usable version. A tui4s app is a
> kyo-ui UI and a `TuiApp` main class: `Tui.run` runs the UI in the real
> terminal, renders the value, follows its signals and routes keys (focus,
> handlers, text editing, paste), and it mounts components in two styles
> (hooks and a `Component` class); a headless kit tests it without a terminal.
> Nothing is published yet.
<!-- claude 2026-10-12: changed the status line (C2) -->
<!-- claude 2026-10-13: changed the status line (C3): keys -->
<!-- claude 2026-10-13: changed the status line (T1): the real terminal -->
<!-- claude 2026-10-13: changed the status line (C4): components and hooks -->
<!-- claude 2026-10-13: changed the status line (T2): TuiApp -->

Components come in two styles. Hooks: a function, for example
`for count <- useSignal(0) yield div(...)`, and `component(key)(body)` for a
node that the runner mounts. A class: `final class Counter extends Component`
with `type State = Int`, `initial`, `view(state)` and `onMount(state)`. See
the Scaladoc of `tui4s.Hooks` and `tui4s.Component`.
<!-- claude 2026-10-13: created these lines (C4): the two component styles -->

## Quick start

<!-- claude 2026-10-13: created this section (T1): the Counter example -->
<!-- claude 2026-10-13: changed this section (T2, OS-48 part): the hooks Counter with its TuiApp main, and its test; the two code blocks are the text of the example files (ReadmeSuite checks them) -->
The smallest app: the Counter example
(`examples/src/main/scala/examples/counter/Counter.scala`). The UI is plain
kyo-ui with one hook (`useSignal`); `object CounterApp extends TuiApp` is its
main class. `+` and `-` change the count, `q` and Ctrl-C quit:

<!-- from: examples/src/main/scala/examples/counter/Counter.scala#quickstart -->
```scala
import kyo.*
import kyo.UI.*
import tui4s.Hooks.useSignal
import tui4s.Tui
import tui4s.TuiApp

/** The Counter: a kyo-ui UI with one piece of state (`useSignal`), key handlers
  * and two buttons. `+` and `-` change the count, Tab moves between the
  * buttons, Enter and Space press them, `q` quits (and so does Ctrl-C).
  */
object Counter:

    /** The Counter UI; `quit` ends the run. */
    def ui(quit: Unit < Sync): UI < Sync =
      useSignal(0).map(count => view(count, quit))

    /** The Counter view over a count that the caller owns. */
    def view(count: SignalRef[Int], quit: Unit < Sync): UI =
      div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('+'), _, _) =>
          count.updateAndGet(_ + 1)
        case KeyboardEvent(Keyboard.Char('-'), _, _) =>
          count.updateAndGet(_ - 1)
        case KeyboardEvent(Keyboard.Char('q'), _, _) => quit
        case _                                       => ()
      }(
        h1("Counter"),
        span("Count: ", count.map(_.toString)).id("count"),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
        p("+ / - change it, Tab moves, Enter presses, q quits").style(
          _.color(Style.Color.gray),
        ),
      )
end Counter

/** Runs the Counter in the real terminal. Start it with its start script, not
  * with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/counter
  * }}}
  */
object CounterApp extends TuiApp:
    def ui: UI < (Async & Scope) = Counter.ui(Tui.quit)
```

Start it in a terminal. The script uses `$JAVA_HOME/bin/java`, or `java` on
`PATH` when `JAVA_HOME` is not set: it must be JDK 25.
<!-- claude 2026-10-13: changed (T1, review R3-6): JAVA_HOME comes first -->

```sh
sbt examples/launchers
examples/target/bin/counter
```

Do not use `sbt run`: sbt also reads the keyboard. The script starts the app
in its own JVM with the flag that a run needs,
`--enable-native-access=ALL-UNNAMED` (the terminal library is native).
`TuiApp` sets the tui4s scheduler values in code before the app starts (fewer
split frames under load, less CPU when idle), and it installs the capture of
`System.out`, `System.err` and `println` while the UI is up. The exit code is
0 after a quit (also Ctrl-C), 1 after a failure (the error is printed after
the terminal is restored), and 143 or 130 for `kill -TERM` or `kill -INT`
(the terminal is restored first). An app with a main of its own (for example
a `KyoApp` that calls `Tui.run`) gives the scheduler values as JVM flags: see
`examples/README.md`, which also lists the other examples (two counters, both
component styles, Todo, Dashboard).

A test runs the UI on `tui4s.testkit.Headless`, with no terminal
(`examples/src/test/scala/examples/counter/CounterSuite.scala`):

<!-- from: examples/src/test/scala/examples/counter/CounterSuite.scala#quickstart-test -->
```scala
import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless

/** The Counter on the headless test kit: no terminal. */
class CounterSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    test("+ and - change the count") {
      val s = run {
        for
            h <- Headless.start(50, 8)(Counter.ui)
            _ <- h.char('+')
            _ <- h.char('+')
            _ <- h.char('-')
            s <- h.screen
        yield s
      }
      assert(s.contains("Count: 1"), s.text)
    }
```

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
