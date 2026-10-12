package tui4s

enum FrameCause:
    case Initial, Input, Background

/** What one frame cost. `repainted`: cell writes into the kept buffer;
  * `flushed`: cells sent to the terminal; `nanos`: from the event to the end of
  * the flush; `updates`: region values applied.
  */
final case class FrameStats(
    seq: Long,
    cause: FrameCause,
    repainted: Int,
    flushed: Int,
    nanos: Long,
    updates: Int,
    handleNanos: Long = 0,
    settleNanos: Long = 0,
    renderNanos: Long = 0,
    /** The focused element: its `id`, or its tree position when it has none. */
    focus: String = "",
    /** `System.nanoTime` at the end of the flush (so a caller can measure from
      * the moment it sent the event).
      */
    endNanos: Long = 0,
    /** Subtrees painted in this frame (paint roots after nested roots are
      * merged).
      */
    subtrees: Int = 0,
    /** Values that the runner found by a re-read of a signal, not from a
      * watcher: the verify pass and the repair tick (a lost wakeup).
      */
    // claude 2026-10-12: created (K29, D-16)
    repaired: Int = 0,
)
