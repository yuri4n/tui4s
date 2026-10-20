package examples.counter

// claude 2026-10-13: created (T1, RC-33 part): the prototype's
// apps/Counter.scala (1d100ed) as an example with the quit value
// claude 2026-10-13: changed (T2, RC-33, OS-48 part): the hooks Counter
// (`useSignal`) and its TuiApp main `CounterApp`; the KyoApp main moved to
// examples.counterkyo. README.md "Quick start" shows the lines between the
// two region lines, and ReadmeSuite checks that they are the same.
// claude 2026-10-19: changed: Signal.initRef, not the deprecated useSignal
// (P1b, A5 f)
// claude 2026-10-20: changed: initSig, the short name of Signal.initRef
// (D26)
// region: quickstart
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp, initSig}

/** The Counter: a kyo-ui UI with one piece of state (a `SignalRef`), key
  * handlers and two buttons. `+` and `-` change the count, Tab moves between
  * the buttons, Enter and Space press them, `q` quits (and so does Ctrl-C).
  */
object Counter:

    /** The Counter UI; `quit` ends the run. */
    def ui(quit: Unit < Sync): UI < Sync =
      initSig(0).map(count => view(count, quit))

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
// endregion: quickstart
