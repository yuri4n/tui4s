package examples.counterstyles

import kyo.*
import kyo.UI.*
// claude 2026-10-13: changed (T2, adapt): the tui4s package (the prototype
// had tui4s.kyoui)
import tui4s.Component
import tui4s.Hooks.*
import tui4s.Tui
import tui4s.TuiApp

/** Style 1: hooks. A component is a function that returns `UI < Sync` (or
  * `< (Async & Scope)`).
  */
// claude 2026-10-13: changed (T2): the prototype's `app` and `dynamic` are in
// CounterStyles.ui (one main per package)
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

/** Both styles side by side, in one app. On the left, two hook counters that
  * the runner mounts (`component`), inside a `when`: `h` hides them (they lose
  * their state) and shows them again (they start at 0). On the right, two
  * `Component` counters (`CounterBox`): the runner keeps their state under
  * their position. Tab moves between the eight buttons, Enter and Space press
  * them, `q` quits.
  */
// claude 2026-10-13: created (T2, RC-33): one UI for the prototype's three
// apps (CounterHooks.app, CounterHooks.dynamic, CounterComponents.app), so one
// main and one PTY run show both styles
object CounterStyles:
    def ui: UI < Sync =
      for shown <- useSignal(true)
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('h'), _, _) => shown.updateAndGet(!_)
        case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
        case _                                       => ()
      }(
        h1("Counter styles: h hides the hook counters, q quits"),
        div.style(_.row.gap(4.px))(
          div(
            h2("Hooks"),
            when(shown)(
              div.style(_.row.gap(2.px))(
                component(CounterHooks.counter("Left")),
                component(CounterHooks.counter("Right")),
              ),
            ),
          ),
          div(
            h2("Components"),
            div.style(_.row.gap(2.px))(CounterBox("Left"), CounterBox("Right")),
          ),
        ),
      )
end CounterStyles

/** Runs both counter styles in the real terminal. Start it with its start
  * script, not with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/counterstyles
  * }}}
  */
// claude 2026-10-13: created (T2, RC-33)
object CounterStylesApp extends TuiApp:
    def ui: UI < (Async & Scope) = CounterStyles.ui
