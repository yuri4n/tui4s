package tui4s.oracle

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*
import tui4s.testkit.Screen

/** P5, the laws of the canvas kernels and of the row spans:
  *   - `Plan.rowSpans` gives the cells of the rects inside the screen, each
  *     cell once, in order of (y, x), with no two spans of a row that touch;
  *   - `diff` after a frame of scene A and then a step to scene B gives the
  *     cells where the two screens differ, each cell once, with the new cell; a
  *     second `diff` with no write between gives nothing; the count of `flush`
  *     is the count of `diff`;
  *   - a repaint of the same full frame flushes 0 cells;
  *   - `read` gives the screen of the backend after a flush.
  */
// claude 2026-10-19: created (P5)
class CanvasPropertyTest extends PropertyTest[Any]:
    import CanvasPropertyTest.*
    override protected def numSamples: Int = 200

    "the canvas kernels" - {
      forAll(rects, UiGen.size) { (rs, size) =>
        val screen = Rect(0, 0, size.w, size.h)
        val spans  = Plan.rowSpans(rs, screen)
        val cells  = spans.flatMap(s => (s.x until s.right).map((_, s.y)))
        val want   = rs.flatMap(r => cellsOf(r.intersect(screen))).toSet
        assert(cells.toSet == want)
        assert(cells.size == want.size)
        assert(
          spans
            .zip(spans.drop(1))
            .forall((a, b) => a.y < b.y || (a.y == b.y && a.right < b.x)),
        )
        assert(spans.forall(s => s.x < s.right))
      }

      forAll(UiGen.built(5), UiGen.size) { (b, size) =>
        val (laidA, laidB, damage) = PaintPropertyTest.stepped(b, size)
        val backend                = TestBackend(size.w, size.h)
        val canvas                 = Canvas(backend)
        val planA                  = Paint(laidA, b.sceneA(size), PaintOracle.full(size))
        val planB                  = Paint(laidB, b.sceneB(size), damage)
        canvas.write(planA)
        discard(canvas.flush(planA))
        val cellsA                 = PaintOracle.cellsOf(backend.buffer())
        // the screen of scene B, from a second canvas
        val other                  = PaintPropertyTest.Now(size)
        discard(other.draw(planA))
        discard(other.draw(planB))
        val cellsB                 = other.cells
        canvas.write(planB)
        val updates                = canvas.diff(planB.spans)
        val changed                = (for
            y <- 0 until size.h
            x <- 0 until size.w
            if cellsA(y)(x) != cellsB(y)(x)
        yield (x, y)).toSet
        assert(updates.map(u => (u.x, u.y)).toSet == changed)
        assert(updates.size == changed.size)
        assert(updates.forall(u => u.cell.toString == cellsB(u.y)(u.x)))
        assert(canvas.diff(planB.spans).isEmpty)
        // A over B, then B over A: each diff gives the changed cells
        canvas.write(planA)
        assert(canvas.diff(planA.spans).size == changed.size)
        canvas.write(planB)
        assert(canvas.flush(planB).getOrElse(-1) == changed.size)
      }

      forAll(UiGen.built(5), UiGen.size) { (b, size) =>
        val env     = b.sceneA(size)
        val plan    =
          Paint(Layout.layoutAll(b.ui, env), env, PaintOracle.full(size))
        val backend = TestBackend(size.w, size.h)
        val canvas  = Canvas(backend)
        canvas.write(plan)
        val first   = canvas.flush(plan).getOrElse(-1)
        canvas.write(plan)
        assert(canvas.flush(plan).getOrElse(-1) == 0)
        assert(first >= 0)
        assert(canvas.read() == Screen.of(backend))
      }
    }
end CanvasPropertyTest

object CanvasPropertyTest:
    /** 0 to 5 rects at -10 to 30, of a size of 0 to 20. */
    val rects: Gen[Vector[Rect]] =
      UiGen
        .between(0, 5)
        .flatMap(n => Gen.listOfN(n, CullPropertyTest.rect))
        .map(_.toVector)

    def cellsOf(r: Rect): Vector[(Int, Int)] =
      for
          y <- (r.y until r.bottom).toVector
          x <- r.x until r.right
      yield (x, y)
end CanvasPropertyTest
