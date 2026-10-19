package tui4s.oracle

import kyo.*
import kyo.test.prop.PropertyTest
import tui4s.internal.*

/** L1a, the baseline: for a random UI that uses none of the new properties
  * (overflow, white-space, text-wrap, text-overflow), the layout gives the same
  * screen as the old layout ([[LayoutOracle]]) on a random screen size:
  *   - a full layout gives the same rect and the same kids for each path;
  *   - a step from scene A to scene B (the relayout of the owners of the
  *     changed regions, as the runner does it) gives the same rects, kids and
  *     damage.
  *
  * One default changed on purpose: a text in a row of a definite `width` now
  * wraps (CSS wraps a text in a box of a definite width), and the old layout
  * never wrapped a text in a row. The UIs have a width on a column and on a row
  * (a `span` is a row too); a UI in which a text wraps in a row (in scene A or
  * in scene B) is not compared. A width on a column keeps the old rule, so it
  * is compared.
  */
// claude 2026-10-19: created (L1a); changed: the widths and the one changed
// default (L1a review)
class LayoutPropertyTest extends PropertyTest[Any]:
    import LayoutPropertyTest.*
    override protected def numSamples: Int = 200

    "the layout and the old layout" - {
      forAll(UiGen.built(5, styles = 7), UiGen.size) { (b, size) =>
        val env  = b.sceneA(size)
        val laid = Layout.layoutAll(b.ui, env)
        assert(
          rowWraps(laid) ||
            shape(laid) == oldShape(LayoutOracle.Layout.layoutAll(b.ui, env)),
        )
      }

      forAll(UiGen.built(5, styles = 7), UiGen.size) { (b, size) =>
        val (_, laid, damage)    = PaintPropertyTest.stepped(b, size)
        val (oldLaid, oldDamage) = oldStepped(b, size)
        val changed              =
          rowWraps(Layout.layoutAll(b.ui, b.sceneA(size))) || rowWraps(laid)
        assert(changed || shape(laid) == oldShape(oldLaid))
        assert(changed || damage.map(show) == oldDamage.map(oldShow))
      }
    }
end LayoutPropertyTest

object LayoutPropertyTest:
    /** True when a text of `laid` wraps in a row: the one default that the old
      * layout does not have.
      */
    // claude 2026-10-19: created (L1a review)
    def rowWraps(laid: Laid): Boolean =
      laid.nodes.values.exists(n =>
        n.kind match
            case Kind.Txt(_, flow) =>
              flow.wrap != Wrapping.Off &&
              n.owner.flatMap(laid.nodes.get).exists(_.props.dir == Dir.Row)
            case _                 => false,
      )

    /** The rect and the kids of each path. */
    def shape(laid: Laid): Map[Loc, (Rect, Vector[Loc])] =
      laid.nodes.map((p, n) => p -> (n.rect, n.kids))

    def oldShape(
        laid: LayoutOracle.Laid,
    ): Map[Loc, (Rect, Vector[Loc])] =
      laid.nodes.map((p, n) => p -> (n.rect, n.kids))

    def show(d: Damage): String = d match
        case Damage.Clear(r, bg) => s"clear $r $bg"
        case Damage.Paint(p)     => s"paint ${p.showLoc}"

    def oldShow(d: LayoutOracle.Damage): String = d match
        case LayoutOracle.Damage.Clear(r, bg) => s"clear $r $bg"
        case LayoutOracle.Damage.Paint(p)     => s"paint ${p.showLoc}"

    /** The step of [[PaintPropertyTest.stepped]] on the old layout: scene B
      * after the relayout of the owners of the changed regions, and the damage.
      */
    def oldStepped(
        b: UiGen.Built,
        size: Size,
    ): (LayoutOracle.Laid, Vector[LayoutOracle.Damage]) =
        val laidA = LayoutOracle.Layout.layoutAll(b.ui, b.sceneA(size))
        TopsOracle
          .runner(UiGen.changed(b))
          .foldLeft((laidA, Vector.empty[LayoutOracle.Damage])) {
            case ((l, d), r) =>
              l.regionOwner.get(r) match
                  case Some(owner) =>
                    val (l2, d2) = LayoutOracle.Layout
                      .relayout(l, owner, Set.empty, b.sceneB(size), b.ui)
                    (l2, d ++ d2)
                  case None        => (l, d)
          }
    end oldStepped
end LayoutPropertyTest
