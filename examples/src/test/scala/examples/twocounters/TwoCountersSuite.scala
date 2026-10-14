package examples.twocounters

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless

/** The two counters on the headless test kit: each counter has its own state,
  * and `q` ends the run (`Tui.quit`).
  */
// claude 2026-10-13: created (T2, RC-33)
class TwoCountersSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    test("the first screen shows both counters at 0") {
      val s = run(Headless.start(60, 10)(_ => TwoCountersApp.ui).map(_.screen))
      assert(s.contains("Two counters"), s.text)
      assert(s.contains("Left") && s.contains("Right"), s.text)
      assert(s.find("Count: 0").isDefined, s.text)
    }

    test("the counters are independent: Left gets 1, Right gets 2") {
      val s     = run {
        for
            h <- Headless.start(60, 10)(_ => TwoCountersApp.ui)
            _ <- h.key(Keyboard.Enter) // the focus starts on the + of Left
            _ <- h.key(Keyboard.Tab)   // the - of Left
            _ <- h.key(Keyboard.Tab)   // the + of Right
            _ <- h.key(Keyboard.Enter)
            _ <- h.key(Keyboard.Space)
            s <- h.screen
        yield s
      }
      val left  = s.find("Count: 1")
      val right = s.find("Count: 2")
      assert(left.isDefined && right.isDefined, s.text)
      assert(
        left.get._1 < right.get._1,
        s"Left must be left of Right:\n${s.text}",
      )
    }

    test("q ends the run") {
      val ended = run {
        for
            h     <- Headless.start(60, 10)(_ => TwoCountersApp.ui)
            _     <- h.char('q')
            ended <- h.awaitEnd(10.seconds)
        yield ended
      }
      assert(ended, "q did not end the run")
    }
end TwoCountersSuite
