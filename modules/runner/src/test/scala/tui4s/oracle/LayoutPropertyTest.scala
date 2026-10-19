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
  *
  * One change of the damage on purpose (L1c, the fix of the painter bug of
  * L1b): a clear is cut to the clip of the painter, so a kid that overflows its
  * owner does not clear cells that the paint does not give back. The old layout
  * stays as it was; the test cuts its clears in the same way ([[cutClears]])
  * before the compare.
  */
// claude 2026-10-19: created (L1a); changed: the widths and the one changed
// default (L1a review); the cut clears (L1c review)
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
        // claude 2026-10-19: created (L1b): the region of a layer
        case Damage.Region(r)    => s"region $r"

    def oldShow(d: LayoutOracle.Damage): String = d match
        case LayoutOracle.Damage.Clear(r, bg) => s"clear $r $bg"
        case LayoutOracle.Damage.Paint(p)     => s"paint ${p.showLoc}"

    /** The step of [[PaintPropertyTest.stepped]] on the old layout: scene B
      * after the relayout of the owners of the changed regions, and the damage
      * with its clears cut as the new layout cuts them ([[cutClears]]).
      */
    // claude 2026-10-19: changed: the cut clears (L1c review)
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
                    (l2, d ++ cutClears(l, l2, owner, d2))
                  case None        => (l, d)
          }
    end oldStepped

    /** The L1c change of the damage, applied to the damage `d` of one relayout
      * of the old layout at `at` (from `pre` to `post`). The relayout goes up
      * from `at` while the natural size or the props of a node change; only the
      * damage of the node `top` where it stops is kept. So each clear of `d`
      * clears a kid of `top` (a removed kid, the old or the new box of a kid):
      * the new layout cuts it to the content boxes of `top` and of its owners
      * ([[contentClip]]). The clear just before `Paint(top)` clears `top`
      * itself: it is cut to the content boxes of the owners of `top`.
      */
    // claude 2026-10-19: created (L1c review): the delta of the frozen oracle
    def cutClears(
        pre: LayoutOracle.Laid,
        post: LayoutOracle.Laid,
        at: Loc,
        d: Vector[LayoutOracle.Damage],
    ): Vector[LayoutOracle.Damage] =
        val t    = top(pre, post, at)
        val kids = contentClip(post, Some(t))
        val self = contentClip(post, post.nodes.get(t).flatMap(_.owner))
        d.zipWithIndex.map {
          case (LayoutOracle.Damage.Clear(r, bg), i) =>
            val clip = d.lift(i + 1) match
                case Some(LayoutOracle.Damage.Paint(p)) if p == t => self
                case _                                            => kids
            LayoutOracle.Damage.Clear(clip.fold(r)(r.intersect), bg)
          case (other, _)                            => other
        }
    end cutClears

    /** The node where a relayout of the old layout at `at` stops: the first
      * node on the way up from `at` whose natural size and props are the same
      * before (`pre`) and after (`post`), or the node with no owner.
      */
    // claude 2026-10-19: created (L1c review)
    @annotation.tailrec
    def top(pre: LayoutOracle.Laid, post: LayoutOracle.Laid, at: Loc): Loc =
      (pre.nodes.get(at), post.nodes.get(at)) match
          case (Some(a), Some(b))
              if a.natural != b.natural || a.props != b.props =>
            b.owner match
                case Some(o) => top(pre, post, o)
                case None    => at
          case _ => at

    /** The clip of the painter for a kid of `from` in the old layout: the
      * content boxes of `from` and of its owners, and the screen. None when
      * `from` is none or not in `laid`.
      */
    // claude 2026-10-19: created (L1c review)
    def contentClip(laid: LayoutOracle.Laid, from: Option[Loc]): Option[Rect] =
        @annotation.tailrec
        def up(q: Option[Loc], acc: Option[Rect]): Option[Rect] =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                val box = n.kind match
                    case LayoutOracle.Kind.Screen => n.rect
                    case _                        => n.content
                up(n.owner, Some(acc.fold(box)(_.intersect(box))))
        up(from, None)
    end contentClip
end LayoutPropertyTest
