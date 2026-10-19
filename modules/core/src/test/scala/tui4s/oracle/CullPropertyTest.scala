package tui4s.oracle

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** F3, the paint cull. Two properties:
  *   - a text writes the same rows as the old code ([[CullOracle]]);
  *   - painting the screen's nodes inside a random clip gives, inside the clip,
  *     the same cells as painting them inside the whole screen, and no cell
  *     outside the clip. A node that the cull skips wrongly changes a cell
  *     inside the clip. The texts are ASCII: `Canvas.text` skips chars, not
  *     cells, at the left edge of a clip, so a wide character or a combining
  *     mark there moves the text (a known gap of the canvas, also before the
  *     cull).
  */
// claude 2026-10-19: created (PF-a, F3)
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

      forAll(UiGen.built(5, UiGen.asciiText), UiGen.size, rect) {
        (b, size, clip) =>
          val env                                    = b.sceneA(size)
          val laid                                   = Layout.layoutAll(b.ui, env)
          val full                                   = Rect(0, 0, size.w, size.h)
          val kids                                   = laid.nodes.get(Loc.screen).fold(Vector.empty[Loc])(_.kids)
          def cells(c: Rect): Vector[Vector[String]] =
              val backend = TestBackend(size.w, size.h)
              val canvas  = Canvas(backend)
              kids.foreach(k => Paint.paintNode(canvas, laid, env, k, c))
              discard(canvas.flush(Vector(full)))
              val buf     = backend.buffer()
              Vector.tabulate(size.h, size.w)((y, x) =>
                buf.cellAt(x, y).toString,
              )
          end cells
          val inClip                                 = cells(clip)
          val inFull                                 = cells(full)
          val blank                                  = TestBackend(1, 1).buffer().cellAt(0, 0).toString
          val visible                                = clip.intersect(full)
          val same                                   = (0 until size.h).forall(y =>
            (0 until size.w).forall { x =>
              val inside = x >= visible.x && x < visible.right &&
                y >= visible.y && y < visible.bottom
              if inside then inClip(y)(x) == inFull(y)(x)
              else inClip(y)(x) == blank
            },
          )
          assert(same)
      }
    }
end CullPropertyTest

object CullPropertyTest:
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
