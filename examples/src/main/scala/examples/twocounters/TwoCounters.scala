package examples.twocounters

import kyo.*
import kyo.UI.*
// claude 2026-10-13: changed (T2, adapt): tui4s.Tui (the prototype had
// tui4s.kyoui.Tui)
import tui4s.{Tui, TuiApp}

/** Two independent counters side by side, in plain kyo-ui: a counter is a
  * function that allocates its own state when it runs, so each call is a new
  * counter. Tab moves between the four buttons, Enter and Space press them, `q`
  * quits (`Tui.quit`).
  */
// claude 2026-10-13: changed (T2, RC-33): the two counters of the prototype's
// apps/Counter.scala; its single Counter is examples.counter
object TwoCounters:

    /** One counter as a function: its state is allocated when the function
      * runs.
      */
    def counter(label: String): UI < Sync =
      for count <- Signal.initRef(0)
      yield div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
        span(label).style(_.bold),
        span("Count: ", count.map(_.toString)),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
      )

    /** Two independent counters side by side. */
    // claude 2026-10-13: changed (T2): a def named ui (the prototype's val
    // twoCounters)
    def ui: UI < Sync =
      for
          left  <- counter("Left")
          right <- counter("Right")
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
        case _                                       => ()
      }(
        h1("Two counters"),
        div.style(_.row.gap(2.px))(left, right),
        p("Tab moves between the four buttons, Enter presses, q quits").style(
          _.color(Style.Color.gray),
        ),
      )
end TwoCounters

/** Runs the two counters in the real terminal. Start it with its start script,
  * not with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/twocounters
  * }}}
  */
// claude 2026-10-13: created (T2, RC-33)
object TwoCountersApp extends TuiApp:
    def ui: UI < (Async & Scope) = TwoCounters.ui
