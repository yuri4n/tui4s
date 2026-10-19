package tui4s.internal

import kyo.Stylesheet
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
  * @param vars
  *   the custom properties of the stylesheet of the run
  * @param sheetProblems
  *   the entries and the selectors of that stylesheet that the runner ignores
  */
// claude 2026-10-19: changed: the custom properties of the stylesheet, and
// its problems (L1b)
private[tui4s] final case class RunOptions(
    options: TuiOptions,
    inboxCapacity: Int,
    pollMillis: Int,
    pendingNanos: Long,
    settleNanos: Long,
    vars: CssVars = CssVars.empty,
    sheetProblems: Vector[String] = Vector.empty,
)

private[tui4s] object RunOptions:
    /** The checked values of `options`, and the custom properties of `sheet`
      * with the entries of it that the runner ignores ([[CssVars.of]]).
      */
    // claude 2026-10-19: changed: the stylesheet (L1b)
    def of(
        options: TuiOptions,
        sheet: Stylesheet = Stylesheet.empty,
    ): RunOptions =
        val (vars, problems) = CssVars.of(sheet)
        RunOptions(
          options,
          inboxCapacity = options.inboxCapacity.max(1),
          pollMillis = millis(options.pollInterval),
          pendingNanos = nanos(options.pendingTimeout),
          settleNanos = nanos(options.settle match
              case Settle.Quiescent(max, _) => max
              case Settle.Hops(_, window)   => window),
          vars = vars,
          sheetProblems = problems,
        )
    end of

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
