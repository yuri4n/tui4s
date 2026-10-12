package tui4s.fixtures

import kyo.*
import kyo.UI.*

/** The Counter in plain kyo-ui: only kyo-ui's API, plus `Tui.quit` for the quit
  * key.
  */
object Counter:

    // claude 2026-10-12: created (RC-19): the view over a count that the caller
    // owns, so that a test can write the ref
    def view(count: SignalRef[Int]): UI =
      div(
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

    val app: UI < Sync = Signal.initRef(0).map(view)

    // claude 2026-10-12: created (RC-19): the view of one counter over its ref
    def counterView(label: String, count: SignalRef[Int]): UI =
      div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
        span(label).style(_.bold),
        span("Count: ", count.map(_.toString)),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
      )

    /** One counter as a function: its state is allocated when the function
      * runs.
      */
    def counter(label: String): UI < Sync =
      Signal.initRef(0).map(count => counterView(label, count))

    // claude 2026-10-12: created (RC-19): the view of two counters
    def twoCountersView(left: UI, right: UI): UI =
      div(
        h1("Two counters"),
        div.style(_.row.gap(2.px))(left, right),
        p("Tab moves between the four buttons, Enter presses, q quits").style(
          _.color(Style.Color.gray),
        ),
      )

    /** Two independent counters side by side. */
    val twoCounters: UI < Sync =
      for
          left  <- counter("Left")
          right <- counter("Right")
      yield twoCountersView(left, right)
end Counter
