package examples.widgets

import kyo.*
import tui4s.Component

/** Mounts [[TabsView]] with tui4s's component lifetime. The runner retains
  * local state across parent renders and drops it on unmount. Selection is
  * local by default; a parent can supply a signal and handle change requests.
  * Other Kyo UI renderers can use [[TabsView.ui]] directly.
  */
final class Tabs(using Frame)(
    labels: Seq[String],
    initialTab: Int = 0,
    selected: Option[Signal[Int]] = None,
    onChange: Int => Unit < Async = _ => (),
    heading: UI = UI.empty,
    panel: Int => UI = _ => UI.empty,
) extends Component:
    private val widget = new TabsView(
      labels,
      initialTab,
      selected,
      onChange,
      heading,
      panel,
    )

    type State = TabsView.State

    def initial: State = widget.initial

    def view(state: SignalRef[State]): UI = widget.view(state)
end Tabs
