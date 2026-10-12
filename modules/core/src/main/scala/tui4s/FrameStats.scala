package tui4s

// claude 2026-10-12: created (carried from fe06611:modules/core/src/main/scala/tui4s/Tui.scala lines 27-50; new file and package only)

enum FrameCause:
    case Initial, Input, Background

/** What one frame cost. `repainted`: cell writes into the kept buffer; `flushed`: cells sent to the terminal; `nanos`: from
  * the event to the end of the flush; `updates`: region values applied.
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
    // claude 2026-10-03: created the two fields below for the step 6 measurements
    /** `System.nanoTime` at the end of the flush (so a caller can measure from the moment it sent the event). */
    endNanos: Long = 0,
    /** Subtrees painted in this frame (paint roots after nested roots are merged). */
    subtrees: Int = 0
)
