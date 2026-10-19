package examples.widgets

import kyo.*
import kyo.UI.*
import munit.FunSuite
import tui4s.testkit.Headless

/** The widgets: the text of the gauge and of the sparkline (pure functions),
  * and the tabs on the headless test kit.
  */
// claude 2026-10-13: created (T2, RC-33): Widgets is a library with no main
class WidgetsSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    test("gaugeText: the filled cells and the percentage") {
      assertEquals(Widgets.gaugeText(0.42, 10), "████░░░░░░  42%")
      assertEquals(Widgets.gaugeText(0.0, 4), "░░░░   0%")
      assertEquals(Widgets.gaugeText(1.0, 4), "████ 100%")
    }

    test("gaugeText: a ratio outside [0, 1] is cut to it") {
      assertEquals(Widgets.gaugeText(-0.5, 4), Widgets.gaugeText(0.0, 4))
      assertEquals(Widgets.gaugeText(1.7, 4), Widgets.gaugeText(1.0, 4))
    }

    test("sparklineText: one block per value, scaled to [lo, hi]") {
      assertEquals(Widgets.sparklineText(Chunk.empty), "")
      assertEquals(Widgets.sparklineText(Chunk(0.0, 0.5, 1.0)), "▁▅█")
      assertEquals(Widgets.sparklineText(Chunk(10.0, 20.0), 10, 20), "▁█")
      assertEquals(Widgets.sparklineText(Chunk(-1.0, 2.0)), "▁█")
    }

    test("tabs: Enter on a tab selects it; the selected tab is bold") {
      val (first, second)                          = run {
        for
            selected <- Signal.initRef(0)
            h        <- Headless.start(40, 3)(_ =>
                          Widgets.tabs(selected, "One", "Two", "Three"),
                        )
            first    <- h.screen
            _        <- h.key(Keyboard.ArrowRight) // the focus group
            _        <- h.key(Keyboard.Enter)
            second   <- h.screen
        yield (first, second)
      }
      def bold(s: tui4s.testkit.Screen, t: String) =
        s.find(t).exists((x, y) => s.styleAt(x, y).bold)
      assert(bold(first, "One") && !bold(first, "Two"), first.text)
      assert(bold(second, "Two") && !bold(second, "One"), second.text)
    }
end WidgetsSuite
