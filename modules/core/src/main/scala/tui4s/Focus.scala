package tui4s

/** Which part of a fixed set has the focus. An immutable value that the app
  * keeps in its state. Two values are equal when they have the same order and
  * the same focused part.
  *
  * {{{
  * enum Pane derives CanEqual:
  *   case Input, Items
  *
  * val focus = Focus(Pane.Input, Pane.Items)
  * focus.next.is(Pane.Items) // true
  * }}}
  *
  * @param order
  *   the parts, in Tab order
  * @param index
  *   the position of the focused part in `order`
  */
final class Focus[A] private (val order: Vector[A], val index: Int):
    /** The focused part. */
    def current: A = order(index)

    /** True when `a` has the focus. */
    def is(a: A): Boolean = current == a

    /** The focus on the next part; after the last part, the first one. */
    def next: Focus[A] = new Focus(order, (index + 1) % order.size)

    /** The focus on the previous part; before the first part, the last one. */
    def prev: Focus[A] = new Focus(order, (index - 1 + order.size) % order.size)

    /** The focus on `a`; `this` (the same object) when `a` already has the
      * focus or is not in the order.
      */
    def to(a: A): Focus[A] =
        val i = order.indexOf(a)
        if i < 0 || i == index then this else new Focus(order, i)

    override def equals(that: Any): Boolean = that match
        case f: Focus[?] => f.index == index && f.order == order
        case _           => false

    override def hashCode: Int = order.hashCode * 31 + index

    override def toString: String =
      s"Focus($current of ${order.mkString(", ")})"
end Focus

/** Builds focus values. */
object Focus:
    /** The focus on `first`, with the parts in the order given. */
    def apply[A](first: A, rest: A*): Focus[A] =
      new Focus((first +: rest).toVector, 0)
