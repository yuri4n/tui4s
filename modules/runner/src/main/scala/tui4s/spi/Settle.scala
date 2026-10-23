package tui4s.spi

import java.time.Duration

/** How the runner batches an event's consequences before it flushes a frame.
  * The default of [[tui4s.TuiOptions.settle]] is
  * `Quiescent(100 ms, confirm = 2)`. These are soft batching rules, not a
  * barrier for all asynchronous work in an application.
  */
// claude 2026-10-12: changed: java.time.Duration (Kyo-free, D-14 a), CanEqual; codex 2026-10-22: changed: describe the soft batching contract.
enum Settle derives CanEqual:
    /** Until Kyo's scheduler has no queued or running task besides the runner
      * itself (bounded by `max`). Reads the scheduler's load through the public
      * `Scheduler.status()`. Unrelated runnable tasks can keep this policy
      * waiting until `max` expires. The active budget is shared by the frame's
      * settlement passes; pending component waits have a separate allowance.
      * The budget is checked between bounded inbox batches; it cannot interrupt
      * user code or a scheduler pause.
      *
      * `confirm`: how many quiescent observations in a row, one scheduler hop
      * apart, each followed by an empty inbox. Why 2: the probe's scan is not
      * atomic and a woken task is invisible for a few instructions between
      * `queue.poll()` and `runTask`; a second observation one hop later makes a
      * false "quiescent" much less likely (it cannot make it impossible).
      */
    // codex 2026-10-22: changed: document the shared allowance and global-load tradeoff.
    case Quiescent(max: Duration, confirm: Int = 2)

    /** Until `hops` scheduler round trips in a row bring nothing AND `window`
      * has passed since the last news. An escape hatch that reads nothing of
      * the scheduler.
      */
    case Hops(hops: Int, window: Duration)
end Settle
