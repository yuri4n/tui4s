package tui4s.oracle

import kyo.*
import kyo.UI.Ast.{Foreach, Text}
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** F8: `Mount.reconcile` with structural sharing, and the compare of the verify
  * pass. A random `Foreach` (keyed by value or by index; a render that reads
  * the index or not) goes from one random list of values to another. The new
  * reconcile gives items equal to the old one ([[ReconcileOracle]]), and an
  * item whose UI is equal to its old UI keeps the old UI value.
  */
// claude 2026-10-19: created (PF-a, F8)
class ReconcilePropertyTest extends PropertyTest[Any]:
    import ReconcilePropertyTest.*
    override protected def numSamples: Int = 200

    "Mount.reconcile and Mount.sameValues" - {
      forAll(foreach, values, values) { (fe, v0, v1) =>
        val old = ReconcileOracle.reconcile(fe, Vector.empty, v0)
        assert(
          Mount.reconcile(fe, old, v1) == ReconcileOracle.reconcile(fe, old, v1),
        )
      }

      forAll(foreach, values, values) { (fe, v0, v1) =>
        val old   = Mount.reconcile(fe, Vector.empty, v0)
        val byKey = old.map(o => o.key -> o).toMap
        val kept  = Mount
          .reconcile(fe, old, v1)
          .forall(it =>
            byKey
              .get(it.key)
              .filter(o => o.value == it.value && o.ui == it.ui) match
                case Some(o) => o.ui eq it.ui
                case None    => true,
          )
        assert(kept)
      }

      forAll(foreach, values, values) { (fe, v0, v1) =>
        val items = Mount.reconcile(fe, Vector.empty, v0)
        val cur   = Chunk.from(v1)
        assert(
          Mount.sameValues(items, cur) == ReconcileOracle.sameValues(items, cur),
        )
        assert(Mount.sameValues(items, Chunk.from(v0)))
      }
    }
end ReconcilePropertyTest

object ReconcilePropertyTest:
    /** 0 to 8 values from 0 to 5: duplicates and moves are frequent. */
    val values: Gen[Vector[Int]] =
      UiGen
        .between(0, 8)
        .flatMap(n => Gen.listOfN(n, UiGen.between(0, 5)))
        .map(_.toVector)

    /** A `Foreach` of Ints: keyed by value (a duplicate gets `~i`) or by index,
      * with a render that reads the index or not.
      */
    def foreach(using Frame): Gen[Foreach[Int, UI]] =
      Gen.zip(Gen.boolean, Gen.boolean).map { (keyed, byIndex) =>
        Foreach[Int, UI](
          Signal.initConst(Chunk.empty[Int]),
          if keyed then Present((a: Int) => a.toString) else Absent,
          (i, a) => if byIndex then Text(s"$i:$a") else Text(a.toString),
        )
      }
end ReconcilePropertyTest
