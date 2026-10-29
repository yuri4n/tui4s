package tui4s

import kyo.test.*
import tui4s.internal.RunOptions
import tui4s.spi.Settle

import java.time.Duration

/** The options of a run are checked once (`RunOptions.of`, amendment A10 of the
  * purity plan): the edge values zero, negative, and a duration too long for a
  * `Long` count of nanoseconds.
  */
// claude 2026-10-19: created (A10)
class RunOptionsTest extends Test[Any]:
    private val huge = Duration.ofSeconds(Long.MaxValue)

    // codex 2026-10-23: changed: check the local default's active allowance.
    "the defaults pass as they are" in {
      val r = RunOptions.of(TuiOptions())
      assert(r.options == TuiOptions())
      assert(r.inboxCapacity == 1024 && r.pollMillis == 25)
      assert(r.pendingNanos == 2_000_000_000L)
      assert(r.settleNanos == 16_000_000L)
    }

    "inboxCapacity: zero and a negative value are 1" in {
      assert(RunOptions.of(TuiOptions(inboxCapacity = 0)).inboxCapacity == 1)
      assert(RunOptions.of(TuiOptions(inboxCapacity = -5)).inboxCapacity == 1)
      assert(RunOptions.of(TuiOptions(inboxCapacity = 7)).inboxCapacity == 7)
    }

    "pollInterval: below 1 ms is 1 ms; too long is Int.MaxValue ms" in {
      def ms(d: Duration): Int =
        RunOptions.of(TuiOptions(pollInterval = d)).pollMillis
      assert(ms(Duration.ZERO) == 1)
      assert(ms(Duration.ofNanos(500_000)) == 1)
      assert(ms(Duration.ofMillis(-5)) == 1)
      assert(ms(Duration.ofMillis(25)) == 25)
      assert(ms(Duration.ofDays(365L * 100)) == Int.MaxValue)
      assert(ms(huge) == Int.MaxValue)
    }

    "pendingTimeout: a negative one is 0; too long for nanoseconds is Long.MaxValue" in {
      def ns(d: Duration): Long =
        RunOptions.of(TuiOptions(pendingTimeout = d)).pendingNanos
      assert(ns(Duration.ZERO) == 0L)
      assert(ns(Duration.ofMillis(-1)) == 0L)
      assert(ns(Duration.ofMillis(50)) == 50_000_000L)
      assert(ns(Duration.ofNanos(Long.MaxValue)) == Long.MaxValue)
      assert(ns(huge) == Long.MaxValue)
    }

    // codex 2026-10-23: changed: include Local maximum validation.
    "settle: the finite maxima and the window of Hops, checked as pendingTimeout" in {
      def ns(s: Settle): Long =
        RunOptions.of(TuiOptions(settle = s)).settleNanos
      assert(ns(Settle.Quiescent(Duration.ofMillis(-3))) == 0L)
      assert(ns(Settle.Quiescent(huge)) == Long.MaxValue)
      assert(ns(Settle.Local(max = Duration.ofMillis(-3))) == 0L)
      assert(ns(Settle.Local(max = huge)) == Long.MaxValue)
      assert(ns(Settle.Hops(2, Duration.ZERO)) == 0L)
      assert(ns(Settle.Hops(2, Duration.ofNanos(250_000))) == 250_000L)
      assert(ns(Settle.Hops(2, huge)) == Long.MaxValue)
    }
end RunOptionsTest
