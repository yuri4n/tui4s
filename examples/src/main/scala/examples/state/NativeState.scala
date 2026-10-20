package examples.state

// claude 2026-10-20: created: a block of examples/README.md as a compiled
// source (MarkdownRegionsSuite checks that the block is the region below)
// region: native-state
import kyo.*
import kyo.UI.*

object NativeStateExample:
    def ui: UI < Sync =
      for
          name     <- Signal.initRef("Ada")
          enabled  <- Signal.initRef(true)
          lastEdit <- Signal.initRef("")
          greeting  = name.map(value => s"Hello, $value")
      yield div(
        input.value(name).onInput(_ => name.get.map(lastEdit.set)),
        checkbox.checked(enabled),
        p(greeting),
        p(lastEdit.map(value => s"Last edit: $value")),
        enabled.render(value => span(if value then "Enabled" else "Disabled")),
      )
end NativeStateExample
// endregion: native-state
