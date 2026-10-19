package tui4s.use

import kyo.*

/** Undo and redo over a `SignalRef`. Make it with [[useHistory]].
  *
  * Write the value through the history ([[set]], [[update]]): such a write is
  * one step of the history. A write to the ref that does not go through the
  * history is not a step, and the next step of the history replaces it. A write
  * of the value that the ref holds is not a step.
  */
// claude 2026-10-19: created (L2a)
final class History[A] private[use] (
    ref: SignalRef[A],
    line: SignalRef[History.Timeline[A]],
    limit: Int,
)(using Frame):

    /** True when [[undo]] has a value to restore. */
    val canUndo: Signal[Boolean] = line.map(_.past.nonEmpty)

    /** True when [[redo]] has a value to restore. */
    val canRedo: Signal[Boolean] = line.map(_.future.nonEmpty)

    /** Writes `a` as a new step; it clears the values that redo restores. */
    def set(a: A): Unit < Sync = change(_.write(a, limit))

    /** Writes `f` of the present value as a new step. */
    def update(f: A => A): Unit < Sync =
      change(t => t.write(f(t.present), limit))

    /** Restores the value before the last step; nothing when there is none. */
    def undo: Unit < Sync = change(_.undo)

    /** Restores the value that the last undo replaced; nothing when there is
      * none.
      */
    def redo: Unit < Sync = change(_.redo)

    private def change(op: History.Timeline[A] => History.Timeline[A]): Unit <
      Sync =
      line.updateAndGet(op).map(mirror)

    // The ref shows the present value of the last change: a change that lands
    // between the update and the write of the ref is written again.
    private def mirror(t: History.Timeline[A]): Unit < Sync =
      ref
        .set(t.present)
        .andThen(
          line.get.map(now => if now eq t then Kyo.unit else mirror(now)),
        )
end History

/** The pure timeline of [[History]]. */
// claude 2026-10-19: created (L2a)
private[use] object History:
    /** The values before the present one (the oldest first), the present value,
      * and the values that redo restores (the next one first).
      */
    final case class Timeline[A](past: Chunk[A], present: A, future: Chunk[A]):

        /** A step to `a`: the past keeps at most `limit` values. */
        def write(a: A, limit: Int): Timeline[A] =
          if a == present then this
          else
              Timeline(
                (past :+ present).takeRight(limit.max(0)),
                a,
                Chunk.empty,
              )

        /** One step back. */
        def undo: Timeline[A] =
          if past.isEmpty then this
          else Timeline(past.dropRight(1), past.last, present +: future)

        /** One step forward. */
        def redo: Timeline[A] =
          if future.isEmpty then this
          else Timeline(past :+ present, future.head, future.drop(1))
    end Timeline

    def start[A](a: A): Timeline[A] = Timeline(Chunk.empty, a, Chunk.empty)
end History

/** Undo and redo over `ref`: the history keeps at most `limit` values before
  * the present one. Write the value through the history, so each write is one
  * step; a write to `ref` that bypasses the history is not a step. It has no
  * fiber.
  *
  * The laws: an undo after a write restores the value before it; a redo after
  * an undo restores the value that the undo replaced; a write clears the redo
  * values; the past keeps at most `limit` values.
  *
  * {{{
  * for
  *     items   <- Signal.initRef(Chunk.empty[String])
  *     history <- useHistory(items, 100)
  * yield div.onKeyDown {
  *   case KeyboardEvent(Keyboard.Char('z'), m, _) if m.ctrl => history.undo
  *   case KeyboardEvent(Keyboard.Char('y'), m, _) if m.ctrl => history.redo
  *   case _                                                 => Kyo.unit
  * }(button("Add").onClick(history.update(_ :+ "new")))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useHistory[A](ref: SignalRef[A], limit: Int)(using
    CanEqual[A, A],
    Frame,
): History[A] < Sync =
  ref.get.map(a =>
    Signal.initRef(History.start(a)).map(line => History(ref, line, limit)),
  )
