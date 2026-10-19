package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.test.*

import UiTree.*

/** The tabs through their public kyo-ui tree and callbacks, with no renderer
  * (moved from the examples' TabsViewSuite).
  */
// claude 2026-10-19: created (L2b): examples TabsViewSuite moved here; the
// selection is a Model
class TabsTest extends Test[Any]:

    private def panel(index: Int): UI = span(s"[panel=$index]")

    "reusing an allocation effect gives independent state and focus groups" in {
      val allocate = Tabs("One", "Two", "Three")(panel = panel).ui
      for
          first     <- allocate
          second    <- allocate
          a         <- buttons(first)
          b         <- buttons(second)
          _         <- click(first, 1)
          changed   <- text(first)
          unchanged <- text(second)
      yield
          assert(a.head.attrs.focusGroup != b.head.attrs.focusGroup)
          assert(changed.contains("[panel=1]"), changed)
          assert(unchanged.contains("[panel=0]"), unchanged)
      end for
    }

    "a local model: onChange runs after the change, with the wrapped index" in {
      for
          state    <- Signal.initRef(Tabs.State(0, Present("owned-test")))
          seen     <- Signal.initRef(Chunk.empty[(Int, Int)])
          widget    =
            Tabs("One", "Two", "Three")(
              onChange = next =>
                state.get.map(s => seen.updateAndGet(_ :+ (next, s.selected))),
              panel = panel,
            )
          ui        = widget.view(state)
          before   <- seen.get
          values   <- buttons(ui)
          _        <- values.head.attrs.onKeyDown.get(
                        KeyboardEvent(Keyboard.ArrowLeft, Modifiers.none, Absent),
                      )
          requests <- seen.get
          shown    <- text(ui)
      yield
          assert(before.isEmpty)
          assert(requests == Chunk((2, 2)))
          assert(shown.contains("[panel=2]"), shown)
    }

    "a controlled signal changes only when its owner changes it" in {
      for
          raw        <- Signal.initRef(0)
          ui         <- Tabs("One", "Two", "Three")(
                          selected = Model.Controlled(raw.map(_ * 2)),
                          panel = panel,
                        ).ui
          _          <- click(ui, 1)
          source     <- raw.get
          afterClick <- text(ui)
          _          <- raw.set(1)
          afterWrite <- text(ui)
      yield
          assert(source == 0)
          assert(afterClick.contains("[panel=0]"), afterClick)
          assert(afterWrite.contains("[panel=2]"), afterWrite)
    }

    "a bound ref is written before onChange; the same tab runs nothing" in {
      for
          ref  <- Signal.initRef(0)
          seen <- Signal.initRef(Chunk.empty[(Int, Int)])
          ui   <- Tabs("One", "Two")(
                    selected = Model.Bound(ref),
                    onChange =
                      n => ref.get.map(r => seen.updateAndGet(_ :+ (n, r))),
                  ).ui
          _    <- click(ui, 1)
          _    <- click(ui, 1)
          all  <- seen.get
          now  <- ref.get
      yield assert(all == Chunk((1, 1)) && now == 1)
    }

    "the tab buttons have the role tab in a tablist" in {
      for
          ui   <- Tabs("A", "B")().ui
          tabs <- textsOf(ui, "tab")
          list <- byRole(ui, "tablist")
      yield assert(tabs == Chunk("A", "B") && list.nonEmpty)
    }
end TabsTest
