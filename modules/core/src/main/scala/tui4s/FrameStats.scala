package tui4s

/** Why a frame was made: the first frame, an input (a key or a resize), or a
  * change in the background (a signal, the repair tick).
  */
// claude 2026-10-12: changed: CanEqual (OS-16), Scaladoc
// claude 2026-10-13: changed: the Scaladoc names the keys (C3)
enum FrameCause derives CanEqual:
    case Initial, Input, Background

/** What one frame cost. `repainted`: cell writes into the kept buffer;
  * `flushed`: cells sent to the terminal; `nanos`: from the event to the end of
  * the flush; `updates`: region values applied.
  */
// claude 2026-10-12: changed: CanEqual (OS-16)
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
      * watcher: the verify pass and the repair tick (a lost wakeup). A
      * `Reactive` value with closures or nested signals never compares equal to
      * its last render, so each verify and each repair tick renders it again:
      * the frame then has `repaired > 0` and usually `flushed == 0`.
      */
    // claude 2026-10-12: created (K29, D-16); changed: the Reactive re-render
    // (review R2-5)
    repaired: Int = 0,
    /** Settles that ran out of their `max` while this frame was prepared (0 or
      * 1; 2 with the re-check). The frame can then miss a consequence of the
      * event: it comes in a later frame.
      */
    // claude 2026-10-12: created (D-17 c, RR O6; it replaces a stderr print)
    settleTimeouts: Int = 0,
) derives CanEqual
