package tui4s.internal

// claude 2026-10-09: created (carried from .work/proto/kyoui-runner/src/tui4s/kyoui/internal/Geometry.scala; new path and package only)

/** The position of a node in the UI tree. Element children are their index, the content of a `Reactive` is `~`, the
  * items of a `Foreach` are `#<key>`. Paths are stable while the structure is stable, so focus, carets and component
  * state survive a re-render.
  */
type Loc = Vector[String]

object Loc:
    val screen: Loc = Vector.empty
    val root: Loc   = Vector("0")

extension (p: Loc)
    def isUnder(prefix: Loc): Boolean     = p.size > prefix.size && p.startsWith(prefix)
    def isAtOrUnder(prefix: Loc): Boolean = p.startsWith(prefix)
    def showLoc: String                   = if p.isEmpty then "<screen>" else p.mkString(".")

/** A rectangle of terminal cells. */
final case class Rect(x: Int, y: Int, w: Int, h: Int):
    def isEmpty: Boolean = w <= 0 || h <= 0
    def right: Int       = x + w
    def bottom: Int      = y + h
    def area: Int        = if isEmpty then 0 else w * h

    def inset(s: Sides): Rect =
        Rect(x + s.l, y + s.t, (w - s.l - s.r).max(0), (h - s.t - s.b).max(0))

    def intersect(o: Rect): Rect =
        val nx = x.max(o.x)
        val ny = y.max(o.y)
        Rect(nx, ny, (right.min(o.right) - nx).max(0), (bottom.min(o.bottom) - ny).max(0))

    def union(o: Rect): Rect =
        if isEmpty then o
        else if o.isEmpty then this
        else
            val nx = x.min(o.x)
            val ny = y.min(o.y)
            Rect(nx, ny, right.max(o.right) - nx, bottom.max(o.bottom) - ny)
end Rect

object Rect:
    val empty: Rect = Rect(0, 0, 0, 0)

final case class Size(w: Int, h: Int)

object Size:
    val zero: Size = Size(0, 0)

/** Top, right, bottom, left. */
final case class Sides(t: Int, r: Int, b: Int, l: Int):
    def h: Int                 = l + r
    def v: Int                 = t + b
    def +(o: Sides): Sides     = Sides(t + o.t, r + o.r, b + o.b, l + o.l)
    def nonZero: Boolean       = t > 0 || r > 0 || b > 0 || l > 0

object Sides:
    val zero: Sides            = Sides(0, 0, 0, 0)
    def all(n: Int): Sides     = Sides(n, n, n, n)
    def lr(l: Int, r: Int): Sides = Sides(0, r, 0, l)
