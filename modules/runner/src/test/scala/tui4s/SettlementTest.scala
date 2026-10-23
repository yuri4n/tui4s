package tui4s

import kyo.test.*
import tui4s.internal.{RunOptions, Settlement}
import tui4s.spi.Settle

import java.time.Duration

/** Deadline and quiet-window checks with an explicit monotonic clock. */
// codex 2026-10-22: created: deterministic coverage of settlement starvation and budget reuse.
class SettlementTest extends Test[Any]:
    // codex 2026-10-22: created: nanosecond-scale inputs need no real timer or scheduler.
    private def quiescent(max: Long = 10L, confirm: Int = 2) =
      Settlement.policy(
        RunOptions.of(
          TuiOptions(settle =
            Settle.Quiescent(
              Duration.ofNanos(max),
              confirm,
            ),
          ),
        ),
      )

    // codex 2026-10-22: created: Quiescent completion still requires consecutive quiet observations.
    "Quiescent retains its scheduler probe and confirmation count" in {
      val p      = quiescent()
      val first  =
        p.begin(Settlement.Budget(p.maximum, 0L), 0L).observed(1L, true)
      val second = first.observed(2L, true)
      assert(p.probesScheduler)
      assert(p.decide(first, 1L, true) == Settlement.Decision.Continue)
      assert(p.decide(second, 2L, true) == Settlement.Decision.Complete)
    }

    // codex 2026-10-22: created: continuous arrivals cannot renew the frame's deadline.
    "every nonempty batch consumes the same maximum" in {
      val p       = quiescent()
      val initial = p.begin(Settlement.Budget(p.maximum, 0L), 0L)
      val states  =
        (2L to 10L by 2L).scanLeft(initial)((s, t) => s.observed(t, false)).tail
      assert(
        states.init.forall(s =>
          p.decide(s, s.lastActivity, false) == Settlement.Decision.Continue,
        ),
      )
      assert(p.decide(states.last, 10L, false) == Settlement.Decision.Timeout)
      assert(states.last.budget.remaining == 0L)
    }

    // codex 2026-10-22: created: traffic resets quiet observations without restoring spent time.
    "a new message restarts quiet confirmations without restoring the budget" in {
      val p       = quiescent(max = 20L)
      val first   =
        p.begin(Settlement.Budget(p.maximum, 0L), 0L).observed(3L, true)
      val message = first.observed(4L, false)
      val next    = message.observed(5L, true)
      assert(message.quiet == 0 && message.budget.remaining == 16L)
      assert(p.decide(next, 5L, true) == Settlement.Decision.Continue)
      assert(
        p.decide(
          next.observed(6L, true),
          6L,
          true,
        ) == Settlement.Decision.Complete,
      )
    }

    // codex 2026-10-22: created: verification and the recheck cannot each obtain a fresh maximum.
    "a recheck charges verification time to the first pass's remaining budget" in {
      val p          = quiescent(confirm = 1)
      val first      =
        p.begin(Settlement.Budget(p.maximum, 0L), 0L).observed(1L, true)
      assert(p.decide(first, 1L, true) == Settlement.Decision.Complete)
      val recheck    = p.begin(first.budget, 8L)
      assert(recheck.budget.remaining == 2L && recheck.quiet == 0)
      val finalBatch = recheck.observed(10L, false)
      assert(p.decide(finalBatch, 10L, false) == Settlement.Decision.Timeout)
    }

    // codex 2026-10-22: created: component waits pause the budget without extending active work.
    "a pending wait excludes only its duration and keeps prior active charges" in {
      val before  = Settlement.Budget(10L, 0L).spend(3L)
      val resumed = before.resume(1000L)
      assert(resumed.remaining == 7L)
      assert(resumed.spend(1004L).remaining == 3L)
      assert(resumed.spend(1007L).remaining == 0L)
    }

    // codex 2026-10-22: created: relative monotonic time handles negative values and signed wrap.
    "the active budget uses elapsed differences without absolute deadlines" in {
      assert(Settlement.Budget(10L, -100L).spend(-95L).remaining == 5L)
      assert(
        Settlement
          .Budget(10L, Long.MaxValue - 5L)
          .spend(Long.MinValue + 3L)
          .remaining == 1L,
      )
      assert(Settlement.Budget(10L, 0L).spend(100L).remaining == 0L)
    }

    // codex 2026-10-22: created: both policies follow the existing duration boundary rules.
    "negative durations and confirmation counts are checked once" in {
      val p       = quiescent(max = -1L, confirm = 0)
      assert(p.maximum == 0L && p.window == 0L && p.confirmations == 1)
      val initial = p.begin(Settlement.Budget(p.maximum, 0L), 0L)
      assert(p.expired(initial.budget))
      val huge    = Settlement.policy(
        RunOptions.of(
          TuiOptions(settle = Settle.Hops(0, Duration.ofSeconds(Long.MaxValue))),
        ),
      )
      assert(huge.window == Long.MaxValue)
      val hops    = Settlement.policy(
        RunOptions.of(
          TuiOptions(settle = Settle.Hops(-1, Duration.ofNanos(-1L))),
        ),
      )
      assert(hops.window == 0L && hops.confirmations == 1)
    }

    // codex 2026-10-22: created: an empty inbox does not bypass a busy scheduler's maximum.
    "Quiescent exhausts its budget even when the inbox is empty" in {
      val p     = quiescent()
      val state =
        p.begin(Settlement.Budget(p.maximum, 0L), 0L).observed(10L, false)
      assert(p.probesScheduler)
      assert(p.decide(state, 10L, true) == Settlement.Decision.Timeout)
    }

    // codex 2026-10-22: created: Hops stays an explicit quiet-window policy without a total maximum.
    "Hops keeps its hop count and quiet window when its elapsed counter saturates" in {
      val p       = Settlement.policy(
        RunOptions.of(TuiOptions(settle = Settle.Hops(2, Duration.ofNanos(3L)))),
      )
      val initial = Settlement.State(Settlement.Budget(0L, 0L), 0, 0L)
      val second  = initial.observed(0L, true).observed(1L, true)
      val third   = second.observed(2L, true)
      val window  = third.observed(3L, true)
      assert(!p.expired(initial.budget) && !p.probesScheduler)
      assert(p.decide(second, 1L, true) == Settlement.Decision.Continue)
      assert(p.decide(third, 2L, true) == Settlement.Decision.Continue)
      assert(p.decide(window, 3L, true) == Settlement.Decision.Complete)
      assert(
        p.decide(
          window.observed(5L, false),
          5L,
          false,
        ) == Settlement.Decision.Continue,
      )
    }
end SettlementTest
