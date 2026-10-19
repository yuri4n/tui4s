package tui4s.oracle

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** P5, the pure painter against the old painter ([[PaintOracle]], A9). For a
  * random UI (texts with wide characters, emoji and combining marks) and a
  * random screen size:
  *   - a full frame gives the cells of the old painter, cell by cell with the
  *     style (symbol, colours, modifiers), and the same measures (cells
  *     written, cells flushed, subtrees painted);
  *   - a step from scene A to scene B (the damage of the relayout, painted over
  *     the frame of scene A) gives the same cells and measures;
  *   - a paint of the screen's nodes inside a random clip at x = 0 gives the
  *     cells of the old painter (the old canvas cuts a text by chars at the
  *     left edge of a clip, so the clip starts at the left edge of the screen;
  *     `CullPropertyTest` checks the left edge by the clip law);
  *   - every op of a plan lies inside the spans of the plan.
  */
// claude 2026-10-19: created (P5, A9)
class PaintPropertyTest extends PropertyTest[Any]:
    import PaintPropertyTest.*
    override protected def numSamples: Int = 200

    "the pure painter and the old painter" - {
      forAll(UiGen.built(5), UiGen.size) { (b, size) =>
        val env  = b.sceneA(size)
        val laid = Layout.layoutAll(b.ui, env)
        val old  = PaintOracle.Old(size)
        val now  = Now(size)
        val o    = old.frame(laid, env, PaintOracle.full(size))
        val n    = now.frame(laid, env, PaintOracle.full(size))
        assert(now.cells == old.cells)
        assert(n == o)
      }

      forAll(UiGen.built(5), UiGen.size) { (b, size) =>
        val (laidA, laidB, damage) = stepped(b, size)
        val old                    = PaintOracle.Old(size)
        val now                    = Now(size)
        val o0                     = old.frame(laidA, b.sceneA(size), PaintOracle.full(size))
        val n0                     = now.frame(laidA, b.sceneA(size), PaintOracle.full(size))
        val o1                     = old.frame(laidB, b.sceneB(size), damage)
        val n1                     = now.frame(laidB, b.sceneB(size), damage)
        assert(now.cells == old.cells)
        assert((n0, n1) == (o0, o1))
      }

      forAll(UiGen.built(5), UiGen.size, clipAtZero) { (b, size, clip) =>
        val env  = b.sceneA(size)
        val laid = Layout.layoutAll(b.ui, env)
        val full = Rect(0, 0, size.w, size.h)
        val kids = laid.nodes.get(Loc.screen).fold(Vector.empty[Loc])(_.kids)
        val oldB = TestBackend(size.w, size.h)
        val oldC = PaintOracle.Canvas(oldB)
        kids.foreach(k => PaintOracle.Paint.paintNode(oldC, laid, env, k, clip))
        discard(oldC.flush(Vector(full)))
        val ops  = kids.foldLeft(Vector.empty[Op])((o, k) =>
          Paint.paintNode(o, laid, env, k, clip.intersect(full)),
        )
        val now  = Now(size)
        val plan = Plan(size, ops, Plan.rowSpans(Vector(full), full), 0)
        discard(now.draw(plan))
        assert(now.cells == PaintOracle.cellsOf(oldB.buffer()))
      }

      forAll(UiGen.built(5), UiGen.size) { (b, size) =>
        val (laidA, laidB, damage) = stepped(b, size)
        val plans                  = Vector(
          Paint(laidA, b.sceneA(size), PaintOracle.full(size)),
          Paint(laidB, b.sceneB(size), damage),
        )
        assert(
          plans.forall(p => p.ops.forall(op => opCells(op).forall(inSpans(p)))),
        )
      }
    }
end PaintPropertyTest

object PaintPropertyTest:
    /** A clip that starts at the left edge of the screen: y at -5 to 20, a size
      * of 0 to 40 by 0 to 20.
      */
    val clipAtZero: Gen[Rect] =
      Gen
        .zip3(
          UiGen.between(-5, 20),
          UiGen.between(0, 40),
          UiGen.between(0, 20),
        )
        .map((y, w, h) => Rect(0, y, w, h))

    /** The pure painter and the canvas on a `TestBackend`. */
    final class Now(size: Size):
        val backend: TestBackend = TestBackend(size.w, size.h)
        private val canvas       = Canvas(backend)

        /** Writes `plan` and flushes it. Returns the cells flushed. */
        def draw(plan: Plan): Int =
            canvas.write(plan)
            canvas.flush(plan).getOrElse(-1)

        /** A frame as `Runner.frame` makes it: the cells written, the cells
          * flushed and the subtrees painted.
          */
        def frame(
            laid: Laid,
            env: Scene,
            damage: Vector[Damage],
        ): (Int, Int, Int) =
            val plan = Paint(laid, env, damage)
            (plan.cells, draw(plan), plan.roots)

        def cells: Vector[Vector[String]] =
          PaintOracle.cellsOf(backend.buffer())
    end Now

    /** Scene A laid out, scene B after the relayout of the owners of the
      * changed regions (as the runner does it), and the damage of the step.
      */
    def stepped(b: UiGen.Built, size: Size): (Laid, Laid, Vector[Damage]) =
        val laidA           = Layout.layoutAll(b.ui, b.sceneA(size))
        val (laidB, damage) =
          TopsOracle
            .runner(UiGen.changed(b))
            .foldLeft((laidA, Vector.empty[Damage])) { case ((l, d), r) =>
              l.regionOwner.get(r) match
                  case Some(owner) =>
                    val (l2, d2) =
                      Layout.relayout(l, owner, Set.empty, b.sceneB(size), b.ui)
                    (l2, d ++ d2)
                  case None        => (l, d)
            }
        (laidA, laidB, damage)
    end stepped

    /** The cells that `op` names. */
    def opCells(op: Op): Vector[(Int, Int)] = op match
        case Op.Clear(r, _)      => cellsOf(r)
        case Op.Fill(r, _)       => cellsOf(r)
        case Op.Put(x, y, _, _)  => Vector((x, y))
        case Op.Run(x, y, gs, _) =>
          (x until x + gs.foldLeft(0)(_ + _.width)).toVector.map((_, y))

    private def cellsOf(r: Rect): Vector[(Int, Int)] =
      for
          y <- (r.y until r.bottom).toVector
          x <- r.x until r.right
      yield (x, y)

    def inSpans(p: Plan)(c: (Int, Int)): Boolean =
      p.spans.exists(s => s.y == c._2 && c._1 >= s.x && c._1 < s.right)
end PaintPropertyTest
