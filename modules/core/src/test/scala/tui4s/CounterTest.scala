package tui4s

import kyo.test.*
import tui4s.fixtures.CounterApp
import tui4s.testkit.*

/** SPEC 6.3 CounterTest. */
class CounterTest extends TuiSuite:
    "update is a pure function" in {
      assert(CounterApp.update(1, Key.Char('+')) == 2)
      assert(CounterApp.update(1, Key.Char('q')) == Quit)
      assert(CounterApp.update(1, Key.Char('x')) == 1)
    }

    "the screen shows the count and the help bar" in {
      val t = TuiTest(CounterApp, 40, 6)
      t.press('+', '+')
      assert(t.state == 2)
      assert(t.screen.contains("Count: 2"))
      assert(t.screen.contains("+ increment · - decrement · q quit"))
      t.press('-')
      assert(t.screen.contains("Count: 1") && t.frames == 4)
    }

    "a key that changes nothing builds no view and draws no frame" in {
      val t      = TuiTest(CounterApp)
      val before = t.stats
      t.press('x', Key.Up)
      assert(t.stats.viewBuilds == before.viewBuilds)
      assert(t.stats.frames == before.frames)
      assert(t.lastTrace.contains("\"in\":[\"key:↑\"]"))
      assert(t.lastTrace.contains("\"state\":\"same\",\"frame\":\"skip-same\""))
    }

    "q quits; Ctrl-C quits; later keys are ignored" in {
      assert(TuiTest(CounterApp).press('q').quit)
      val t = TuiTest(CounterApp)
      t.press(Key.Ctrl('c'))
      assert(t.quit && t.error.isEmpty)
      t.press('+')
      assert(t.state == 0)
    }

    "with ctrlCQuits off, update sees Ctrl-C" in {
      val app = App[Int](
        0,
        n => text(n.toString),
        (n, e) => if e == Key.Ctrl('c') then n + 1 else n,
        options = TuiOptions(ctrlCQuits = false),
      )
      val t   = TuiTest(app)
      t.press(Key.Ctrl('c'))
      assert(!t.quit && t.state == 1)
    }

    "a golden screen" in {
      val t = TuiTest(CounterApp, 40, 5)
      t.press('+')
      assertShows(
        t.screen,
        """╭ Counter ─────────────────────────────╮
            |│                                      │
            |│               Count: 1               │
            |│  + increment · - decrement · q quit  │
            |╰──────────────────────────────────────╯""".stripMargin,
      )
    }

    "the count is bold yellow" in {
      val t      = TuiTest(CounterApp, 40, 5)
      val (x, y) = t.screen.find("Count: 0").get
      assert(t.screen.styleAt(x + 7, y) == Style.bold.fg(Color.Yellow))
      assert(t.screen.styleAt(x, y) == Style.default)
    }
end CounterTest
