package tui4s.oracle

import kyo.*
import kyo.UI.*
import kyo.UI.Ast.{Div, Reactive}
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** L1b, the overlays: random UIs with random positioned elements, random screen
  * sizes:
  *   - the box of an absolute element in a relative box of 20 x 10 cells, with
  *     a random set of offsets (`top`, `right`, `bottom`, `left`) and a random
  *     `width` and `height` or none, is the box that the rule of
  *     [[Layout.arrangeKids]] gives (an offset places it from its edge, two
  *     opposite offsets give its size, else its size or its natural size);
  *   - the damage rule: after a step from scene A to scene B (the relayout of
  *     the owners of the changed regions, and the damage of the layers that
  *     moved), painted over the frame of scene A, every cell of the screen is
  *     the cell of a full frame of scene B. The layers are fixed boxes (with or
  *     without a background, at random zIndex values, over each other), one of
  *     them in a region that moves it or takes it away in scene B, and a
  *     dropdown in a relative box. So a step that changes a cell under a layer
  *     leaves the cells of the layer intact, and a layer never paints under a
  *     lower one. The cells compared are the boxes of the layers with a
  *     background and the regions of the layers that moved (a step of the flow
  *     alone can leave a cell that a full frame paints otherwise, for example a
  *     wide glyph that a box of 1 cell cuts). Every op of the plan lies inside
  *     its spans.
  */
// claude 2026-10-19: created (L1b)
class OverlayPropertyTest extends PropertyTest[Any]:
    import OverlayPropertyTest.*
    override protected def numSamples: Int = 200

    "overlays" - {
      forAll(edges, edges, sizes) { (h, v, size) =>
        val (l, r)                                                   = h
        val (t, b)                                                   = v
        val (w, ht)                                                  = size
        def opt(s: Style, o: Option[Int], f: Length => Style): Style =
          o.fold(s)(n => s ++ f(n.px))
        val st0                                                      = Style.position(_.absolute)
        val st                                                       = Vector[(Option[Int], Length => Style)](
          (t, Style.top(_)),
          (r, Style.right(_)),
          (b, Style.bottom(_)),
          (l, Style.left(_)),
          (w, Style.width(_)),
          (ht, Style.height(_)),
        ).foldLeft(st0) { case (s, (o, f)) => opt(s, o, f) }
        val ui                                                       = div.style(_.padding(2.px, 3.px))(
          div.style(_.position(_.relative).width(20.px).height(10.px))(
            div.style(st)("ab"),
          ),
        )
        val laid                                                     = Layout.layoutAll(ui, UiGen.scene(Map.empty, Size(40, 20)))
        val box                                                      = laid.nodes(Loc.root.child(0).child(0)).rect
        val cbX                                                      = 3
        val cbY                                                      = 2
        val ew                                                       = w.getOrElse(
          (for a <- l; c <- r yield (20 - a - c).max(0)).getOrElse(2),
        )
        // the text "ab" wraps in a box narrower than 2 cells
        val eh                                                       = ht.getOrElse(
          (for a <- t; c <- b yield (10 - a - c).max(0))
            .getOrElse(if ew >= 2 then 1 else 2),
        )
        val ex                                                       =
          l.map(cbX + _).orElse(r.map(c => cbX + 20 - c - ew)).getOrElse(cbX)
        val ey                                                       =
          t.map(cbY + _).orElse(b.map(c => cbY + 10 - c - eh)).getOrElse(cbY)
        assert(
          box == Rect(ex, ey, ew, eh),
          s"$box, want ${Rect(ex, ey, ew, eh)}",
        )
      }

      forAll(withLayers, UiGen.size) { (b, size) =>
        val laidA         = Layout.layoutAll(b.ui, b.sceneA(size))
        val (_, laidB, d) = PaintPropertyTest.stepped(b, size)
        val moved         = Layout.layerDamage(laidA, laidB)
        val now           = PaintPropertyTest.Now(size)
        discard(now.draw(Paint(laidA, b.sceneA(size), PaintOracle.full(size))))
        discard(now.draw(Paint(laidB, b.sceneB(size), d ++ moved)))
        val full          = PaintPropertyTest.Now(size)
        discard(full.draw(Paint(laidB, b.sceneB(size), PaintOracle.full(size))))
        // the boxes of the layers with a background (each of their cells is a
        // cell of a layer) and the regions of the layers that moved
        val opaque        = laidB.layers.toVector.flatMap(p =>
          laidB.nodes
            .get(p)
            .filter(_.props.bg.isDefined)
            .map(Layout.layerBox(laidB, _)),
        ) ++ moved.collect { case Damage.Region(r) => r }
        val cells         = opaque.flatMap(r =>
          for
              y <- (r.y until r.bottom).toVector
              x <- r.x until r.right
              if x >= 0 && y >= 0 && x < size.w && y < size.h
          yield (x, y),
        )
        val diff          = cells.filter((x, y) => now.cells(y)(x) != full.cells(y)(x))
        val plan          = Paint(laidB, b.sceneB(size), d ++ moved)
        assert(diff.isEmpty, s"cells that differ: $diff")
        assert(
          plan.ops.forall(op =>
            PaintPropertyTest
              .opCells(op)
              .forall(PaintPropertyTest.inSpans(plan)),
          ),
        )
      }
    }
