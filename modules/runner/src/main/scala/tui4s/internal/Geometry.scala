package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import scala.annotation.tailrec

/** The position of a node in the UI tree. Element children are their index, the
  * content of a `Reactive` is `~`, the items of a `Foreach` are `#<key>`. Paths
  * are stable while the structure is stable, so focus, carets and component
  * state survive a re-render.
  *
  * An opaque type: only the constructors below make a `Loc` (`Loc.screen`,
  * `Loc.root`, `child`, `content`, `item`), so a string vector of another
  * origin is not a `Loc`. Two paths are equal when their steps are equal.
  */
// claude 2026-10-19: changed: an opaque type with its constructors, not an
// alias (purity plan, P2, amendment A6)
private[tui4s] opaque type Loc = Vector[String]

private[tui4s] object Loc:
    val screen: Loc = Vector.empty
    val root: Loc   = Vector("0")

    given CanEqual[Loc, Loc] = CanEqual.derived

    /** Compares path steps without allocating an iterator for each prefix
      * check.
      */
    // codex 2026-10-23: created: avoid iterator allocation in region and layout scans.
    private def hasPrefix(path: Loc, prefix: Loc): Boolean =
        @tailrec def loop(index: Int): Boolean =
          if index == prefix.size then true
          else if path(index) != prefix(index) then false
          else loop(index + 1)
        path.size >= prefix.size && loop(0)

    /** The paths of `ps` that are not under another path of `ps`, in the order
      * of `ps` (a duplicate stays). A path is under another path of `ps` when
      * one of its proper prefixes is in `ps`. So the cost is O(n x h^2) for n
      * paths of depth h, not O(n^2 x h) as for a compare of each pair.
      */
    // claude 2026-10-19: created (PF-a, F2): the filter of Paint and Runner
    def tops(ps: Vector[Loc]): Vector[Loc] =
        val set = ps.toSet
        ps.filterNot(p => (0 until p.size).exists(i => set.contains(p.take(i))))

    // claude 2026-10-19: created (P2, A6): the steps of a path and its
    // parent
    extension (p: Loc)
        /** The child at index `i` of an element or a fragment. */
        def child(i: Int): Loc = p :+ i.toString

        /** The content of a `Reactive` region (or of a component). */
        def content: Loc = p :+ "~"

        /** The item of key `key` of a `Foreach` region. */
        def item(key: String): Loc = p :+ s"#$key"

        /** The path one step up; the screen for the screen. */
        def parent: Loc = p.dropRight(1)

        /** The paths at and above `p`: the screen, ..., the parent, `p`. */
        // claude 2026-10-19: created (PF-b, F6)
        def prefixes: Iterator[Loc] = (0 to p.size).iterator.map(p.take)

        /** The number of steps (0 for the screen). */
        def depth: Int                        = p.size
        // codex 2026-10-23: changed: compare steps without temporary iterators.
        def isUnder(prefix: Loc): Boolean     =
          p.size > prefix.size && hasPrefix(p, prefix)
        // codex 2026-10-23: changed: compare steps without temporary iterators.
        def isAtOrUnder(prefix: Loc): Boolean = hasPrefix(p, prefix)

        /** The text of the path for the logs and the frame stats: the steps
          * joined by `.`, or `<screen>`.
          */
        def showLoc: String =
          if p.isEmpty then "<screen>" else p.mkString(".")
    end extension
end Loc

/** A rectangle of terminal cells. */
private[tui4s] final case class Rect(x: Int, y: Int, w: Int, h: Int):
    def isEmpty: Boolean = w <= 0 || h <= 0
    def right: Int       = x + w
    def bottom: Int      = y + h
    def area: Int        = if isEmpty then 0 else w * h

    def inset(s: Sides): Rect =
      Rect(x + s.l, y + s.t, (w - s.l - s.r).max(0), (h - s.t - s.b).max(0))

    def intersect(o: Rect): Rect =
        val nx = x.max(o.x)
        val ny = y.max(o.y)
        Rect(
          nx,
          ny,
          (right.min(o.right) - nx).max(0),
          (bottom.min(o.bottom) - ny).max(0),
        )
    end intersect

    def union(o: Rect): Rect =
      if isEmpty then o
      else if o.isEmpty then this
      else
          val nx = x.min(o.x)
          val ny = y.min(o.y)
          Rect(nx, ny, right.max(o.right) - nx, bottom.max(o.bottom) - ny)
end Rect

private[tui4s] object Rect:
    val empty: Rect = Rect(0, 0, 0, 0)

private[tui4s] final case class Size(w: Int, h: Int)

private[tui4s] object Size:
    val zero: Size = Size(0, 0)

/** Top, right, bottom, left. */
private[tui4s] final case class Sides(t: Int, r: Int, b: Int, l: Int):
    def h: Int             = l + r
    def v: Int             = t + b
    def +(o: Sides): Sides = Sides(t + o.t, r + o.r, b + o.b, l + o.l)
    def nonZero: Boolean   = t > 0 || r > 0 || b > 0 || l > 0

private[tui4s] object Sides:
    val zero: Sides               = Sides(0, 0, 0, 0)
    def all(n: Int): Sides        = Sides(n, n, n, n)
    def lr(l: Int, r: Int): Sides = Sides(0, r, 0, l)
