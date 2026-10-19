package tui4s.oracle

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** The laws of the opaque [[tui4s.internal.Loc]] (A6): a step down and `parent`
  * are inverse; `isAtOrUnder` is the ancestor relation (or equality);
  * `Loc.tops` is idempotent. The paths come from [[TopsPropertyTest.path]].
  */
// claude 2026-10-19: created (P2, A6)
class LocPropertyTest extends PropertyTest[Any]:
    import LocPropertyTest.*
    override protected def numSamples: Int = 200

    "a step down, then parent, gives the path again" - {
      forAll(TopsPropertyTest.path, UiGen.between(0, 9)) { (p, i) =>
        assert(p.child(i).parent == p)
        assert(p.content.parent == p)
        assert(p.item(s"k$i").parent == p)
        assert(p.child(i).depth == p.depth + 1)
      }
    }

    "a.isAtOrUnder(b) iff b is a or an ancestor of a" - {
      forAll(TopsPropertyTest.path, TopsPropertyTest.path) { (a, b) =>
        assert(a.isAtOrUnder(b) == lineage(a).contains(b))
        assert(a.isUnder(b) == (a != b && lineage(a).contains(b)))
      }
      // an ancestor made by `parent`: the law in the direction "true"
      forAll(TopsPropertyTest.path, UiGen.between(0, 6)) { (a, k) =>
        val b = (0 until k).foldLeft(a)((p, _) => p.parent)
        assert(a.isAtOrUnder(b))
      }
    }

    "Loc.tops is idempotent" - {
      forAll(TopsPropertyTest.paths)(ps =>
        assert(Loc.tops(Loc.tops(ps)) == Loc.tops(ps)),
      )
    }
end LocPropertyTest

object LocPropertyTest:
    /** `p` and every path above it, up to the screen. */
    def lineage(p: Loc): Vector[Loc] =
      Vector.iterate(p, p.depth + 1)(_.parent)
