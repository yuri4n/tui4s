package examples.counterstyles

import kyo.*
import kyo.UI.*
// claude 2026-10-13: changed (T2, adapt): the tui4s package (the prototype
// had tui4s.kyoui)
import tui4s.Component
import tui4s.Hooks.*
import tui4s.Tui

/** Style 1: hooks. A component is a function that returns `UI < Sync` (or
  * `< (Async & Scope)`).
  */
object CounterHooks:

    def counter(label: String): UI < Sync =
      for count <- useSignal(0)
      yield div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
        span(label).style(_.bold),
        span("Count: ", count.map(_.toString)),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
      )

    /** Sequenced at the top: each call allocates its own state once. */
    val app: UI < Sync =
      for
          left  <- counter("Left")
          right <- counter("Right")
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
        case _                                       => ()
      }(h1("Hooks"), div.style(_.row.gap(2.px))(left, right))

    /** The same, mounted by the runner: `component(...)` runs the effect when
      * the node mounts. Here the counters live inside a `when`: hiding them
      * drops their state, showing them again starts from 0.
      */
    val dynamic: UI < Sync =
      for shown <- useSignal(true)
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('h'), _, _) => shown.updateAndGet(!_)
        case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
        case _                                       => ()
      }(
        h1("Hooks, mounted by the runner (h hides/shows)"),
        when(shown)(
          div.style(_.row.gap(2.px))(
            component(counter("Left")),
            component(counter("Right")),
          ),
        ),
      )
end CounterHooks

/** Style 2: a component class with a type member for its state. The runner
  * allocates the state at mount.
  */
final class CounterBox(label: String) extends Component:
    type State = Int
    def initial: Int                    = 0
    def view(count: SignalRef[Int]): UI =
      div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
        span(label).style(_.bold),
        span("Count: ", count.map(_.toString)),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
      )
end CounterBox

object CounterComponents:
    val app: UI =
      div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
        case _                                       => ()
      }(
        h1("Components"),
        div.style(_.row.gap(2.px))(CounterBox("Left"), CounterBox("Right")),
      )
end CounterComponents

// claude 2026-10-13: changed (T2, adapt): the three KyoApp mains of the
// prototype are cut; the example gets one TuiApp main in its own commit (one
// main per package).
