package examples.counterstyles

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}

/** Both counter styles on the headless test kit: the hook counters and the
  * `Component` counters count on their own, and the hook counters inside the
  * `when` lose their state when they are hidden.
  */
// claude 2026-10-13: created (T2, RC-33)
class CounterStylesSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    /** The counts on the screen, from left to right (one row has them all). */
    private def counts(s: Screen): Seq[Int] =
      s.text.linesIterator
        .map(l =>
          "Count: (-?[0-9]+)".r
            .findAllMatchIn(l)
            .map(m => String.valueOf(m.group(1)).toInt),
        )
        .map(_.toSeq)
        .find(_.nonEmpty)
        .getOrElse(Nil)

    private def start(using Frame): Headless < (Async & Scope) =
      Headless.start(90, 12)(_ => CounterStylesApp.ui)

    test("the first screen shows both styles, four counters at 0") {
      val s = run(start.map(_.screen))
      assert(s.contains("Hooks") && s.contains("Components"), s.text)
      assertEquals(counts(s), Seq(0, 0, 0, 0), s.text)
    }

    test("one + on a hook counter and one on a Component counter") {
      val s = run {
        for
            h <- start
            _ <- h.key(Keyboard.Enter) // the focus starts on the + of Hooks Left
            _ <- Kyo.foreach(1 to 4)(_ => h.key(Keyboard.Tab))
            _ <- h.key(Keyboard.Enter) // the + of Components Left
            s <- h.screen
        yield s
      }
      assertEquals(counts(s), Seq(1, 0, 1, 0), s.text)
    }

    test("h hides the hook counters; shown again, they start at 0") {
      val (hidden, again) = run {
        for
            h      <- start
            _      <- h.key(Keyboard.Enter) // Hooks Left: 1
            _      <- Kyo.foreach(1 to 4)(_ => h.key(Keyboard.Tab))
            _      <- h.key(Keyboard.Enter) // Components Left: 1
            _      <- h.char('h')
            hidden <- h.screen
            _      <- h.char('h')
            again  <- h.screen
        yield (hidden, again)
      }
      assertEquals(counts(hidden), Seq(1, 0), hidden.text)
      assertEquals(counts(again), Seq(0, 0, 1, 0), again.text)
    }

    test("q ends the run") {
      val ended =
        run(start.map(h => h.char('q').andThen(h.awaitEnd(10.seconds))))
      assert(ended, "q did not end the run")
    }
end CounterStylesSuite
