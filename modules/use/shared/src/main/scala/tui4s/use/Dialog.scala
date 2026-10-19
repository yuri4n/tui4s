package tui4s.use

import kyo.*

/** A modal dialog as a state machine: [[open]] shows a request and waits for
  * its answer; [[close]] gives the answer. Make it with [[useDialog]].
  *
  * At most one request is open: an `open` while another one is open waits for
  * its turn (first come, first served). A `close` with no open request does
  * nothing. In tui4s a key handler runs on the runner, one at a time, so a
  * handler must not wait for an answer (the key of the answer would never
  * come): it starts the flow with [[launch]].
  */
// claude 2026-10-19: created (L2a)
final class Dialog[Q, A] private[use] (
    queue: SignalRef[Chunk[Dialog.Entry[Q, A]]],
    flows: Channel[() => Unit < Async],
)(using Frame):
    import Dialog.*

    /** The open request, for the UI to show; Absent when no dialog is open. */
    val request: Signal[Maybe[Q]] =
      queue.map(q => if q.isEmpty then Absent else Present(q.head.request))

    /** Shows `q` (when the requests before it are closed) and waits for its
      * answer. An interrupt of the wait removes the request.
      */
    def open(q: Q): A < Async =
      Promise
        .init[A, Any]
        .map(p =>
          queue
            .updateAndGet(_ :+ Entry(q, p))
            .andThen(
              Sync.ensure(queue.updateAndGet(_.filterNot(_.answer == p)).unit)(
                p.get,
              ),
            ),
        )

    /** Answers the open request and closes it; nothing happens when no request
      * is open.
      */
    def close(answer: A): Unit < Sync =
      queue
        .getAndUpdate(_.drop(1))
        .map(q =>
          if q.isEmpty then Kyo.unit
          else q.head.answer.completeDiscard(Result.succeed(answer)),
        )

    /** Starts `flow` (a flow that opens the dialog) on the fiber of the dialog,
      * after the flows before it, and returns at once: a key handler calls it.
      * It never waits. When 16 flows wait already, or when the `Scope` of
      * [[useDialog]] has closed, it drops `flow` and logs a warning (Kyo's
      * `Log`). A flow that fails is logged and the next flow runs. The flows
      * end when the `Scope` of [[useDialog]] closes.
      */
    // claude 2026-10-19: changed: an offer that never waits, so a key handler
    // never stops the runner (L2a review)
    def launch(flow: => Any < Async): Unit < Sync =
      Abort.run[Closed](flows.offer(() => flow.unit)).map {
        case Result.Success(true) => Kyo.unit
        case _                    =>
          Log.warn(
            "a dialog flow was dropped: 16 flows wait, or the dialog ended",
          )
      }
end Dialog

/** The pure state of [[Dialog]]. */
// claude 2026-10-19: created (L2a)
private[use] object Dialog:
    /** A request and the promise of its answer. */
    final case class Entry[Q, A](request: Q, answer: Promise[A, Any])

/** A dialog: its open request as a signal, `open` (wait for an answer), `close`
  * (give the answer) and `launch` (start a flow from a key handler). The fiber
  * that runs the flows ends when the caller's `Scope` closes.
  *
  * {{{
  * for
  *     confirm <- useDialog[String, Boolean]
  * yield div.onKeyDown {
  *   case KeyboardEvent(Keyboard.Char('d'), _, _) =>
  *     confirm.launch(confirm.open("Delete the item?").map(yes => if yes then delete else Kyo.unit))
  *   case _ => Kyo.unit
  * }(
  *   list,
  *   when(confirm.request.map(_.nonEmpty))(
  *     div.focusTrap(true)(
  *       span(confirm.request.map(_.getOrElse(""))),
  *       button("Yes").focusAuto(true).onClick(confirm.close(true)),
  *       button("No").onClick(confirm.close(false)),
  *     ),
  *   ),
  * )
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useDialog[Q, A](using Frame): Dialog[Q, A] < (Sync & Scope) =
  for
      queue <- Signal.initRef(Chunk.empty[Dialog.Entry[Q, A]])
      flows <- Channel.init[() => Unit < Async](16)
      _     <- Fiber.init(
                 Loop.forever(
                   flows.take.map(flow =>
                     Abort.run[Throwable](flow()).map {
                       case Result.Success(_) => Kyo.unit
                       case failed            => Log.warn(s"a dialog flow failed: $failed")
                     },
                   ),
                 ),
               )
  yield Dialog(queue, flows)
