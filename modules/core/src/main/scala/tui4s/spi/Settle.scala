package tui4s.spi

// claude 2026-10-09: created (carried from .work/proto/kyoui-runner/src/tui4s/kyoui/Tui.scala lines 28-42; new path and package only)

import kyo.*

/** How the runner knows that an event's consequences have all arrived before it flushes a frame. */
enum Settle:
    /** Until Kyo's scheduler has no queued or running task besides the runner itself (bounded by `max`). Reads the
      * scheduler's load; the most reliable choice with kyo's current `Signal` API.
      *
      * `confirm`: how many quiescent observations in a row, one scheduler hop apart, each followed by an empty inbox.
      */
    // claude 2026-10-03: changed: added `confirm` (default 2). The probe's scan is not atomic and a woken task is
    // invisible for a few instructions between `queue.poll()` and `runTask`; a second observation one hop later makes a
    // false "quiescent" much less likely (it cannot make it impossible; see REPORT.md).
    case Quiescent(max: Duration, confirm: Int = 2)

    /** Until `hops` scheduler round trips in a row bring nothing AND `window` has passed since the last news. */
    case Hops(hops: Int, window: Duration)
end Settle
