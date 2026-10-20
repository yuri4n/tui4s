package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: portable-tabs
import kyo.*
import kyo.UI.*
import tui4s.widgets.Tabs

object PortableTabsExample:
    def ui: UI < Sync =
      Tabs("Overview", "Details")(
        panel = index => span(s"Panel $index"),
      ).ui
// endregion: portable-tabs
