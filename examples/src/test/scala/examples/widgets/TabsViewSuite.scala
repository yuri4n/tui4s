package examples.widgets

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.*
import munit.FunSuite

/** Exercises public Kyo UI trees and callbacks without a renderer. */
class TabsViewSuite extends FunSuite:

    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    private def panel(index: Int): UI = span(s"[panel=$index]")

    private def nodes(ui: UI)(using Frame): Vector[UI] < Sync =
      ui match
          case reactive: Reactive[?] => reactive.signal.current.map(nodes)
          case fragment: Fragment[?] => children(fragment.children)
          case element: Element      => children(element.children).map(element +: _)
          case _                     => Vector(ui)

    private def children(values: Chunk[UI])(using Frame): Vector[UI] < Sync =
      Kyo.foreach(values)(nodes).map(_.flatten.toVector)

    private def buttons(ui: UI)(using Frame): Vector[Button] < Sync =
      nodes(ui).map(_.collect { case button: Button => button })

    private def text(ui: UI)(using Frame): String < Sync =
      nodes(ui).map(_.collect { case Text(value) => value }.mkString)

    private def click(ui: UI, index: Int)(using Frame): Unit < Async =
      buttons(ui).map(_(index).attrs.onClick.get.unit)

    test(
      "reusing an allocation effect creates independent state and focus groups",
    ) {
      val allocate                                      = new TabsView("One", "Two", "Three")(panel = panel).ui
      val (firstGroup, secondGroup, changed, unchanged) = run {
        for
            first     <- allocate
            second    <- allocate
            a         <- buttons(first)
            b         <- buttons(second)
            _         <- click(first, 1)
            changed   <- text(first)
            unchanged <- text(second)
        yield (
          a.head.attrs.focusGroup,
          b.head.attrs.focusGroup,
          changed,
          unchanged,
        )
      }
      assertNotEquals(firstGroup, secondGroup)
      assert(changed.contains("[panel=1]"), changed)
      assert(unchanged.contains("[panel=0]"), unchanged)
    }

    test(
      "owned callbacks run after selection changes and receive normalized requests",
    ) {
      val (before, requests, shown) = run {
        for
            state    <- Signal.initRef(TabsView.State(0, "owned-test"))
            seen     <- Signal.initRef(Vector.empty[(Int, Int)])
            widget    = new TabsView("One", "Two", "Three")(
                          onChange = next =>
                            state.get
                              .map(s => seen.updateAndGet(_ :+ (next, s.selected)))
                              .unit,
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
        yield (before, requests, shown)
      }
      assertEquals(before, Vector.empty[(Int, Int)])
      assertEquals(requests, Vector((2, 2)))
      assert(shown.contains("[panel=2]"), shown)
    }

    test(
      "a controlled read-only signal changes only when its owner changes it",
    ) {
      val (source, afterClick, afterWrite) = run {
        for
            raw        <- Signal.initRef(0)
            ui         <- new TabsView("One", "Two", "Three")(
                            selected = Some(raw.map(_ * 2)),
                            panel = panel,
                          ).ui
            _          <- click(ui, 1)
            source     <- raw.get
            afterClick <- text(ui)
            _          <- raw.set(1)
            afterWrite <- text(ui)
        yield (source, afterClick, afterWrite)
      }
      assertEquals(source, 0)
      assert(afterClick.contains("[panel=0]"), afterClick)
      assert(afterWrite.contains("[panel=2]"), afterWrite)
    }
end TabsViewSuite
