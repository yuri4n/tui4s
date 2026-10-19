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

// claude 2026-10-19: created (L2a)
object AsyncState:
    /** The state of a finished work. */
    def of[E, A](r: Result[E, A]): AsyncState[E, A] = r match
        case Result.Success(a) => Done(a)
        case Result.Failure(e) => Failed(Result.Failure(e))
        case Result.Panic(t)   => Failed(Result.Panic(t))

/** A signal that folds the values of `stream` into a state, from `zero`. The
  * stream runs in a fiber that ends when the caller's `Scope` closes; each
  * chunk of the stream is one write of the signal.
  *
  * {{{
  * for count <- useStreamFold(events, 0)((n, _) => n + 1)
  * yield span(count.map(n => s"$n events"))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useStreamFold[A, B](stream: Stream[A, Async], zero: B)(f: (B, A) => B)(using
    Tag[Emit[Chunk[A]]],
    Frame,
): Signal[B] < (Sync & Scope) =
  for
      state <- Signal.initRef(zero)
      _     <- Fiber.init(
                 stream.foreachChunk(c => state.updateAndGet(b => c.foldLeft(b)(f))),
               )
  yield state

/** A signal of the text of `stream`: its pieces, joined in order (for example
  * the tokens of a chat answer). The stream runs in a fiber that ends when the
  * caller's `Scope` closes.
  *
  * {{{
  * for answer <- useStreamText(tokens)
  * yield p(answer)
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useStreamText(stream: Stream[String, Async])(using
    Frame,
): Signal[String] < (Sync & Scope) =
  useStreamFold(stream, "")(_ + _)
