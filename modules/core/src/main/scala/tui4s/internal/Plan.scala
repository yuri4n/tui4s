package tui4s.internal

import jatatui.core.style.{Color as JColor, Style as JStyle}

/** One grapheme cluster of a text run and the number of cells that it takes:
  * the sum of the cell widths of its code points, as jatatui's `Wcwidth` counts
  * them (1; 2 for a wide character or an emoji; more for an emoji sequence, for
  * example 6 for a ZWJ family).
  */
// claude 2026-10-19: created (P5): the cells of a text, found by the painter
private[tui4s] final case class Glyph(symbol: String, width: Int)

/** One write of a paint plan. The painter clips each op to the screen and to
  * the clip of its node, so an op names only cells that the frame can change.
  * The ops of a plan apply in order: a later op writes over an earlier one.
  */
// claude 2026-10-19: created (P5): the writes of the pure painter
private[tui4s] enum Op:
    /** Each cell of `r` becomes empty (a space with no style), then gets the
      * background `bg`.
      */
    case Clear(r: Rect, bg: Option[JColor])

    /** Each cell of `r` gets the background `bg`. Its symbol and the rest of
      * its style do not change.
      */
    case Fill(r: Rect, bg: JColor)

    /** The cell (x, y) gets the symbol `symbol`, and `style` is patched onto
      * the style of the cell (a colour or a modifier of `style` replaces the
      * old one; the rest stays).
      */
    case Put(x: Int, y: Int, symbol: String, style: JStyle)

    /** The glyphs of a text from the cell (x, y), each glyph after the cells of
      * the glyph before it. A glyph writes its first cell as `Put` does, and
      * its other cells become empty: the hidden half of a wide character reads
      * as a space.
      */
    case Run(x: Int, y: Int, glyphs: Vector[Glyph], style: JStyle)

    /** The number of cell writes of this op (the `repainted` measure of
      * [[tui4s.FrameStats]]).
      */
    def cells: Int = this match
        case Clear(r, _)      => r.area
        case Fill(r, _)       => r.area
        case Put(_, _, _, _)  => 1
        case Run(_, _, gs, _) => gs.foldLeft(0)(_ + _.width)
end Op

/** The cells (x, y) to (right - 1, y) of one row. */
// claude 2026-10-19: created (P5, F9 a): the unit of the diff
private[tui4s] final case class RowSpan(y: Int, x: Int, right: Int)

/** The paint plan of one frame: an immutable value that [[Paint]] makes and
  * [[Canvas]] writes.
  *
  * For example, `Paint(laid, scene, Vector(Damage.Paint(p)))` gives the ops
  * that paint the node at `p` and its subtree, and the row spans of the rect of
  * `p`; `Canvas.write` applies the ops to the kept cells, and `Canvas.flush`
  * sends the cells of the spans that changed.
  *
  * Invariant: every cell that an op names is inside `spans`. So the cells
  * outside the spans do not change, and the diff compares only the spans.
  *
  * @param size
  *   the screen
  * @param ops
  *   the writes, in order
  * @param spans
  *   the damaged cells: disjoint row spans, in row order (see
  *   [[Plan.rowSpans]])
  * @param roots
  *   the number of subtrees painted (`FrameStats.subtrees`)
  */
// claude 2026-10-19: created (P5): the value between the painter and the canvas
private[tui4s] final case class Plan(
    size: Size,
    ops: Vector[Op],
    spans: Vector[RowSpan],
    roots: Int,
):
    /** The number of cell writes of the plan (`FrameStats.repainted`). */
    def cells: Int = ops.foldLeft(0)(_ + _.cells)
end Plan

private[tui4s] object Plan:
    /** The cells of `rects` inside `screen`, as row spans. Law: the cells of
      * the spans are the cells of the rects inside the screen; no cell is in
      * two spans; the spans are in order of (y, x), and two spans of one row do
      * not touch (they merge). So the diff compares each damaged cell once,
      * also where the rects overlap (F9 a).
      */
    // claude 2026-10-19: created (P5, F9 a)
    def rowSpans(rects: Vector[Rect], screen: Rect): Vector[RowSpan] =
        val rows = rects.iterator
          .map(_.intersect(screen))
          .filterNot(_.isEmpty)
          .flatMap(r =>
            (r.y until r.bottom).iterator.map(y => (y, r.x, r.right)),
          )
          .toVector
          .sorted
        rows.foldLeft(Vector.empty[RowSpan]) { case (acc, (y, x, right)) =>
          acc.lastOption match
              case Some(last) if last.y == y && x <= last.right =>
                acc.updated(
                  acc.size - 1,
                  last.copy(right = last.right.max(right)),
                )
              case _                                            => acc :+ RowSpan(y, x, right)
        }
    end rowSpans
end Plan
