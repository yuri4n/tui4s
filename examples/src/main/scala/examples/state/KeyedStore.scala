package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: keyed-store
import examples.widgets.Tabs
import kyo.*
import kyo.UI.*
import pini4s.*
import tui4s.widgets.Model

object KeyedStoreExample:
    def tabs(using StateStore): UI < Sync =
      useState("dashboard.currentTab")(0).map { selected =>
        new Tabs("CPU", "Memory", "Network")(
          selected = Model.Bound(selected),
        ).ui
      }

    def ui: UI < Sync =
      StateStore.init.map { store =>
        given StateStore = store
        for
            first  <- tabs
            second <- tabs
        yield div(first, second)
      }
end KeyedStoreExample
// endregion: keyed-store
