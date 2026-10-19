package examples.composables

import kyo.*
import munit.FunSuite

/** The five native recipes of the README section "You may not need a
  * composable" do what their Scaladoc says.
  */
// claude 2026-10-19: created (L2a)
class NativeSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    /** Waits until `p` holds for `s` (a read every 5 ms, at most 10 s). */
    private def await[A](s: Signal[A])(p: A => Boolean)(using Frame) =
      Loop(0) { i =>
        s.current.map(v =>
          if p(v) || i > 2000 then Loop.done(p(v))
          else Async.sleep(5.millis).andThen(Loop.continue(i + 1)),
        )
      }

    test("toggle, increment with a max, and a derived label") {
      val (on, n, text) = run {
        for
            on    <- Signal.initRef(false)
            count <- Signal.initRef(0)
            _     <- Native.toggle(on)
            _     <- Kyo.foreach(1 to 5)(_ => Native.increment(count, 3))
            v     <- on.get
            n     <- count.get
            text  <- Native.label(count).current
        yield (v, n, text)
      }
      assert(on && n == 3 && text == "3 items")
    }

    test("every runs its action until the Scope closes") {
      val ticked = run {
        for
            n  <- Signal.initRef(0)
            _  <- Native.every(5.millis)(n.updateAndGet(_ + 1))
            ok <- await(n)(_ >= 3)
        yield ok
      }
      assert(ticked)
    }

    test("timer: the ticks stop while paused and go on after") {
      val (ran, stopped, again) = run {
        for
            paused <- Signal.initRef(false)
            ticks  <- Signal.initRef(0)
            _      <- Native.timer(paused, ticks, 5.millis)
            ran    <- await(ticks)(_ >= 2)
            _      <- paused.set(true)
            _      <- Async.sleep(50.millis)
            a      <- ticks.get
            _      <- Async.sleep(50.millis)
            b      <- ticks.get
            _      <- paused.set(false)
            again  <- await(ticks)(_ > b)
        yield (ran, a == b, again)
      }
      assert(ran && stopped && again)
    }
end NativeSuite
