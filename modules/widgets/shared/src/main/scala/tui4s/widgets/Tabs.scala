package tui4s.widgets

import kyo.*
import kyo.UI.*

/** Tabs: a row of tab buttons and the panel of the selected tab.
  *
  * The labels come first: `first` and then the `rest`, so tabs without a label
  * do not compile. The options come in a second parameter list:
  *   - `selected`: who owns the selected index ([[Model]]; the default: the
  *     widget, from tab 0). Every index wraps into the labels, also the index
  *     that `onChange` and `panel` get.
  *   - `onChange`: called with each new index, after the write of a local or
  *     bound model; for a controlled model it is the request.
  *   - `heading`: a slot left of the tabs; `panel`: the slot of the selected
  *     tab.
  *
  * Keys (the tab buttons are one focus group): Tab moves the focus without a
  * selection; ArrowLeft and ArrowRight select the tab before or after the
  * focused one, and move the focus there; Enter and Space select the focused
  * tab. The selected tab is bold in `--tui4s-accent`, the others are in
  * `--tui4s-muted`.
  *
  * [[ui]] allocates the state and a focus group (`UI < Sync`). A runner that
  * keeps a mount across renders uses [[initial]], [[mount]] and [[view]] (the
  * tui4s adapter `examples.widgets.Tabs` does). The constructor captures the
  * caller's `Frame` for the later render and event effects.
  *
  * {{{
  * Tabs("Overview", "Details")(panel = index => span(s"Panel $index")).ui
  * Tabs("CPU", "Memory")(selected = Model.Bound(tab)).ui
  * }}}
  */
// claude 2026-10-19: created (L2b): examples.widgets.TabsView moved here;
// changed: the selection is a Model (owned, bound or controlled)
final class Tabs(using Frame)(first: String, rest: String*)(
    selected: Model[Int] = Model.Local(0),
    onChange: Int => Any < Async = (_: Int) => (),
    heading: UI = UI.empty,
    panel: Int => UI = _ => UI.empty,
):
    private val labels: Chunk[String] = Chunk.from(first +: rest)

    private def normalize(index: Int): Int = Math.floorMod(index, labels.size)

    /** Fresh local state with no focus group: [[mount]] gives it one. */
    def initial: Tabs.State =
        val start = selected match
            case Model.Local(i) => i
            case _              => 0
        Tabs.State(normalize(start), Absent)

    /** The effect of a mount: gives `state` a fresh keyboard focus group when
      * it has none, so two mounted tabs do not move the focus of each other.
      * Run it once per mount.
      */
    def mount(state: SignalRef[Tabs.State]): Unit < Sync =
      Tabs.newGroup.map(group =>
        state
          .updateAndGet(s =>
            if s.focusGroup.isEmpty then s.copy(focusGroup = Present(group))
            else s,
          )
          .unit,
      )

    /** The reactive UI of the state that its caller or renderer keeps. */
    def view(state: SignalRef[Tabs.State]): UI =
        val selection: Signal[Int] = (selected match
            case Model.Local(_)      => state.map(_.selected)
            case Model.Bound(ref)    => ref
            case Model.Controlled(s) => s
        ).map(normalize)

        def select(index: Int): Unit < Async =
            val next = normalize(index)
            selection.current.map(now =>
              if now == next then Kyo.unit
              else
                  val write: Unit < Sync = selected match
                      case Model.Local(_)      =>
                        state.updateAndGet(_.copy(selected = next)).unit
                      case Model.Bound(ref)    => ref.set(next)
                      case Model.Controlled(_) => Kyo.unit
                  write.andThen(onChange(next)).unit,
            )
        end select

        div(
          div.style(_.row.gap(2.px))(
            heading,
            state.map(_.focusGroup).render { group =>
              selection.render { current =>
                nav
                  .role("tablist")
                  .style(_.gap(1.px))(
                    fragment(labels.zipWithIndex.map { (label, index) =>
                      val tab = button(label).role("tab")
                      group
                        .fold(tab)(tab.focusGroup)
                        .onKeyDown {
                          case KeyboardEvent(Keyboard.ArrowLeft, _, _)  =>
                            select(index - 1)
                          case KeyboardEvent(Keyboard.ArrowRight, _, _) =>
                            select(index + 1)
                          case _                                        => Kyo.unit
                        }
                        .onClick(select(index))
                        .style(
                          if index == current then
                              Style.bold.color(Colors.accent)
                          else Looks.muted,
                        )
                    }*),
                  )
              }
            },
          ),
          selection.render(panel),
        )
    end view

    /** Allocates fresh state and a fresh focus group each time that this effect
      * runs. Keep the returned UI across reactive updates; use [[initial]],
      * [[mount]] and [[view]] for a renderer's retained mount.
      */
    def ui: UI < Sync =
      Tabs.newGroup
        .map(group => Signal.initRef(initial.copy(focusGroup = Present(group))))
        .map(view)
end Tabs

/** The state of [[Tabs]] and its focus group names. */
// claude 2026-10-19: created (L2b): moved from examples.widgets.TabsView
object Tabs:
    /** The state of one mounted widget.
      *
      * @param selected
      *   the selected tab of a local model
      * @param focusGroup
      *   the keyboard focus group of the tab buttons; absent until
      *   [[Tabs.mount]] gives one
      */
    final case class State(selected: Int, focusGroup: Maybe[String])
        derives CanEqual

    /** A fresh focus group name from Kyo's `Random`: no global counter. */
    private def newGroup(using Frame): String < Sync =
      Looks.freshId("tabs")
end Tabs
