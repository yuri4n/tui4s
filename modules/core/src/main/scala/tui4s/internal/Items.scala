package tui4s.internal

import kyo.*
import tui4s.*

/** What the inbox carries to the render fiber (SPEC 5.2). Input events need no
  * wrapper: a type test separates them from the rest.
  */
private[tui4s] type Item = Event | Fold | Done | Probe | Failed | Stop.type

/** A value from the source or task with the generation `token`, or a function
  * `S => Next[S]` (token [[Fold.Direct]]: `TuiHandle.update`, `TuiTest.fold`).
  */
private[tui4s] final class Fold(val token: Long, val value: Any)

private[tui4s] object Fold:
    /** The token of a change from outside the app; never stale. */
    val Direct: Long = 0L

/** The source or task with the generation `token` ended. */
private[tui4s] final class Done(
    val token: Long,
    val result: Result[Throwable, Any],
)

/** A request for a notice from the render fiber (the live kit's `settle`). It
  * completes `promise` after the first draw decision at which more than `after`
  * batches of the app were folded (`Stats.batches`): with -1, the decision of
  * the batch that holds it (everything put before it is folded and drawn); with
  * the count that the caller last saw, the decision after the next news from
  * the app. A probe whose promise is already complete is dropped.
  */
private[tui4s] final class Probe(
    val promise: Promise.Unsafe[Stats, Any],
    val after: Long,
)

/** The input thread failed. */
private[tui4s] final class Failed(val error: TuiError)

/** `TuiHandle.stop`: like `Quit`. */
private[tui4s] case object Stop
