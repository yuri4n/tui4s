package tui4s.internal

import kyo.*
import tui4s.*

/** Inbox items (5.2). Input events need no wrapper (a type test separates them). */
private[tui4s] type Item = Event | Fold | Done | Probe | Failed | Stop.type

/** A value from a source/task, or a function (Dispatch, TuiHandle). */
private[tui4s] final class Fold(val token: Long, val value: Any)
private[tui4s] object Fold:
  /** TuiHandle.update and LiveTest folds: always live, the value is a function `S => Next[S]`. */
  val HandleToken: Long = 0L

private[tui4s] final class Done(val token: Long, val result: Result[Throwable, Any])

/** settle / stats: completed after the batch's draw decision. */
private[tui4s] final class Probe(val promise: Promise.Unsafe[Stats, Any])

/** The input thread failed. */
private[tui4s] final class Failed(val error: TuiError)

/** TuiHandle.stop: like Quit. */
private[tui4s] case object Stop

private[tui4s] enum Step derives CanEqual:
  case Quit, Same, WorkOnly, Changed, Failed
