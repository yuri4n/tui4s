package tui4s.widgets

import kyo.*
import kyo.UI.*
import tui4s.use.{Edge, useSelection}

/** A tree whose nodes expand and collapse, as a [[ListView]] of its visible
  * rows: each row is indented by its depth and marked "▸" (closed), "▾" (open)
  * or nothing (a leaf). The expanded nodes are a [[Model]] of their keys; the
  * selection follows the key of its node when a node opens or closes.
  *
  * Keys (on the tree): the moves of a [[ListView]] (ArrowUp, ArrowDown, `k`,
  * `j`, PageUp, PageDown, Home, End); ArrowRight opens a closed node, or goes
  * to the first child of an open one; ArrowLeft closes an open node, or goes to
  * the parent; Space opens or closes; Enter opens or closes a node with
  * children and activates a leaf (`onActivate`).
  *
  * {{{
  * TreeView(Signal.initConst(Chunk(dirs)), _.path)(d => span(d.name))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object TreeView:

    /** A node: its value and its children. */
    final case class Node[+A](value: A, children: Chunk[Node[A]] = Chunk.empty)

    /** The look of a visible row. */
    enum Shape derives CanEqual:
        case Leaf, Open, Closed

    /** A visible row: its value, its depth and its shape. */
    final case class Row[A](value: A, depth: Int, shape: Shape)

    /** The tree of `roots`; `label` is the slot of a node. */
    def apply[A, K](
        roots: Signal[Chunk[Node[A]]],
        key: A => K,
        height: Int = 10,
        expanded: Model[Set[K]] = Model.Local(Set.empty[K]),
        onExpand: Set[K] => Any < Async = (_: Set[K]) => (),
        onActivate: A => Any < Async = (_: A) => (),
    )(label: A => UI)(using Frame): UI < Sync =
      for
          open <- Model.open(expanded, onExpand)
          shown = roots.combineLatest(open.value).map((r, o) => rows(r, key, o))
          sel  <-
            useSelection(shown, (r: Row[A]) => key(r.value), Edge.Clamp, height)
          id   <- Looks.freshId("tree")
      yield
          val current: Maybe[(Int, Row[A])] < Sync =
            for
                xs <- shown.current
                i  <- sel.index.current
            yield Maybe.when(i < xs.size)((i, xs(i)))
          def toggle(r: Row[A]): Unit < Async      =
            open.value.current.map(o =>
                val k = key(r.value)
                open.change(if o.contains(k) then o - k else o + k),
            )
          def goTo(i: Int): Unit < Sync            =
            shown.current.map(xs => sel.select(key(xs(i).value)))
          val activate: Unit < Async               =
            current.map(_.fold(Kyo.unit) { (_, r) =>
              if r.shape == Shape.Leaf then onActivate(r.value).unit
              else toggle(r)
            })
          val list                                 = Listbox.keys(sel, m => m, activate)
          def keys(e: KeyboardEvent): Any < Async  =
            current.map(_.fold(Kyo.unit) { (i, r) =>
              (e.key, r.shape) match
                  case (Keyboard.ArrowRight, Shape.Closed) => toggle(r)
                  case (Keyboard.ArrowRight, Shape.Open)   => goTo(i + 1)
                  case (Keyboard.ArrowLeft, Shape.Open)    => toggle(r)
                  case (Keyboard.ArrowLeft, _)             =>
                    shown.current.map(xs => parent(xs, i).fold(Kyo.unit)(goTo))
                  case (Keyboard.Space, Shape.Leaf)        => Kyo.unit
                  case (Keyboard.Space, _)                 => toggle(r)
                  case _                                   => list(e).unit
            })
          Listbox(
            id,
            shown.map(xs => (0, xs)),
            sel.index,
            height,
            Style.Overflow.auto,
            keys,
          )((r, _) =>
            div.style(_.row)(
              span("  " * r.depth + (r.shape match
                  case Shape.Leaf   => "  "
                  case Shape.Open   => "▾ "
                  case Shape.Closed => "▸ ")),
              label(r.value),
            ),
          )

    /** The visible rows of `roots`: each node, then the rows of its children
      * when its key is in `open`.
      */
    def rows[A, K](
        roots: Chunk[Node[A]],
        key: A => K,
        open: Set[K],
    ): Chunk[Row[A]] =
        def walk(n: Node[A], depth: Int): Chunk[Row[A]] =
            val k = key(n.value)
            if n.children.isEmpty then Chunk(Row(n.value, depth, Shape.Leaf))
            else if open.contains(k) then
                Row(n.value, depth, Shape.Open) +: n.children.flatMap(
                  walk(_, depth + 1),
                )
            else Chunk(Row(n.value, depth, Shape.Closed))
        end walk
        roots.flatMap(walk(_, 0))
    end rows

    /** The index of the parent row of the row at `i`: the nearest row before it
      * with a smaller depth.
      */
    def parent[A](rows: Chunk[Row[A]], i: Int): Maybe[Int] =
      if i <= 0 || i >= rows.size then Absent
      else
          val d = rows(i).depth
          Maybe.fromOption((i - 1 to 0 by -1).find(j => rows(j).depth < d))
end TreeView
