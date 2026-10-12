package tui4s.fixtures

// claude 2026-10-09: created (carried from .work/proto/kyoui-runner/apps/Counter.scala; new path and package only)

import kyo.*
import kyo.UI.*
import tui4s.Tui

/** The Counter in plain kyo-ui: only kyo-ui's API, plus `Tui.quit` for the quit key. */
object Counter:

    val app: UI < Sync =
        for count <- Signal.initRef(0)
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('+'), _, _) => count.updateAndGet(_ + 1)
            case KeyboardEvent(Keyboard.Char('-'), _, _) => count.updateAndGet(_ - 1)
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(
            h1("Counter"),
            span("Count: ", count.map(_.toString)).id("count"),
            div.style(_.row.gap(1.px))(
                button("+").onClick(count.updateAndGet(_ + 1)),
                button("-").onClick(count.updateAndGet(_ - 1))
            ),
            p("+ / - change it, Tab moves, Enter presses, q quits").style(_.color(Style.Color.gray))
        )

    /** One counter as a function: its state is allocated when the function runs. */
    def counter(label: String): UI < Sync =
        for count <- Signal.initRef(0)
        yield div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
            span(label).style(_.bold),
            span("Count: ", count.map(_.toString)),
            div.style(_.row.gap(1.px))(
                button("+").onClick(count.updateAndGet(_ + 1)),
                button("-").onClick(count.updateAndGet(_ - 1))
            )
        )

    /** Two independent counters side by side. */
    val twoCounters: UI < Sync =
        for
            left  <- counter("Left")
            right <- counter("Right")
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(
            h1("Two counters"),
            div.style(_.row.gap(2.px))(left, right),
            p("Tab moves between the four buttons, Enter presses, q quits").style(_.color(Style.Color.gray))
        )
end Counter

object CounterMain extends KyoApp:
    run(Tui.run(Counter.app))

object TwoCountersMain extends KyoApp:
    run(Tui.run(Counter.twoCounters))
