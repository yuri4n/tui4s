package tui4s.oracle

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** L1a, the viewports: random UIs with the three viewport styles of
  * [[UiGen.styled]] (`overflowY: scroll`, `overflow: hidden`, `overflow: auto`
  * with a border), random scroll offsets, random screen sizes:
  *   - the paint of a kid of a viewport names only cells of the port of the
  *     viewport;
  *   - the layout clamps an offset to [0, content - port]: an offset past the
  *     end gives the last window, and after a step to scene B (other contents)
  *     the offsets that scene A kept are in range again; `Scroll.kept` keeps
  *     only the viewports that are still there;
  *   - the follow-focus rule shows the target in its innermost viewport and
  *     moves that viewport the least distance that shows it (all offsets that
  *     show it are tried); a new layout with the new offsets puts the target at
  *     the same content position, in the window.
  */
// claude 2026-10-19: created (L1a)
class ViewportPropertyTest extends PropertyTest[Any]:
    import ViewportPropertyTest.*
    override protected def numSamples: Int = 200

    "viewports" - {
      forAll(UiGen.built(5, styles = 8), UiGen.size, seed) { (b, size, s) =>
        val env  = scrolled(b.sceneA(size), s)
        val laid = Layout.layoutAll(b.ui, env)
        val ok   = laid.nodes.values.filter(_.view.isDefined).forall { v =>
          v.kids.forall { k =>
            Paint(laid, env, Vector(Damage.Paint(k))).ops.forall(op =>
              PaintPropertyTest
                .opCells(op)
                .forall((x, y) => inside(v.port, x, y)),
            )
          }
        }
        assert(ok)
      }

      forAll(UiGen.built(5, styles = 8), UiGen.size) { (b, size) =>
        val far   = Offset(0, 1000)
        val laidA =
          Layout.layoutAll(b.ui, b.sceneA(size).copy(scroll = _ => far))
        val atMax = laidA.nodes.values
          .flatMap(_.view)
          .forall(v => v.offset == Offset(0, v.max.y))
        val keptA =
          Scroll.kept(
            laidA,
            laidA.nodes.collect { case (p, n) if n.view.isDefined => p -> far },
          )
        val envB  =
          b.sceneB(size).copy(scroll = p => keptA.getOrElse(p, Offset.zero))
        val laidB = Layout.layoutAll(b.ui, envB)
        val range = laidB.nodes.values
          .flatMap(_.view)
          .forall(v =>
            v.offset.y >= 0 && v.offset.y <= v.max.y && v.offset.x == 0,
          )
        val kept  = Scroll
          .kept(laidB, keptA)
          .keySet
          .forall(p => laidB.nodes.get(p).exists(_.view.isDefined))
        assert(atMax && range && kept)
      }

      forAll(UiGen.built(5, styles = 8), UiGen.size, seed, pick) {
        (b, size, s, i) =>
          val env     = scrolled(b.sceneA(size), s)
          val laid    = Layout.layoutAll(b.ui, env)
          val targets = laid.nodes.keys.toVector
            .sortBy(_.showLoc)
            .filter(p => innermost(laid, p).isDefined)
          if targets.isEmpty then assert(true)
          else
              val t                      = targets(i % targets.size)
              val vp                     = innermost(laid, t).getOrElse(Loc.screen)
              val v                      = laid.nodes(vp).view.get
              val box                    = laid.nodes(t).rect
              val a                      = box.y - v.port.y + v.offset.y
              val offsets                = laid.nodes.collect {
                case (p, n) if n.view.isDefined =>
                  p -> n.view.fold(Offset.zero)(_.offset)
              }
              val (scroll1, _)           = Scroll.follow(laid, t, offsets)
              val o1                     = scroll1.getOrElse(vp, Offset.zero).y
              def shows(o: Int): Boolean =
                if box.h <= v.port.h then a >= o && a + box.h <= o + v.port.h
                else a <= o && a + box.h >= o + v.port.h
              val can                    = (0 to v.max.y).filter(shows)
              val minimal                =
                if can.isEmpty then o1 >= 0 && o1 <= v.max.y
                else
                    shows(o1) &&
                    (o1 - v.offset.y).abs == can
                      .map(o => (o - v.offset.y).abs)
                      .min
              val laid1                  = Layout.layoutAll(
                b.ui,
                env.copy(scroll = p => scroll1.getOrElse(p, Offset.zero)),
              )
              val v1                     = laid1.nodes(vp).view.get
              val box1                   = laid1.nodes(t).rect
              assert(minimal)
              assert(v1.offset.y == o1)
              assert(box1.y - v1.port.y + v1.offset.y == a)
          end if
      }
    }
end ViewportPropertyTest

object ViewportPropertyTest:
    val seed: Gen[Int] = UiGen.between(0, 1000)
    val pick: Gen[Int] = UiGen.between(0, 100000)

    /** `env` with an offset of 0 to 8 rows for each viewport (a function of its
      * path and `s`).
      */
    def scrolled(env: Scene, s: Int): Scene =
      env.copy(scroll = p => Offset(0, Math.floorMod(p.hashCode * 31 + s, 9)))

    def inside(r: Rect, x: Int, y: Int): Boolean =
      x >= r.x && x < r.right && y >= r.y && y < r.bottom

    /** The innermost viewport above `p` whose vertical axis scrolls. */
    def innermost(laid: Laid, p: Loc): Option[Loc] =
        @annotation.tailrec
        def up(q: Option[Loc]): Option[Loc] =
          q.flatMap(laid.nodes.get) match
              case None    => None
              case Some(n) =>
                n.view match
                    case Some(v) if Props.scrolls(v.y) => Some(n.path)
                    case _                             => up(n.owner)
        laid.nodes.get(p).flatMap(n => up(n.owner))
    end innermost
end ViewportPropertyTest
