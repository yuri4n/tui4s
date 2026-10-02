package examples.ziocounter

import munit.FunSuite
import tui4s.interop.zio.*
import tui4s.interop.zio.testkit.*
import tui4s.testkit.*
import zio.Runtime
import zio.Unsafe
import zio.ZIO

/** The ZIO Counter with the same synchronous tests as the Kyo one (SPEC 6.3),
  * plus the facade's live kit.
  */
class CounterSuite extends FunSuite:
    private def run[A](z: ZIO[Any, Throwable, A]): A =
      Unsafe.unsafe(implicit u =>
        Runtime.default.unsafe.run(z).getOrThrowFiberFailure(),
      )

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

    test("the same screen as the Kyo Counter") {
      val onZio = TuiTest(CounterApp, 40, 5).press('+')
      val onKyo = TuiTest(examples.counter.CounterApp, 40, 5).press('+')
      assertEquals(onZio.screen, onKyo.screen)
    }

    test("live, as a ZIO effect: the real loop on a test terminal") {
      val (n, screen) = run(
        LiveTuiTest(CounterApp, 40, 5)(t =>
          t.press('+', '+', '-') *> t.state.zip(t.screen),
        ),
      )
      assertEquals(n, 1)
      assert(screen.contains("Count: 1"), screen.text)
    }
end CounterSuite
