package tui4s.oracle

import kyo.*
import kyo.UI.*
import kyo.test.prop.PropertyTest
import tui4s.internal.*

/** An incremental frame against a full frame (L1c, the painter bug of L1b). The
  * runner paints only the damage of a step: the clears and the subtrees that
  * changed. A full frame paints everything. For a step from scene A to scene B,
  * both must give the same screen.
  *
  * The cause of the bug: the damage of a kid that overflows its owner named
  * cells outside the content box of the owner (a border, or the cells after the
  * background of the owner). The clear emptied them, and the paint of the kid
  * (clipped by the content boxes of its owners, [[Paint.clipOf]]) did not give
  * them back. Now [[Layout.damageClip]] is the clip of the painter. A second
  * cause: the caret cell of a focused field put a wide glyph into a field of 1
  * cell, so its hidden half named a cell outside the field.
  */
// claude 2026-10-19: created (L1c): the negative controls and the property
// of the painter bug of L1b
class StepPropertyTest extends PropertyTest[Any]:
    import StepPropertyTest.*
    override protected def numSamples: Int = 200

    "a kid that overflows a box of 2 rows: the step keeps the text under the box" in {
      // the shrunk counterexample of the property: a box of 2 rows holds a
      // column of 4 rows; in scene B a Foreach at its end gets an item. The
      // old damage cleared the rect of the item (row 3, under the box) and
      // the paint of the item (clipped to the box) did not give back the "a"
      // under the box.
      import UiGen.Spec.*
      val b            = UiGen.build(
        Box(
          0,
          0,
          List(
            Txt(""),
            Box(
              0,
              4,
              List(
                Box(
                  0,
                  0,
                  List(
                    Txt("a hello"),
                    Txt(""),
                    Txt(""),
                    Each(Nil, List(("a", Txt("")))),
                  ),
                ),
              ),
            ),
            Txt("a"),
            Txt(""),
          ),
        ),
        Loc.root,
      )
      val (step, full) = frames(b, Size(1, 5))
      assert(step == full, s"step:\n${show(step)}\nfull:\n${show(full)}")
    }

    "a field of 1 cell with a wide value: its ops stay in the field" in {
      // the caret is on a wide character that does not fit in the field
      val ui    = div.style(_.row)(input.style(_.width(1.px)), span("ab"))
      val field = Loc.root.child(0)
      val env   = Scene(
        p => Option.when(p == field)(Content.Value("中文")),
        Some(field),
        Size(4, 1),
        _ => 0,
      )
      val laid  = Layout.layoutAll(ui, env)
      val plan  = Paint(laid, env, PaintOracle.full(Size(4, 1)))
      val rect  = laid.nodes(field).rect
      val out   = plan.ops.filter {
        case Op.Put(x, y, s, _) =>
          (x until x + Layout.textWidth(s)).exists(c =>
            c < rect.x || c >= rect.right || y < rect.y || y >= rect.bottom,
          )
        case _                  => false
      }
      assert(out.isEmpty, s"ops outside the field $rect: $out")
    }

    "an incremental step and a full frame give the same screen" - {
      forAll(UiGen.built(5, UiGen.text, styles), UiGen.size) { (b, size) =>
        val (step, full) = frames(b, size)
        assert(step == full)
      }
    }
end StepPropertyTest

object StepPropertyTest:
    /** The box styles of the property: the styles of the old layout, the widths
      * and the viewports of [[UiGen.styled]].
      */
    val styles: Int = 10

    /** The cells (with the style) after a full frame of scene A and the step to
      * scene B, and after a full frame of scene B.
      */
    def frames(b: UiGen.Built, size: Size): (Cells, Cells) =
        val laidA        = Layout.layoutAll(b.ui, b.sceneA(size))
        val (laidB, dmg) = step(b, size, laidA)
        val step0        = PaintPropertyTest.Now(size)
        discard(
          step0.draw(Paint(laidA, b.sceneA(size), PaintOracle.full(size))),
        )
        discard(step0.draw(Paint(laidB, b.sceneB(size), dmg)))
        val full         = PaintPropertyTest.Now(size)
        val laidFull     = Layout.layoutAll(b.ui, b.sceneB(size))
        discard(
          full.draw(Paint(laidFull, b.sceneB(size), PaintOracle.full(size))),
        )
        (step0.cells, full.cells)
    end frames

    type Cells = Vector[Vector[String]]

    /** The symbols of `c`, one row per line. */
    def show(c: Cells): String =
      c.map(
        _.map(cell =>
          cell.drop(cell.indexOf("symbol: ") + 8).takeWhile(_ != ','),
        ).mkString,
      ).mkString("\n")

    /** The step from scene A to scene B as the runner does it: the owner of
      * each changed region (not under another changed region) is laid out
      * again, then the owner of each changed input value and of the element
      * that loses the focus, with that node forced.
      */
    def step(b: UiGen.Built, size: Size, laidA: Laid): (Laid, Vector[Damage]) =
        val sceneB   = b.sceneB(size)
        val dirty    = UiGen.changed(b)
        val (rs, ns) = dirty.partition(laidA.regionOwner.contains)
        val (l1, d1) =
          TopsOracle.runner(rs).foldLeft((laidA, Vector.empty[Damage])) {
            case ((l, d), r) =>
              l.regionOwner.get(r) match
                  case Some(owner) =>
                    val (l2, d2) =
                      Layout.relayout(l, owner, Set.empty, sceneB, b.ui)
                    (l2, d ++ d2)
                  case None        => (l, d)
          }
        (ns ++ b.focus.toVector).foldLeft((l1, d1)) { case ((l, d), p) =>
          l.nodes.get(p).flatMap(_.owner) match
              case Some(owner) =>
                val (l2, d2) = Layout.relayout(l, owner, Set(p), sceneB, b.ui)
                (l2, d ++ d2)
              case None        => (l, d)
        }
    end step
end StepPropertyTest
