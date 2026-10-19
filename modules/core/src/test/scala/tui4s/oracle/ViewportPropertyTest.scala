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
  *   - the follow-focus rule moves each viewport around the target (nested ones
  *     too), on each axis, the least distance that shows the target (all
  *     offsets are tried), after the moves of the viewports inside it; a new
  *     layout with the new offsets has these offsets;
  *   - a scroll laid out in place (the relayout of each viewport, the innermost
  *     first) gives the nodes of a full layout with the new offsets;
  *   - the thumb of the indicator lies inside its track, has at least 1 cell,
  *     is at the top at offset 0 and at the bottom at the last offset, and
  *     moves down when the offset grows.
  */
// claude 2026-10-19: created (L1a); changed: nested viewports, both axes,
// the scroll in place (L1a review)
class ViewportPropertyTest extends PropertyTest[Any]:
    import ViewportPropertyTest.*
    override protected def numSamples: Int = 200

    "viewports" - {
      forAll(UiGen.built(5, styles = 10), UiGen.size, seed) { (b, size, s) =>
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

      forAll(UiGen.built(5, styles = 10), UiGen.size) { (b, size) =>
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

      // claude 2026-10-19: changed: every viewport around the target, both
      // axes (L1a review)
      forAll(UiGen.built(5, styles = 10), UiGen.size, seed, pick) {
        (b, size, s, i) =>
          val env     = scrolled(b.sceneA(size), s)
          val laid    = Layout.layoutAll(b.ui, env)
          val targets = laid.nodes.keys.toVector
            .sortBy(_.showLoc)
            .filter(p => enclosing(laid, p).nonEmpty)
          if targets.isEmpty then assert(true)
          else
              val t            = targets(i % targets.size)
              val offsets      = laid.nodes.collect {
                case (p, n) if n.view.isDefined =>
                  p -> n.view.fold(Offset.zero)(_.offset)
              }
              val (scroll1, _) = Scroll.follow(laid, t, offsets)
              val laid1        = Layout.layoutAll(
                b.ui,
                env.copy(scroll = p => scroll1.getOrElse(p, Offset.zero)),
              )
              val box          = laid1.nodes(t).rect
              val all          = enclosing(laid1, t).map { vp =>
                val o0 = laid.nodes(vp).view.fold(Offset.zero)(_.offset)
                val v  = laid1.nodes(vp).view.get
                val o  = v.offset
                vp -> (o == scroll1.getOrElse(vp, Offset.zero) &&
                  nearestOk(
                    o0.x,
                    o.x,
                    box.x - v.port.x + o.x,
                    box.w,
                    v.port.w,
                    v.max.x,
                  ) &&
                  nearestOk(
                    o0.y,
                    o.y,
                    box.y - v.port.y + o.y,
                    box.h,
                    v.port.h,
                    v.max.y,
                  ))
              }
              assert(all.forall(_._2), s"$t: $all")
          end if
      }

      // claude 2026-10-19: created (L1a review): a scroll laid out in place
      forAll(UiGen.built(5, styles = 10), UiGen.size, seed) { (b, size, s) =>
        val laidA   = Layout.layoutAll(b.ui, scrolled(b.sceneA(size), s))
        val envB    = scrolled(b.sceneA(size), s + 1)
        val ports   = laidA.nodes.values
          .filter(_.view.isDefined)
          .map(_.path)
          .toVector
          .sortBy(p => -p.showLoc.length)
        val inPlace = ports.foldLeft(laidA)((l, p) =>
          Layout.relayout(l, p, Set.empty, envB, b.ui)._1,
        )
        assert(views(inPlace) == views(Layout.layoutAll(b.ui, envB)))
      }

      // claude 2026-10-19: created (L1a): the thumb of the indicator
      forAll(
        UiGen.between(3, 40),
        UiGen.between(3, 40),
        UiGen.between(1, 200),
        UiGen.between(0, 200),
      ) { (len, port, extra, o) =>
        val content         = port + extra
        val offset          = o.min(extra)
        val (at, size)      = Paint.thumb(len, port, content, offset)
        val (top, _)        = Paint.thumb(len, port, content, 0)
        val (bottom, bsize) = Paint.thumb(len, port, content, extra)
        val (next, _)       =
          Paint.thumb(len, port, content, (offset + 1).min(extra))
        assert(size >= 1 && at >= 0 && at + size <= len)
        assert(top == 0 && bottom + bsize == len && next >= at)
      }
    }
end ViewportPropertyTest

object ViewportPropertyTest:
    val seed: Gen[Int] = UiGen.between(0, 1000)
    val pick: Gen[Int] = UiGen.between(0, 100000)

    /** `env` with an offset of 0 to 4 columns and 0 to 8 rows for each viewport
      * (a function of its path and `s`).
      */
    // claude 2026-10-19: changed: the columns too (L1a review)
    def scrolled(env: Scene, s: Int): Scene =
      env.copy(scroll =
        p =>
          Offset(
            Math.floorMod(p.hashCode * 17 + s, 5),
            Math.floorMod(p.hashCode * 31 + s, 9),
          ),
      )

    /** The rect, the kids and the view of each path. */
    // claude 2026-10-19: created (L1a review)
    def views(laid: Laid): Map[Loc, (Rect, Vector[Loc], Option[View])] =
      laid.nodes.map((p, n) => p -> (n.rect, n.kids, n.view))

    /** True when the offset `o1` of one axis is the follow rule's choice from
      * `o0` for the box `a` to `a + h` (in content cells) in a port of `port`
      * cells: in [0, `max`], and, when an offset in that range shows the box,
      * one that shows it (whole when it fits, else covering the port) with the
      * least change. All offsets are tried.
      */
    // claude 2026-10-19: created (L1a review)
    def nearestOk(
        o0: Int,
        o1: Int,
        a: Int,
        h: Int,
        port: Int,
        max: Int,
    ): Boolean =
        def shows(o: Int): Boolean =
          if h <= port then a >= o && a + h <= o + port
          else a <= o && a + h >= o + port
        val can                    = (0 to max).filter(shows)
        o1 >= 0 && o1 <= max.max(0) &&
        (can.isEmpty ||
          (shows(o1) && (o1 - o0).abs == can.map(o => (o - o0).abs).min))
    end nearestOk

    /** The viewports above `p`, innermost first. */
    // claude 2026-10-19: created (L1a review)
    def enclosing(laid: Laid, p: Loc): Vector[Loc] =
        @annotation.tailrec
        def up(q: Option[Loc], acc: Vector[Loc]): Vector[Loc] =
          q.flatMap(laid.nodes.get) match
              case None    => acc
              case Some(n) =>
                up(n.owner, if n.view.isDefined then acc :+ n.path else acc)
        laid.nodes.get(p).fold(Vector.empty)(n => up(n.owner, Vector.empty))
    end enclosing

    def inside(r: Rect, x: Int, y: Int): Boolean =
      x >= r.x && x < r.right && y >= r.y && y < r.bottom

end ViewportPropertyTest
