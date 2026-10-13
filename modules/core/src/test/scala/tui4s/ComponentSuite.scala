package tui4s

import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** The helpers of the component suites (ComponentTest, HooksTest): waits on the
  * wall clock and counters that the effects of a test app move.
  */
// claude 2026-10-13: created (C4; the helpers of ComponentTest move here)
abstract class ComponentSuite extends RunnerSuite:

    /** Polls `cond` every 2 ms: the ms until it was true, or -1 after
      * `timeout`.
      */
    def waitFor(timeout: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Long < Async =
      now.map { t0 =>
        Loop.foreach {
          cond.map { ok =>
            now.map { t =>
              if ok then Loop.done((t - t0) / 1_000_000)
              else if t - t0 > timeout.toNanos then Loop.done(-1L)
              else Async.sleep(2.millis).andThen(Loop.continue)
            }
          }
        }
      }

    /** The value of `n` now. */
    def read(n: AtomicInteger)(using Frame): Int < Sync = Sync.defer(n.get())

    /** True when `n` does not move for 100 ms. */
    def stopped(n: AtomicInteger)(using Frame): Boolean < Async =
      for
          t1 <- read(n)
          _  <- Async.sleep(100.millis)
          t2 <- read(n)
      yield t1 == t2
end ComponentSuite
