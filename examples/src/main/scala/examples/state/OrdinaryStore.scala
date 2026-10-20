package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: ordinary-store
import examples.widgets.Tabs
import kyo.*
import kyo.UI.*
import tui4s.widgets.Model

object OrdinaryStoreExample:
    final class DashboardStore(val selected: SignalRef[Int]):
        val summary: Signal[String]         = selected.map(index => s"Metric $index")
        def select(index: Int): Unit < Sync = selected.set(index)

    def store: DashboardStore < Sync =
      Signal.initRef(0).map(ref => new DashboardStore(ref))

    def view: UI < Env[DashboardStore] =
      Env.use[DashboardStore] { dashboard =>
        div(
          new Tabs("CPU", "Memory", "Network")(
            selected = Model.Controlled(dashboard.selected),
            onChange = index => dashboard.select(index),
          ).ui,
          p(dashboard.summary),
        )
      }

    def ui: UI < Sync = store.map(dashboard => Env.run(dashboard)(view))

    val layer                  = Layer(store)
    def uiWithLayer: UI < Sync = Memo.run(Env.runLayer(layer)(view))
end OrdinaryStoreExample
// endregion: ordinary-store
