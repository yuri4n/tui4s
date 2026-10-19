package tui4s.oracle

import jatatui.core.backend.TestBackend
import jatatui.core.buffer.Buffer
import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** F3, the paint cull, and the clip law of P5. Two properties:
  *   - a text writes the same rows as the old code ([[CullOracle]]);
  *   - painting the screen's nodes inside a random clip gives, inside the clip,
  *     the same cells as painting them inside the whole screen, and no cell
  *     outside the clip. A node that the cull skips wrongly changes a cell
  *     inside the clip. The texts have wide characters, emoji and combining
  *     marks: the clip cuts a text by cells (`Paint.text`). The one exception
  *     is a wide character that the right edge of the clip cuts: it is not
  *     painted, so its cells in the clip keep what was under them.
  */
// claude 2026-10-19: created (PF-a, F3); changed (P5): all texts, the clip law
class CullPropertyTest extends PropertyTest[Any]:
    import CullPropertyTest.*
    override protected def numSamples: Int = 200

    "the paint cull" - {
      forAll(rect, rect, UiGen.between(0, 30)) { (r, clip, n) =>
        val area = r.intersect(clip)
        assert(
          Paint.visibleRows(r, area).filter(_ < n).toVector ==
            CullOracle.rows(r, clip, n),
        )
      }

      forAll(UiGen.built(5), UiGen.size, rect) { (b, size, clip) =>
        val env                         = b.sceneA(size)
        val laid                        = Layout.layoutAll(b.ui, env)
        val full                        = Rect(0, 0, size.w, size.h)
        val kids                        = laid.nodes.get(Loc.screen).fold(Vector.empty[Loc])(_.kids)
        def paint(c: Rect): TestBackend =
            val backend = TestBackend(size.w, size.h)
            val canvas  = Canvas(backend)
            val ops     = kids.foldLeft(Vector.empty[Op])((o, k) =>
              Paint.paintNode(o, laid, env, k, c.intersect(full)),
            )
            val plan    = Plan(size, ops, Plan.rowSpans(Vector(full), full), 0)
            canvas.write(plan)
            discard(canvas.flush(plan))
            backend
        end paint
        val inClip                      = PaintOracle.cellsOf(paint(clip).buffer())
        val fullB                       = paint(full).buffer()
        val inFull                      = PaintOracle.cellsOf(fullB)
        val blank                       = TestBackend(1, 1).buffer().cellAt(0, 0).toString
        val visible                     = clip.intersect(full)
        val cut                         = cutAtRight(fullB, visible)
        // the cells that break the law, for the message
        val bad                         = for
            y     <- 0 until size.h
            x     <- 0 until size.w
            inside = x >= visible.x && x < visible.right &&
                       y >= visible.y && y < visible.bottom
            if (!inside && inClip(y)(x) != blank) ||
              (inside && !cut((x, y)) && inClip(y)(x) != inFull(y)(x))
        yield s"($x, $y): ${inClip(y)(x)} in the clip, ${inFull(y)(x)} without"
        assert(
          bad.isEmpty,
          s"size $size, clip $clip:\n${bad.take(5).mkString("\n")}",
        )
      }
    }
end CullPropertyTest

object CullPropertyTest:
    /** The cells inside `clip` of a wide glyph of `buf` that the right edge of
      * `clip` cuts.
      */
    // claude 2026-10-19: created (P5): the exception of the clip law
    def cutAtRight(buf: Buffer, clip: Rect): Set[(Int, Int)] =
      (for
          y  <- clip.y until clip.bottom
          x0 <- 0 until clip.right.min(buf.area.width)
          w   = Layout.textWidth(buf.cellAt(x0, y).symbol())
          if w > 1 && x0 + w > clip.right
          x  <- x0.max(clip.x) until clip.right
      yield (x, y)).toSet

    /** A rectangle at -10 to 30 with a size of 0 to 20. */
    val rect: Gen[Rect] =
      Gen
        .zip4(
          UiGen.between(-10, 30),
          UiGen.between(-10, 30),
          UiGen.between(0, 20),
          UiGen.between(0, 20),
        )
        .map(Rect(_, _, _, _))
end CullPropertyTest
