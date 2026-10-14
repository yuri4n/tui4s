package examples.counter

import kyo.*
import kyo.UI.*
import tui4s.Tui

/** The Counter in plain kyo-ui: only kyo-ui's API, a `SignalRef` for the count
  * and key handlers. `+` and `-` change the count (a key handler on the root),
  * Tab moves between the two buttons, Enter and Space press them, `q` quits
  * (the quit value that `Tui.run` gives the UI), and so does Ctrl-C
  * (`TuiOptions.ctrlCQuits`).
  */
// claude 2026-10-13: created (T1, RC-33 part): the prototype's
// apps/Counter.scala (1d100ed) as an example with the quit value
object Counter:

    /** The Counter UI; `quit` ends the run. Its state is allocated when the UI
      * is built.
      */
    def ui(quit: Unit < Sync): UI < (Async & Scope) =
      Signal.initRef(0).map(count => view(count, quit))

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

/** Runs the Counter in the real terminal: a plain `KyoApp` whose `main` calls
  * `Tui.run` (an app with its own `main`).
  *
  * Start it in a terminal with the start script, not with `sbt run` (sbt also
  * reads the keyboard, and gives the app no terminal):
  *
  * {{{
  * sbt examples/launchers
  * examples/target/bin/counter
  * }}}
  *
  * The script gives the JVM the flags that a run needs:
  * `--enable-native-access=ALL-UNNAMED` (crossterm is a native library),
  * `--sun-misc-unsafe-memory-access=allow`, `-Xss16m` and the tui4s scheduler
  * values (see `examples/README.md`).
  */
// claude 2026-10-13: created (T1, RC-33 part; the lesson of the review thread
// CounterApp.scala:8 of PR 5: the Scaladoc names the launchers, not sbt run)
object CounterKyoApp extends KyoApp:
    run(Tui.run(quit => Counter.ui(quit)))
