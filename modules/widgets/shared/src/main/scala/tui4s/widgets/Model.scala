package tui4s.widgets

import kyo.*

/** Who owns the value of a widget (its selected tab, its text, its checked
  * items). A widget takes a `Model` and a change callback (`onChange`), as a
  * Vue component takes a `v-model`:
  *   - [[Model.Local]]: the widget owns the value. It makes a `SignalRef` that
  *     starts with `initial`, writes it on each change, then calls `onChange`.
  *   - [[Model.Bound]]: the parent owns a `SignalRef`. The widget writes it,
  *     then calls `onChange`, as a native `.value(ref)` binding does.
  *   - [[Model.Controlled]]: the parent owns a read-only `Signal`. The widget
  *     never writes it: it calls `onChange` with the request, and the parent
  *     accepts it (it writes the source of the signal) or rejects it (it does
  *     nothing). The signal stays authoritative.
  *
  * kyo-ui's own `UI.Bound` (`Const` or `Ref`) says only where an input reads
  * and writes its value. `Model` adds the two owners that it does not have:
  * `Local`, where the widget makes the ref itself, and `Controlled`, where the
  * widget never writes and `onChange` is a request that the parent can
  * reject. `Model.Bound` has the meaning of `UI.Bound.Ref`.
  *
  * A change to the value that the widget shows already runs nothing: no write
  * and no `onChange`. A widget that edits text (a search box, a date field)
  * takes a [[Model.Writable]], because a native text input needs a `SignalRef`
  * to edit.
  *
  * {{{
  * Tabs("CPU", "Memory")(selected = Model.Local(1))         // owned
  * Tabs("CPU", "Memory")(selected = Model.Bound(tabRef))    // the parent's ref
  * Tabs("CPU", "Memory")(selected = Model.Controlled(sig), onChange = ask)
  * }}}
  */
// claude 2026-10-19: created (L2b): the ownership of a widget value;
// changed (L2b review): its relation to kyo-ui's UI.Bound
sealed trait Model[A]

/** The three owners of a value, and the resolution that a widget uses. */
// claude 2026-10-19: created (L2b)
object Model:
    /** A model that the widget can write: [[Local]] or [[Bound]]. */
    sealed trait Writable[A] extends Model[A]

    /** The widget owns the value; it starts with `initial`. */
    final case class Local[A](initial: A) extends Writable[A]

    /** The parent owns `ref`; the widget writes it before `onChange`. */
    final case class Bound[A](ref: SignalRef[A]) extends Writable[A]

    /** The parent owns `value`; the widget only asks with `onChange`. */
    final case class Controlled[A](value: Signal[A]) extends Model[A]

    /** The value that a widget shows, and the change that it makes. */
    private[widgets] final class Open[A](
        val value: Signal[A],
        write: A => Unit < Sync,
        onChange: A => Any < Async,
    ):
        /** Writes `next` (not for a controlled model), then calls `onChange`;
          * nothing when `next` is the value that shows.
          */
        def change(next: A)(using Frame): Unit < Async =
          value.current.map(now =>
            if now == next then Kyo.unit
            else write(next).andThen(onChange(next)).unit,
          )
    end Open

    /** The value of `m` for a widget: a new `SignalRef` for [[Local]]. */
    private[widgets] def open[A](m: Model[A], onChange: A => Any < Async)(using
        Frame,
    ): Open[A] < Sync =
      m match
          case Local(a)      => Signal.initRef(a).map(r => Open(r, r.set, onChange))
          case Bound(r)      => Open(r, r.set, onChange)
          case Controlled(s) => Open(s, _ => Kyo.unit, onChange)

    /** The `SignalRef` of a writable model: a new one for [[Local]]. */
    private[widgets] def ref[A](m: Writable[A])(using
        Frame,
    ): SignalRef[A] < Sync =
      m match
          case Local(a) => Signal.initRef(a)
          case Bound(r) => r
end Model