end OverlayPropertyTest

object OverlayPropertyTest:
    /** Two opposite offsets of 0 to 8 cells, each one or none. */
    val edges: Gen[(Option[Int], Option[Int])] =
      Gen.zip(maybe(UiGen.between(0, 8)), maybe(UiGen.between(0, 8)))

    /** A width of 1 to 12 and a height of 1 to 6, each one or none. */
    val sizes: Gen[(Option[Int], Option[Int])] =
      Gen.zip(maybe(UiGen.between(1, 12)), maybe(UiGen.between(1, 6)))

    private def maybe(g: Gen[Int]): Gen[Option[Int]] =
      Gen.zip(UiGen.between(0, 1), g).map((k, n) => Option.when(k == 1)(n))

    /** A fixed box at (x, y) of w x h cells at the zIndex `z`, with a border
      * and a text, and with a background when `bg`.
      */
    final case class Box(x: Int, y: Int, w: Int, h: Int, z: Int, bg: Int)

    val box: Gen[Box] =
      Gen
        .zip3(
          Gen.zip(UiGen.between(0, 30), UiGen.between(0, 15)),
          Gen.zip(UiGen.between(1, 14), UiGen.between(1, 7)),
          Gen.zip(UiGen.between(-1, 2), UiGen.between(0, 2)),
        )
        .map { case ((x, y), (w, h), (z, bg)) => Box(x, y, w, h, z, bg) }

    def fixed(bx: Box)(using Frame): Div =
        val base = Style
          .position(_.fixed)
          .top(bx.y.px)
          .left(bx.x.px)
          .width(bx.w.px)
          .height(bx.h.px)
          .zIndex(bx.z)
          .border(1.px, Style.Color.white)
        val st   = bx.bg match
            case 0 => base
            case 1 => base.bg(Style.Color.blue)
            case _ => base.bg(Style.Color.red)
        div.style(st)(s"z${bx.z} layer text")
    end fixed

    /** A random UI (at `Loc.root.child(0)`) with three fixed boxes, a region
      * that holds a fourth box in scene A and a moved one (or none) in scene B,
      * and a dropdown in a relative box.
      */
    val withLayers: Gen[UiGen.Built] =
      Gen
        .zip3(
          UiGen.spec(5, UiGen.text, 7),
          Gen.listOfN(3, box),
          Gen.zip3(box, box, UiGen.between(0, 1)),
        )
        .map { (spec, boxes, moved) =>
          val (a, b, gone) = moved
          val inner        = UiGen.build(spec, Loc.root.child(0))
          val region       = Loc.root.child(4)
          val drop         = div.style(_.position(_.relative).alignSelf(_.start))(
            button("menu"),
            div.style(
              _.position(_.dropdown).width(8.px).bg(Style.Color.slate),
            )("one", "two"),
          )
          val root: Div    = div
          val ui           = root.copy(children =
            Chunk[UI](inner.ui) ++ Chunk.from(boxes.toList.map(fixed)) ++
              Chunk[UI](
                Reactive[UI](Signal.initConst[UI](UI.Ast.Text(""))),
                drop,
              ),
          )(using root.frame)
          val inB: UI      = if gone == 1 then UI.Ast.Text("") else fixed(b)
          UiGen.Built(
            ui,
            inner.a.updated(region, Content.Single(fixed(a))),
            inner.b.updated(region, Content.Single(inB)),
            inner.focus,
          )
        }
end OverlayPropertyTest
