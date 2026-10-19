package tui4s.oracle

import kyo.*
import kyo.UI.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** The scan for warnings with the screen of the last frame (PF-b): an element
  * at the same path in that screen is not scanned again. Law: after a full
  * frame of scene A whose warnings are seen, the step to scene B gives the same
  * new warnings with the screen of A as without it. The scenes are a list of
  * spans with ignored properties in a `Foreach`; scene B keeps some items of
  * scene A (the same element, as the runner keeps them) and makes new ones.
  */
// claude 2026-10-19: created (PF-b)
class WarningsScanPropertyTest extends PropertyTest[Any]:
    override protected def numSamples: Int = 200

    private val key: Gen[String]                         = Gen.oneOf("a", "b", "c", "d", "e")
    private val look: Gen[Int]                           = UiGen.between(0, 4)
    private val items: Gen[List[(String, Int, Boolean)]] =
      UiGen
        .between(0, 5)
        .flatMap(n =>
          Gen.listOfN(n, Gen.zip3(key, look, Gen.oneOf(true, false))),
        )
        .map(_.toList.distinctBy(_._1))

    private def span0(k: String, s: Int)(using Frame): UI =
        val el = span(k)
        s match
            case 1 => el.style(_.fontSize(12.px))
            case 2 => el.style(_.cursor(_.pointer))
            case 3 => el.style(_.display(_.table))
            case 4 => el.style(_.color(Style.Color.variable("accent")))
            case _ => el
    end span0

    "the scan with the last screen gives the new warnings of a full scan" - {
      forAll(items, items, UiGen.size) { (as, bs, size) =>
        val region       = Loc.root.child(0)
        val ui           = div(ScanRegion.of)
        val uiA          = as.map((k, s, _) => (k, span0(k, s))).toMap
        val itemsB       = bs.map((k, s, keep) =>
          (k, uiA.get(k).filter(_ => keep).getOrElse(span0(k, s))),
        )
        val sceneA       = UiGen.scene(
          Map(
            region -> Content.Many(as.map((k, _, _) => (k, uiA(k))).toVector),
          ),
          size,
        )
        val sceneB       = UiGen
          .scene(Map(region -> Content.Many(itemsB.toVector)), size)
          .copy(changes = Changes.of(Vector(region)))
        val laidA        = Layout.layoutAll(ui, sceneA)
        val seen         =
          Warnings
            .scan(laidA, PaintOracle.full(size), Set.empty)
            .map(_._1)
            .toSet
        val (laidB, dmg) = laidA.regionOwner
          .get(region)
          .fold((laidA, Vector.empty[Damage]))(o =>
            Layout.relayout(laidA, o, Set.empty, sceneB, ui),
          )
        assert(
          Warnings.scan(laidB, dmg, seen, laidA) ==
            Warnings.scan(laidB, dmg, seen),
        )
      }
    }
end WarningsScanPropertyTest

/** A `Foreach` whose items come from the scene. */
private object ScanRegion:
    def of(using Frame): UI =
      UI.Ast.Foreach[String, UI](
        Signal.initConst(Chunk.empty[String]),
        Absent,
        (_, _) => UI.Ast.Text(""),
      )
