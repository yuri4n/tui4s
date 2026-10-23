package tui4s.internal

import tui4s.spi.Settle

/** Pure settlement decisions. The runner supplies time and inbox observations. */
// codex 2026-10-22: created: quiet checks and a budget shared by a frame's passes.
private[tui4s] object Settlement:
    // codex 2026-10-22: created: distinguish quiet completion from an exhausted budget.
    enum Decision derives CanEqual:
        case Continue, Complete, Timeout

    /** Pending component time pauses this budget. Verification does not. */
    // codex 2026-10-22: created: a relative budget avoids absolute deadline overflow.
    final case class Budget(remaining: Long, checkedAt: Long):
        // codex 2026-10-22: created: charge elapsed active time, with saturation at zero.
        def spend(now: Long): Budget =
          Budget((remaining - (now - checkedAt).max(0L)).max(0L), now)

        // codex 2026-10-22: created: resume after an independently bounded pending wait.
        def resume(now: Long): Budget = copy(checkedAt = now)

    // codex 2026-10-22: created: the immutable state of one settlement pass.
    final case class State(budget: Budget, quiet: Int, lastActivity: Long):
        // codex 2026-10-22: created: new messages reset quiet observations, not the budget.
        def observed(now: Long, quietInbox: Boolean): State =
          copy(
            budget = budget.spend(now),
            quiet = if quietInbox then
                (quiet.toLong + 1L).min(Int.MaxValue.toLong).toInt
            else 0,
            lastActivity = if quietInbox then lastActivity else now,
          )
    end State

    /** Durations and counts are checked once for a run. */
    // codex 2026-10-22: created: retain the explicit scheduler and hop policies.
    final case class Policy(
        mode: Settle,
        maximum: Long,
        window: Long,
        confirmations: Int,
    ):
        // codex 2026-10-22: created: Quiescent reads global load; Hops does not.
        def probesScheduler: Boolean = mode match
            case Settle.Quiescent(_, _) => true
            case Settle.Hops(_, _)      => false

        // codex 2026-10-22: created: Hops keeps its documented quiet-window semantics.
        def expired(budget: Budget): Boolean = mode match
            case Settle.Hops(_, _)      => false
            case Settle.Quiescent(_, _) => budget.remaining == 0L

        // codex 2026-10-22: created: every batch, including a nonempty one, checks the budget.
        def decide(state: State, now: Long, empty: Boolean): Decision =
          if expired(state.budget) then Decision.Timeout
          else if empty && state.quiet >= confirmations && now - state.lastActivity >= window
          then Decision.Complete
          else Decision.Continue

        // codex 2026-10-22: created: reset quiet observations without restoring spent time.
        def begin(budget: Budget, now: Long): State =
          State(budget.spend(now), 0, now)
    end Policy

    // codex 2026-10-22: created: normalize public tuning values without changing RunOptions' constructor.
    def policy(options: RunOptions): Policy = options.options.settle match
        case mode @ Settle.Quiescent(_, confirm) =>
          Policy(mode, options.settleNanos, 0L, confirm.max(1))
        case mode @ Settle.Hops(hops, _)         =>
          Policy(
            mode,
            Long.MaxValue,
            options.settleNanos,
            (hops.toLong.max(0L) + 1L).min(Int.MaxValue.toLong).toInt,
          )
end Settlement
