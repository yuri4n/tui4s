package examples.widgets

import kyo.*
import tui4s.Component

/** Mounts [[TabsView]] with tui4s's component lifetime. The runner retains
  * local state across parent renders and drops it on unmount. Selection is
  * local by default; a parent can supply a signal and handle change requests.
  * Other Kyo UI renderers can use [[TabsView.ui]] directly. The labels and the
  * options are those of [[TabsView]]:
  *
  * {{{
  * new Tabs("CPU", "Memory", "Network")(heading = h1("Dashboard")).ui
  * }}}
  */
// claude 2026-10-19: changed: the labels are `first, rest*`, as in TabsView
// (P1b, A5 g)
final class Tabs(using Frame)(first: String, rest: String*)(
    initialTab: Int = 0,
    selected: Option[Signal[Int]] = None,
    onChange: Int => Unit < Async = _ => (),
    heading: UI = UI.empty,
    panel: Int => UI = _ => UI.empty,
) extends Component:
    private val widget = new TabsView(first, rest*)(
      initialTab,
      selected,
      onChange,
      heading,
      panel,
    )

    type State = TabsView.State

    def initial: State = widget.initial

    def view(state: SignalRef[State]): UI = widget.view(state)

    /** Gives the mount its own keyboard focus group ([[TabsView.mount]]). */
    // claude 2026-10-19: created (P4): the group comes from an effect at the
    // mount
    override def onMount(state: SignalRef[State]): Any < (Async & Scope) =
      widget.mount(state)
end Tabs
