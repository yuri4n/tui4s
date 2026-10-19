package tui4s.use

import kyo.*

/** The state of the work of [[useAsync]]. */
// claude 2026-10-19: created (L2a)
enum AsyncState[+E, +A] derives CanEqual:
    /** There is no input, so no work runs. */
    case Idle

    /** The work for the current input runs. */
    case Loading

    /** The work for the current input gave `value`. */
    case Done(value: A)

    /** The work for the current input failed: a typed failure of `E` or a
      * panic.
      */
    case Failed(error: Result.Error[E])
end AsyncState

/** The state of the work `f` for the current value of `input`: [[AsyncState]].
  * Each new present input starts `f` again and interrupts the work for the
  * input before it, so an old answer never replaces a newer one (the newest
  * input wins, through `Signal.observe`). An absent input is
  * [[AsyncState.Idle]] and interrupts the work. The work runs in fibers that
  * end when the caller's `Scope` closes.
  *
  * {{{
  * for
  *     query   <- Signal.initRef("")
  *     results <- useAsync(query.map(q => if q.isEmpty then Absent else Present(q)))(search)
  * yield div(input.value(query), results.map {
  *   case AsyncState.Done(rows) => ul(rows.map(li(_))*)
  *   case AsyncState.Loading    => span("searching...")
  *   case AsyncState.Failed(e)  => span(s"failed: $e")
  *   case AsyncState.Idle       => span("type to search")
  * })
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useAsync[I, E, A](
    input: Signal[Maybe[I]],
)(f: I => A < (Async & Abort[E]))(using
    ConcreteTag[E],
    Frame,
): Signal[AsyncState[E, A]] < (Sync & Scope) =
  for
      first <- input.current
      state <- Signal.initRef[AsyncState[E, A]](
                 if first.isEmpty then AsyncState.Idle else AsyncState.Loading,
               )
      _     <- Fiber.init(input.observe {
                 case Absent     => state.set(AsyncState.Idle)
                 case Present(i) =>
                   state
                     .set(AsyncState.Loading)
                     .andThen(
                       Fiber.init(
                         Abort.run[E](f(i)).map(r => state.set(AsyncState.of(r))),
                       ),
                     )
                     .unit
               })
  yield state

/** Makes an [[AsyncState]] from a result. */
// claude 2026-10-19: created (L2a); changed: Scaladoc (L2a review)
object AsyncState:
    /** The state of a finished work.
      *
      * {{{
      * AsyncState.of(Result.succeed(rows)) // Done(rows)
      * }}}
      */
    def of[E, A](r: Result[E, A]): AsyncState[E, A] = r match
        case Result.Success(a) => Done(a)
        case Result.Failure(e) => Failed(Result.Failure(e))
        case Result.Panic(t)   => Failed(Result.Panic(t))
end AsyncState

/** How far a stream of [[useStreamFold]] or [[useStreamText]] is. */
// claude 2026-10-19: created (L2a review)
enum StreamStatus derives CanEqual:
    /** The stream runs: more values can come. */
    case Running

    /** The stream ended: the value is final. */
    case Done

    /** The stream failed with `error` (a panic: the stream has no typed
      * failure). The value keeps the values before the failure.
      */
    case Failed(error: Throwable)
end StreamStatus

/** The value that a stream folds into, and the status of the stream. */
// claude 2026-10-19: created (L2a review)
final case class StreamState[B](value: Signal[B], status: Signal[StreamStatus])

/** The fold of the values of `stream` into a signal, from `zero`, and the
  * status of the stream ([[StreamStatus]]: Running, Done or Failed). Each chunk
  * of the stream is one write of the value. A failure of the stream goes to
  * Kyo's `Log` and to the status. The stream runs in a fiber that ends when the
  * caller's `Scope` closes; the status then stays Running.
  *
  * {{{
  * for count <- useStreamFold(events, 0)((n, _) => n + 1)
  * yield span(count.value.map(n => s"$n events"))
  * }}}
  */
// claude 2026-10-19: created (L2a); changed: the status of the stream (L2a
// review)
def useStreamFold[A, B](stream: Stream[A, Async], zero: B)(f: (B, A) => B)(using
    Tag[Emit[Chunk[A]]],
    Frame,
): StreamState[B] < (Sync & Scope) =
  for
      value  <- Signal.initRef(zero)
      status <- Signal.initRef[StreamStatus](StreamStatus.Running)
      _      <- Fiber.init(
                  Abort
                    .run[Throwable](
                      stream.foreachChunk(c =>
                        value.updateAndGet(b => c.foldLeft(b)(f)).unit,
                      ),
                    )
                    .map {
                      case Result.Success(_) => status.set(StreamStatus.Done)
                      case Result.Failure(t) => failed(status, t)
                      case Result.Panic(t)   => failed(status, t)
                    },
                )
  yield StreamState(value, status)

/** Logs the failure `t` of a stream and sets the status. */
// claude 2026-10-19: created (L2a review)
private def failed(status: SignalRef[StreamStatus], t: Throwable)(using
    Frame,
): Unit < Sync =
  Log
    .warn(s"a stream of useStreamFold failed: $t")
    .andThen(status.set(StreamStatus.Failed(t)))

/** The text of `stream`: its pieces, joined in order (for example the tokens of
  * a chat answer), and the status of the stream. The stream runs in a fiber
  * that ends when the caller's `Scope` closes.
  *
  * {{{
  * for answer <- useStreamText(tokens)
  * yield div(
  *   p(answer.value),
  *   span(answer.status.map(s => if s == StreamStatus.Running then "..." else "")),
  * )
  * }}}
  */
// claude 2026-10-19: created (L2a); changed: the status (L2a review)
def useStreamText(stream: Stream[String, Async])(using
    Frame,
): StreamState[String] < (Sync & Scope) =
  useStreamFold(stream, "")(_ + _)
