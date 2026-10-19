package tui4s.spi

import java.time.Duration

/** How the runner knows that an event's consequences have all arrived before it
  * flushes a frame. A tuning knob: the default of [[tui4s.TuiOptions.settle]]
  * is `Quiescent(100 ms, confirm = 2)`.
  */
// claude 2026-10-12: changed: java.time.Duration (Kyo-free, D-14 a), CanEqual
enum Settle derives CanEqual:
    /** Until Kyo's scheduler has no queued or running task besides the runner
      * itself (bounded by `max`). Reads the scheduler's load through the public
      * `Scheduler.status()`; the most reliable choice with kyo's current
      * `Signal` API.
      *
      * `confirm`: how many quiescent observations in a row, one scheduler hop
      * apart, each followed by an empty inbox. Why 2: the probe's scan is not
      * atomic and a woken task is invisible for a few instructions between
      * `queue.poll()` and `runTask`; a second observation one hop later makes a
      * false "quiescent" much less likely (it cannot make it impossible).
      */
    case Quiescent(max: Duration, confirm: Int = 2)

    /** Until `hops` scheduler round trips in a row bring nothing AND `window`
      * has passed since the last news. An escape hatch that reads nothing of
      * the scheduler.
      */
    case Hops(hops: Int, window: Duration)
end Settle
