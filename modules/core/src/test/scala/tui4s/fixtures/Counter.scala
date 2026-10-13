package tui4s.fixtures

import kyo.*
import kyo.UI.*

/** The Counter in plain kyo-ui: only kyo-ui's API. `+` and `-` change the count
  * (a key handler on the root), Tab moves between the buttons, Enter and Space
  * press them. A test can also write the count.
  */
// claude 2026-10-12: changed the Scaladoc (review R1-5, R3-7): no key handler
// and no `Tui.quit` after the cut
// claude 2026-10-13: changed the Scaladoc (C3): the keys are back
object Counter:

    // claude 2026-10-12: created (RC-19): the view over a count that the caller
    // owns, so that a test can write the ref
    // claude 2026-10-13: changed (C3): the key handler of the prototype's app
    // is back on the root
    def view(count: SignalRef[Int]): UI =
      div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('+'), _, _) =>
          count.updateAndGet(_ + 1)
        case KeyboardEvent(Keyboard.Char('-'), _, _) =>
          count.updateAndGet(_ - 1)
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
