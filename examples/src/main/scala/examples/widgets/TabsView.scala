package examples.widgets

import kyo.*
import kyo.UI.*

import java.util.concurrent.atomic.AtomicLong // scalafix:ok DisableSyntax.jvmSync; P4: the group id comes from an effect

/** Tabs built only from Kyo signals, UI values, and callbacks. Each execution
  * of [[ui]] owns fresh selection state unless a parent supplies `selected`.
  * The panel receives the selected index as a scoped slot.
  *
  * With local state, `onChange` runs after the selection changes. With a
  * supplied signal, the callback decides whether to accept the request by
  * updating its source; the displayed selection always follows that signal.
  * Requests are normalized to the label count before the callback runs.
  *
  * Runtime adapters can allocate [[initial]] at mount, retain that state during
  * parent renders, and pass it to [[view]]. The constructor captures the
  * caller's diagnostic `Frame` for later rendering and event effects.
  *
  * The labels come first: `first` and then the `rest`, so a widget without a
  * tab does not compile. The options come in a second parameter list.
  *
  * {{{
  * new TabsView("Overview", "Details")(panel = index => span(s"Panel $index")).ui
  * }}}
  */
// claude 2026-10-19: changed: the labels are `first, rest*`, not a Seq that
// can be empty (P1b, A5 g)
final class TabsView(using Frame)(first: String, rest: String*)(
    initialTab: Int = 0,
    selected: Option[Signal[Int]] = None,
    onChange: Int => Unit < Async = _ => (),
    heading: UI = UI.empty,
    panel: Int => UI = _ => UI.empty,
):
    private val labels: Chunk[String] = Chunk.from(first +: rest)

    private def normalize(index: Int): Int =
      Math.floorMod(index, labels.size)

    /** Fresh local state with a distinct keyboard focus group per mount. */
    def initial: TabsView.State =
      TabsView.State(
        normalize(initialTab),
        s"tabs-${TabsView.nextGroup.incrementAndGet()}",
      )

    /** Builds a reactive UI from state retained by its caller or renderer. */
    def view(state: SignalRef[TabsView.State]): UI =
        val selection = selected.getOrElse(state.map(_.selected)).map(normalize)

        def select(index: Int): Unit < Async =
            val next                = normalize(index)
            val update: Unit < Sync = selected match
                case None    => state.updateAndGet(_.copy(selected = next)).unit
                case Some(_) => Kyo.unit
            update.andThen(Sync.defer(onChange(next)))

        div(
          div.style(_.row.gap(2.px))(
            heading,
            state.map(_.focusGroup).render { group =>
              selection.render { current =>
                nav.style(_.gap(1.px))(
                  fragment(labels.zipWithIndex.map { (label, index) =>
                    button(label)
                      .focusGroup(group)
                      .onKeyDown {
                        case KeyboardEvent(Keyboard.ArrowLeft, _, _)  =>
                          select(index - 1)
                        case KeyboardEvent(Keyboard.ArrowRight, _, _) =>
                          select(index + 1)
                        case _                                        => ()
                      }
                      .onClick(select(index))
                      .style(if index == current then
                          Style.bold.color(Style.Color.yellow)
                      else Style.color(Style.Color.gray))
                  }*),
                )
              }
            },
          ),
          selection.render(panel),
        )
    end view

    /** Allocates fresh local state whenever this effect executes. Keep the
      * returned UI during ordinary reactive updates. Use [[initial]] and
      * [[view]] for a renderer's retained component mount lifecycle.
      */
    def ui: UI < Sync = Sync.defer(Signal.initRef(initial)).map(view)
end TabsView

object TabsView:
    final case class State(selected: Int, focusGroup: String) derives CanEqual

    private val nextGroup = new AtomicLong()
