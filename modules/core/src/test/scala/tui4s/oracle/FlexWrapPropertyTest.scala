package tui4s.oracle

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.{Div, SpanElement}
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** L1b, `flexWrap` and `display: none`:
  *   - a row that wraps (a random width, a random gap) with random items of a
  *     fixed size: a line of two or more items is not longer than the row, the
  *     first item of each line after the first does not fit on the line before
  *     it (the greedy rule), the items of a line share its top row, and each
  *     line is as high as its tallest item (the next line starts after it and
  *     the gap);
  *   - a column of random texts and boxes, some of them `displayNone`, paints
  *     the screen of the same column without them: a hidden element takes no
  *     space and paints nothing.
  */
// claude 2026-10-19: created (L1b)
class FlexWrapPropertyTest extends PropertyTest[Any]:
    import FlexWrapPropertyTest.*
    override protected def numSamples: Int = 200

    "flex-wrap and display none" - {
      forAll(UiGen.between(5, 30), UiGen.between(0, 2), items) {
        (width, gap, sizes) =>
          val kids                                     = sizes.map((w, h) =>
            span.style(_.width(w.px).height(h.px).flexShrink(0)): UI,
          )
          val root: Div                                = div
          val ui                                       = root
            .copy(children = Chunk.from(kids))(using root.frame)
            .style(
              _.row.flexWrap(_.wrap).gap(gap.px).width(width.px).align(_.start),
            )
          val laid                                     = Layout.layoutAll(ui, UiGen.scene(Map.empty, Size(40, 200)))
          val rects                                    =
            sizes.indices.map(i => laid.nodes(Loc.root.child(i)).rect).toVector
          // the lines: the items in order, a new line at each new top row
          val byLine                                   = rects
            .zip(sizes)
            .foldLeft(Vector.empty[Vector[(Rect, (Int, Int))]]) {
              case (acc, (r, s)) =>
                acc.lastOption match
                    case Some(l) if l.head._1.y == r.y =>
                      acc.updated(acc.size - 1, l :+ (r, s))
                    case _                             => acc :+ Vector((r, s))
            }
          def used(l: Vector[(Rect, (Int, Int))]): Int =
            l.map(_._2._1).sum + gap * (l.size - 1)
          val notTooLong                               = byLine.forall(l => l.size < 2 || used(l) <= width)
          val greedy                                   = byLine
            .zip(byLine.drop(1))
            .forall((a, b) => used(a) + gap + b.head._2._1 > width)
          val heights                                  =
            rects.zip(sizes).forall((r, s) => r.h == s._2 && r.w == s._1)
          val tops                                     = byLine.map(_.head._1.y)
          val crosses                                  = byLine.map(_.map(_._2._2).max)
          val stacked                                  = tops.zip(tops.drop(1)).zip(crosses).forall {
            case ((a, b), c) => b == a + c + gap
          }
          assert(notTooLong && greedy && heights && stacked, s"$byLine")
      }

      forAll(column) { kids =>
        val shown                   = kids.collect { case (ui, false) => ui }
        val all                     = kids.map((ui, hide) => if hide then hidden(ui) else ui)
        def col(cs: Vector[UI]): UI =
            val root: Div = div
            root.copy(children = Chunk.from(cs))(using root.frame)
        assert(
          tui4s.internal.Render.text(col(all), 20, 12) ==
            tui4s.internal.Render.text(col(shown), 20, 12),
        )
      }
    }
end FlexWrapPropertyTest

object FlexWrapPropertyTest:
    /** 1 to 8 items of 1 to 9 cells by 1 to 3 rows. */
    val items: Gen[Vector[(Int, Int)]] =
      UiGen
        .between(1, 8)
        .flatMap(n =>
          Gen.listOfN(n, Gen.zip(UiGen.between(1, 9), UiGen.between(1, 3))),
        )
        .map(_.toVector)

    /** An element of the column: a text in a span, a box with a border, or a
      * row of two spans.
      */
    private def kid(kind: Int, t: String)(using Frame): UI = kind match
        case 0 => span(t)
        case 1 => div(t).style(_.border(1.px, Style.Color.white))
        case _ => div.style(_.row.gap(1.px))(span(t), span("x"))

    /** 1 to 6 elements of the column, each one hidden or not. */
    val column: Gen[Vector[(UI, Boolean)]] =
      UiGen
        .between(1, 6)
        .flatMap(n =>
          Gen.listOfN(
            n,
            Gen.zip3(UiGen.between(0, 2), UiGen.asciiText, UiGen.between(0, 1)),
          ),
        )
        .map(
          _.toVector.map((k, t, h) => (kid(k, t)(using summon[Frame]), h == 1)),
        )

    /** `ui` with `displayNone`. */
    def hidden(ui: UI)(using Frame): UI = ui match
        case s: SpanElement => s.style(_.displayNone)
        case d: Div         => d.style(_.displayNone)
        case other          => other
end FlexWrapPropertyTest
