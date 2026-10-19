package tui4s.internal

import tui4s.TuiOptions
import tui4s.spi.Settle

import java.time.Duration

/** The [[tui4s.TuiOptions]] of one run, checked once when the run starts
  * (`Tui.run`, `Headless.start`). The runner, the session and the input thread
  * read the values here, never the raw options. Each value has the range that
  * its user needs, so no other place clamps an option.
  *
  * @param options
  *   the options as the user gave them (for the values that need no check)
  * @param inboxCapacity
  *   `TuiOptions.inboxCapacity`, at least 1
  * @param pollMillis
  *   `TuiOptions.pollInterval` in milliseconds, from 1 to `Int.MaxValue`: an
  *   interval below 1 ms (also zero or a negative one) is 1 ms, so the input
  *   thread never spins on a poll that returns at once
  * @param pendingNanos
  *   `TuiOptions.pendingTimeout` in nanoseconds, from 0 to `Long.MaxValue`
  * @param settleNanos
  *   the duration of `TuiOptions.settle` in nanoseconds (the `max` of
  *   `Quiescent`, the `window` of `Hops`), from 0 to `Long.MaxValue`
  */
// claude 2026-10-19: created: TuiOptions validated once (amendment A10 of the
// purity plan); it replaces the clamps in Tui, Headless, Session and Runner
private[tui4s] final case class RunOptions(
    options: TuiOptions,
    inboxCapacity: Int,
    pollMillis: Int,
    pendingNanos: Long,
    settleNanos: Long,
)

private[tui4s] object RunOptions:
    /** The checked values of `options`. */
    def of(options: TuiOptions): RunOptions =
      RunOptions(
        options,
        inboxCapacity = options.inboxCapacity.max(1),
        pollMillis = millis(options.pollInterval),
        pendingNanos = nanos(options.pendingTimeout),
        settleNanos = nanos(options.settle match
            case Settle.Quiescent(max, _) => max
            case Settle.Hops(_, window)   => window),
      )

    /** `d` in nanoseconds: a negative duration is 0, and a duration too long
      * for a `Long` count of nanoseconds is `Long.MaxValue`.
      */
    def nanos(d: Duration): Long =
      if d.isNegative then 0L
      else if d.compareTo(Duration.ofNanos(Long.MaxValue)) >= 0 then
          Long.MaxValue
      else d.toNanos

    /** `d` in milliseconds, from 1 to `Int.MaxValue`. */
    def millis(d: Duration): Int =
      if d.compareTo(Duration.ofMillis(1)) < 0 then 1
      else if d.compareTo(Duration.ofMillis(Int.MaxValue)) >= 0 then
          Int.MaxValue
      else d.toMillis.toInt
end RunOptions
