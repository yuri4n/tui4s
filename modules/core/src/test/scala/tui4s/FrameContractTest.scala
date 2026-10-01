package tui4s

import jatatui.widgets.gauge.Gauge as JGauge
import kyo.test.*
import tui4s.fixtures.CounterApp
import tui4s.testkit.*

/** The frame contracts of SPEC 7.2 that need no sources: C1, C2, C3. */
class FrameContractTest extends Test[Any]:
    "C1: an unbound key builds no view and draws no frame" in {
      val t      = TuiTest(CounterApp)
      val before = t.stats
      t.press('x', Key.Up)
      val after  = t.stats
      assert(after.viewBuilds == before.viewBuilds)
      assert(after.frames == before.frames)
      assert(after.skippedSame == before.skippedSame + 2)
      assert(
        after.batches == before.batches + 2 && after.events == before.events + 2,
      )
    }

    "C1: a boxed Int above 127 or an equal String counts as the same state" in {
      val big = TuiTest(App[Int](200, n => text(n.toString), (n, _) => n + 0))
      big.press('a')
      assert(big.stats.skippedSame == 1 && big.stats.viewBuilds == 1)
      val str = TuiTest(App[String]("s", s => text(s), (s, _) => new String(s)))
      str.press('a')
      assert(str.stats.skippedSame == 1 && str.stats.viewBuilds == 1)
    }

    "C2: a new state with an equal view builds the view and draws no frame" in {
      val app = App[Int](0, n => text((n / 2).toString), (n, _) => n + 1)
      val t   = TuiTest(app)
      t.press('a')
      assert(t.state == 1)
      assert(t.stats.viewBuilds == 2 && t.stats.frames == 1)
      assert(t.stats.skippedEqualView == 1)
      t.press('a')
      assert(t.stats.viewBuilds == 3 && t.stats.frames == 2)
    }

    "C3: an unchanged gauge draws no frame; a new ratio draws one" in {
      final case class S(ratio: Double, tick: Int)
      val app = App[S](
        S(0.5, 0),
        s => column(gauge(s.ratio, "cpu"), text("load")),
        (s, e) =>
          e match
              case Key.Char('t') => s.copy(tick = s.tick + 1)
              case Key.Char('g') => s.copy(ratio = s.ratio + 0.1)
              case _             => s,
      )
      val t   = TuiTest(app, 20, 4)
      t.press('t')
      assert(t.stats.frames == 1 && t.stats.skippedEqualView == 1)
      t.press('g')
      assert(t.stats.frames == 2)
    }

    "a jatatui Gauge in the escape hatch has no equals: every view draws" in {
      val app = App[Int](
        0,
        _ => widget(JGauge.empty().withRatio(0.5)),
        (n, _) => n + 1,
      )
      val t   = TuiTest(app, 10, 1)
      t.press('a')
      assert(t.stats.frames == 2 && t.stats.skippedEqualView == 0)
    }

    "a resize draws the next frame even when the view is equal" in {
      val t = TuiTest(CounterApp, 40, 5)
      t.resize(30, 5)
      assert(t.stats.frames == 2 && t.stats.viewBuilds == 2)
      assert(t.screen.rows.forall(r => r.length <= 30))
      assert(t.screen.contains("Count: 0"))
    }

    "one batch of several events draws at most one frame" in {
      val t = TuiTest(CounterApp)
      t.send(Key.Char('+'), Key.Char('+'), Key.Char('+'))
      assert(t.state == 3 && t.stats.frames == 2 && t.stats.batches == 1)
    }

    "the counting backend reports the changed cells of the last frame" in {
      val t = TuiTest(CounterApp, 40, 5)
      assert(t.stats.lastChangedCells > 40) // the first frame
      t.press('+')
      assert(t.stats.lastChangedCells == 1) // "0" -> "1"
    }
end FrameContractTest
