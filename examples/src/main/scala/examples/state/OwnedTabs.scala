package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: owned-tabs
import examples.widgets.Tabs
import kyo.*
import kyo.UI.*

object OwnedTabsExample:
    def ui: UI =
      new Tabs("CPU", "Memory", "Network")(
        heading = h1("Dashboard"),
        panel = index => p(s"Selected metric: $index"),
      ).ui
// endregion: owned-tabs
