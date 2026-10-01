package examples.counter

import munit.FunSuite
import tui4s.*
import tui4s.testkit.*

class CounterSuite extends FunSuite:
    test("update is a pure function") {
      assertEquals(CounterApp.update(1, Key.Char('+')), 2: Next[Int])
      assertEquals(CounterApp.update(1, Key.Char('q')), Quit: Next[Int])
      assertEquals(CounterApp.update(1, Key.Char('x')), 1: Next[Int])
    }

    test("the screen shows the count and the help bar") {
      val t = TuiTest(CounterApp, 40, 6).press('+', '+')
      assertEquals(t.state, 2)
      assert(t.screen.contains("Count: 2"), t.screen.text)
      assert(t.screen.contains("+ increment · - decrement · q quit"))
    }

    test("a key that changes nothing builds no view and draws no frame") {
      val t      = TuiTest(CounterApp)
      val before = t.stats
      t.press('x', Key.Up)
      assertEquals(t.stats.viewBuilds, before.viewBuilds)
      assertEquals(t.stats.frames, before.frames)
      assert(t.lastTrace.contains("\"frame\":\"skip-same\""), t.lastTrace)
    }

    test("q quits; Ctrl-C quits") {
      assert(TuiTest(CounterApp).press('q').quit)
      assert(TuiTest(CounterApp).press(Key.Ctrl('c')).quit)
    }

    test("a golden screen") {
      val t = TuiTest(CounterApp, 40, 5).press('+')
      assertScreen(
        t.screen,
        """╭ Counter ─────────────────────────────╮
          |│                                      │
          |│               Count: 1               │
          |│  + increment · - decrement · q quit  │
          |╰──────────────────────────────────────╯""".stripMargin,
      )
    }
end CounterSuite
