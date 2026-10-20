package examples.counterstyles

import kyo.*
import kyo.UI.*
import tui4s.Hooks.*
// claude 2026-10-13: changed (T2, adapt): the tui4s package (the prototype
// had tui4s.kyoui)
import tui4s.{Component, Tui, TuiApp}

/** Style 1: hooks. A component is a function that returns `UI < Sync` (or
  * `< (Async & Scope)`).
  */
// claude 2026-10-13: changed (T2): the prototype's `app` and `dynamic` are in
// CounterStyles.ui (one main per package)
object CounterHooks:

    // claude 2026-10-19: changed: the colours of the stylesheet (L1b)
    // claude 2026-10-20: created the region below (L5): the docs page
    // "Components" shows it
    // region: hook-counter
    def counter(label: String): UI < Sync =
      for count <- Signal.initRef(0)
      yield div.style(_.border(1.px, CounterStyles.frame).padding(0.px, 1.px))(
        span(label).style(_.bold.color(CounterStyles.label)),
        span("Count: ", count.map(_.toString))
          .style(_.color(CounterStyles.count)),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
      )
    // endregion: hook-counter
end CounterHooks

// claude 2026-10-20: created the region below (L5): the docs page
// "Components" shows it
// region: class-counter
/** Style 2: a component class with a type member for its state. The runner
  * allocates the state at mount.
  */
final class CounterBox(label: String) extends Component:
    type State = Int
    def initial: Int                    = 0
    // claude 2026-10-19: changed: the colours of the stylesheet (L1b)
    def view(count: SignalRef[Int]): UI =
      div.style(_.border(1.px, CounterStyles.frame).padding(0.px, 1.px))(
        span(label).style(_.bold.color(CounterStyles.label)),
        span("Count: ", count.map(_.toString))
          .style(_.color(CounterStyles.count)),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
      )
end CounterBox
// endregion: class-counter

/** Both styles side by side, in one app. On the left, two hook counters that
  * the runner mounts (`component`), inside a `when`: `h` hides them (they lose
  * their state) and shows them again (they start at 0). On the right, two
  * `Component` counters (`CounterBox`): the runner keeps their state under
  * their position. Tab moves between the eight buttons, Enter and Space press
  * them, `q` quits. The colours of the counters are custom colours that
  * [[colors]] declares once.
  */
// claude 2026-10-13: created (T2, RC-33): one UI for the prototype's three
// apps (CounterHooks.app, CounterHooks.dynamic, CounterComponents.app), so one
// main and one PTY run show both styles
object CounterStyles:
    // claude 2026-10-19: created the region below (L1b): the README section
    // "Overlays, terminal size and theme colors" shows it
    // region: colors
    /** The custom colours of the app, declared once for the whole tree: a
      * kyo-ui `Stylesheet.vars` block (CSS custom properties on `:root`). The
      * run gets it as its stylesheet (`TuiApp.stylesheet`, or the `stylesheet`
      * of `Headless.start`), and an element names a colour with
      * `Style.Color.variable`.
      */
    val colors: Stylesheet = Stylesheet.vars(
      "frame" -> "#64748b",
      "label" -> "#eab308",
      "count" -> "#22c55e",
    )

    val frame: Style.Color = Style.Color.variable("frame")
    val label: Style.Color = Style.Color.variable("label")
    val count: Style.Color = Style.Color.variable("count")
    // endregion: colors

    // claude 2026-10-20: created the region below (L5): the docs page
    // "Components" shows it
    // region: mount
    def ui: UI < Sync =
      for shown <- Signal.initRef(true)
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
    // endregion: mount
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
    def ui: UI < (Async & Scope)        = CounterStyles.ui
    // claude 2026-10-19: created (L1b): the custom colours
    override def stylesheet: Stylesheet = CounterStyles.colors
