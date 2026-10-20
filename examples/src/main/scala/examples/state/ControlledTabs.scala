package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: controlled-tabs
import examples.widgets.Tabs
import kyo.*
import kyo.UI.*
import tui4s.widgets.Model

object ControlledTabsExample:
    def ui: UI < Sync =
      Signal.initRef(0).map { selected =>
        div(
          new Tabs("CPU", "Memory", "Network")(
            selected = Model.Controlled(selected),
            onChange = next => if next == 2 then () else selected.set(next),
            panel = index => p(s"Selected metric: $index"),
          ).ui,
          button("Select Network externally").onClick(selected.set(2)),
        )
      }
end ControlledTabsExample
// endregion: controlled-tabs
