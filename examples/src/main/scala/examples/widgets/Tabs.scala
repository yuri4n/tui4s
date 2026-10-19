package examples.widgets

import kyo.*
import tui4s.Component
import tui4s.widgets.{Model, Tabs as TabsWidget}

/** Mounts the plain widget `tui4s.widgets.Tabs` with tui4s's component
  * lifetime: the runner keeps the local state across parent renders and drops
  * it at the unmount. The plain widget allocates its state when its `ui` effect
  * runs, so it cannot keep a selection inside a `render` closure that runs
  * again; this adapter can. The labels and the options are those of the widget:
  *
  * {{{
  * new Tabs("CPU", "Memory", "Network")(heading = h1("Dashboard")).ui
  * }}}
  */
// claude 2026-10-19: changed: an adapter of tui4s.widgets.Tabs (L2b): the
// widget moved to tui4s-widgets, the selection is a Model
final class Tabs(using Frame)(first: String, rest: String*)(
    selected: Model[Int] = Model.Local(0),
    onChange: Int => Any < Async = (_: Int) => (),
    heading: UI = UI.empty,
    panel: Int => UI = _ => UI.empty,
) extends Component:
    private val widget =
      new TabsWidget(first, rest*)(selected, onChange, heading, panel)

    type State = TabsWidget.State

    def initial: State = widget.initial

    def view(state: SignalRef[State]): UI = widget.view(state)

    /** Gives the mount its own keyboard focus group (`Tabs.mount`). */
    override def onMount(state: SignalRef[State]): Any < (Async & Scope) =
      widget.mount(state)
end Tabs
