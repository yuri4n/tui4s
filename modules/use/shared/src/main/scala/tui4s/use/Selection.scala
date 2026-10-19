package tui4s.use

import kyo.*

/** What a move does at the first or the last item. */
// claude 2026-10-19: created (L2a)
enum Edge derives CanEqual:
    /** The move stops at the edge. */
    case Clamp

    /** The move goes on from the other edge (a step of one item; a page move
      * stops at the edge).
      */
    case Wrap
end Edge

/** The selected item of a list, by its key. Make it with [[useSelection]].
  *
  * `index` is in 0 to size - 1 (0 for an empty list). `key` is the key of the
  * selected item (Absent for an empty list). When the list changes, the
  * selection follows the key of its item; when that item goes, the selection
  * takes the index of its last move (clamped to the list).
  */
// claude 2026-10-19: created (L2a)
final class Selection[A, K] private[use] (
    items: Signal[Chunk[A]],
    keyOf: A => K,
    cursor: SignalRef[Selection.Cursor[K]],
    edge: Edge,
    page: Int,
)(using Frame):
    import Selection.*

    /** The index of the selected item. */
    val index: Signal[Int] =
      items.combineLatest(cursor).map((xs, c) => resolve(xs, keyOf, c))

    /** The key of the selected item. */
    val key: Signal[Maybe[K]] =
      items
        .combineLatest(cursor)
        .map((xs, c) => keyAt(xs, keyOf, resolve(xs, keyOf, c)))

    /** Moves the selection by `delta` items, with the edge rule. */
    def moveBy(delta: Int): Unit < Sync =
      moveTo((n, i) => step(n, i, delta, edge))

    /** One item up. */
    def moveUp: Unit < Sync = moveBy(-1)

    /** One item down. */
    def moveDown: Unit < Sync = moveBy(1)

    /** One page up (it stops at the first item). */
    def pageUp: Unit < Sync = moveTo((n, i) => step(n, i, -page, Edge.Clamp))

    /** One page down (it stops at the last item). */
    def pageDown: Unit < Sync = moveTo((n, i) => step(n, i, page, Edge.Clamp))

    /** The first item. */
    def first: Unit < Sync = moveTo((_, _) => 0)

    /** The last item. */
    def last: Unit < Sync = moveTo((n, _) => (n - 1).max(0))

    /** The item with the key `k`; nothing changes when no item has it. */
    def select(k: K): Unit < Sync =
      items.current.map(xs =>
        xs.indexWhere(keyOf(_) == k) match
            case -1 => Kyo.unit
            case i  => cursor.set(Cursor(Present(k), i)),
      )

    private def moveTo(f: (Int, Int) => Int): Unit < Sync =
      items.current.map(xs =>
        cursor.updateAndGet { c =>
          val i = f(xs.size, resolve(xs, keyOf, c))
          Cursor(keyAt(xs, keyOf, i), i)
        }.unit,
      )
end Selection

/** The pure rules of [[Selection]]. */
// claude 2026-10-19: created (L2a)
private[use] object Selection:
    /** The selected key, and its index when it was selected. */
    final case class Cursor[K](key: Maybe[K], index: Int)

    /** The index of the selection in `xs`: the index of its key, else its old
      * index clamped to `xs`.
      */
    def resolve[A, K](xs: Chunk[A], keyOf: A => K, c: Cursor[K]): Int =
        val at = c.key.fold(-1)(k => xs.indexWhere(keyOf(_) == k))
        if at >= 0 then at else c.index.min(xs.size - 1).max(0)

    /** The key of the item at `i`. */
    def keyAt[A, K](xs: Chunk[A], keyOf: A => K, i: Int): Maybe[K] =
      if i >= 0 && i < xs.size then Present(keyOf(xs(i))) else Absent

    /** The index `delta` items from `i` in a list of `n` items. */
    def step(n: Int, i: Int, delta: Int, edge: Edge): Int =
      if n <= 0 then 0
      else
          edge match
              case Edge.Clamp => (i + delta).max(0).min(n - 1)
              case Edge.Wrap  => Math.floorMod(i + delta, n)
end Selection

/** A selection in `items`, by the key of each item (`key`): its index, its key,
  * and the moves. `edge` says what a step does at the first or the last item;
  * `page` is the size of a page move. The selection starts at the first item.
  * When the list changes, the selection follows the key of its item; when that
  * item goes, the selection takes the index of its last move, clamped to the
  * list. It has no fiber: the index and the key derive from `items` and the
  * last move.
  *
  * {{{
  * for sel <- useSelection(todos, _.id, Edge.Wrap)
  * yield ul.onKeyDown {
  *   case KeyboardEvent(Keyboard.ArrowUp, _, _)   => sel.moveUp
  *   case KeyboardEvent(Keyboard.ArrowDown, _, _) => sel.moveDown
  *   case _                                      => Kyo.unit
  * }(todos.foreachKeyed(_.id.toString)(t => li(t.text)))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useSelection[A, K](
    items: Signal[Chunk[A]],
    key: A => K,
    edge: Edge = Edge.Clamp,
    page: Int = 10,
)(using Frame): Selection[A, K] < Sync =
  items.current.map(xs =>
    Signal
      .initRef(Selection.Cursor(Selection.keyAt(xs, key, 0), 0))
      .map(cursor => Selection(items, key, cursor, edge, page.max(1))),
  )
