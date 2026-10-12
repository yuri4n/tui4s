package tui4s.spi

import kyo.*

/** How the runner knows that an event's consequences have all arrived before it
  * flushes a frame.
  */
enum Settle:
    /** Until Kyo's scheduler has no queued or running task besides the runner
      * itself (bounded by `max`). Reads the scheduler's load; the most reliable
      * choice with kyo's current `Signal` API.
      *
      * `confirm`: how many quiescent observations in a row, one scheduler hop
      * apart, each followed by an empty inbox.
      */
    /* Why `confirm` (default 2): the probe's scan is not atomic and a woken
     * task is invisible for a few instructions */
    /* between `queue.poll()` and `runTask`; a second observation one hop later
     * makes a false "quiescent" much less */
    // likely (it cannot make it impossible).
    case Quiescent(max: Duration, confirm: Int = 2)

    /** Until `hops` scheduler round trips in a row bring nothing AND `window`
      * has passed since the last news.
      */
    case Hops(hops: Int, window: Duration)
end Settle
