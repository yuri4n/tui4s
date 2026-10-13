package examples.counter

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless

/** The Counter on the headless kit (no terminal): the keys of the example
  * change the screen, and `q` and Ctrl-C end the run.
  */
// claude 2026-10-13: created (T1, RC-33 part)
class CounterSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    test("the first screen shows the title, the count and the two buttons") {
      val s = run(Headless.start(50, 8)(Counter.ui).map(_.screen))
      assert(s.contains("Counter"), s.text)
      assert(s.contains("Count: 0"), s.text)
      assert(s.contains("+") && s.contains("-"), s.text)
    }

    test("+ and - change the count") {
      val s = run {
        for
            h <- Headless.start(50, 8)(Counter.ui)
            _ <- h.char('+')
            _ <- h.char('+')
            _ <- h.char('-')
            s <- h.screen
        yield s
      }
      assert(s.contains("Count: 1"), s.text)
    }

    test("Tab moves to the next button, Enter presses it") {
      val s = run {
        for
            h <- Headless.start(50, 8)(Counter.ui)
            _ <- h.key(Keyboard.Enter) // the focus starts on the + button
            _ <- h.key(Keyboard.Tab)
            _ <- h.key(Keyboard.Enter)
            _ <- h.key(Keyboard.Enter)
            s <- h.screen
        yield s
      }
      assert(s.contains("Count: -1"), s.text)
    }

    test("q ends the run; Ctrl-C ends the run") {
      val (byQ, byCtrlC) = run {
        for
            q    <- Headless.start(50, 8)(Counter.ui)
            _    <- q.char('q')
            endQ <- q.awaitEnd(10.seconds)
            c    <- Headless.start(50, 8)(Counter.ui)
            _    <- c.key(Keyboard.Char('c'), ctrl = true)
            endC <- c.awaitEnd(10.seconds)
        yield (endQ, endC)
      }
      assert(byQ, "q did not end the run")
      assert(byCtrlC, "Ctrl-C did not end the run")
    }
end CounterSuite
