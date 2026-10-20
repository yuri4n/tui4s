package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: emitted-tabs
import examples.widgets.Tabs
import kyo.*
import kyo.UI.*

object EmittedTabsExample:
    enum Event:
        case Selected(index: Int)

    def selectionChanged(index: Int): Unit < Emit[Event] =
      Emit.value[Event](Event.Selected(index))

    def ui: UI < Sync =
      Signal.initRef(Vector.empty[Int]).map { changes =>
        def record(event: Event): Unit < Sync = event match
            case Event.Selected(index) => changes.updateAndGet(_ :+ index).unit

        div(
          new Tabs("Overview", "Details")(
            onChange =
              index => Emit.runForeach[Event](selectionChanged(index))(record),
          ).ui,
          p(changes.map(values => s"Selections: ${values.mkString(", ")}")),
        )
      }
end EmittedTabsExample
// endregion: emitted-tabs
