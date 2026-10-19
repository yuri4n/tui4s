package examples.counter

// claude 2026-10-13: created (T1, RC-33 part)
// claude 2026-10-13: changed (T2, RC-33, OS-48 part): README.md "Quick start"
// shows the lines between the two region lines (ReadmeSuite checks them);
// new leaves: the app's own UI (CounterApp.ui with Tui.quit), a written ref
// region: quickstart-test
import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless

/** The Counter on the headless test kit: no terminal. */
class CounterSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

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
    // endregion: quickstart-test

    test("the first screen shows the title, the count and the two buttons") {
      val s = run(Headless.start(50, 8)(Counter.ui).map(_.screen))
      assert(s.contains("Counter"), s.text)
      assert(s.contains("Count: 0"), s.text)
      assert(s.contains("+") && s.contains("-"), s.text)
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
            _    <- c.key(Keyboard.Char('c'), UI.Modifiers(ctrl = true))
            endC <- c.awaitEnd(10.seconds)
        yield (endQ, endC)
      }
      assert(byQ, "q did not end the run")
      assert(byCtrlC, "Ctrl-C did not end the run")
    }

    // claude 2026-10-13: created (T2): the UI of the TuiApp quits through
    // Tui.quit (it ignores the quit value of the kit)
    test("CounterApp.ui: + changes the count, q ends the run (Tui.quit)") {
      val (s, ended) = run {
        for
            h     <- Headless.start(50, 8)(_ => CounterApp.ui)
            _     <- h.char('+')
            s     <- h.screen
            _     <- h.char('q')
            ended <- h.awaitEnd(10.seconds)
        yield (s, ended)
      }
      assert(s.contains("Count: 1"), s.text)
      assert(ended, "q did not end the run")
    }

    // claude 2026-10-13: created (T2): a test that writes the app's ref waits
    // for the screen with awaitScreen (the kit itself does not wait)
    test("a count that the test writes shows on the screen") {
      val shown = run {
        for
            count <- Signal.initRef(0)
            h     <- Headless.start(50, 8)(quit => Counter.view(count, quit))
            _     <- count.set(41)
            seen  <- h.awaitScreen(_.contains("Count: 41"), 10.seconds)
        yield seen
      }
      assert(shown, "the screen never showed Count: 41")
    }
end CounterSuite
