package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.*

/** The armkey bookkeeping of the World (D-16, P2): `World.unarmed` holds the
  * regions whose watcher has not armed yet, by generation. It is a pure value
  * of the runner fiber, so these leaves need no runner.
  */
// claude 2026-10-19: created (P2): World.unarmedRegions, World.armed and the
// prune of Mount.gcComps
class UnarmedTest extends Test[Any]:

    private val a = Loc.root.child(0)
    private val b = Loc.root.child(1)

    private def region(p: Loc, gen: Long): RegionState =
      RegionState(p, Source.Local, gen, Content.Value(()), Vector.empty, None)

    /** A world with the regions `a` (gen 1) and `b` (gen 2), and the unarmed
      * entries `entries`.
      */
    private def world(entries: Map[Loc, Long]): World =
      World
        .init(div("x"), Size(10, 2))
        .copy(
          regions = Map(a -> region(a, 1L), b -> region(b, 2L)),
          unarmed = entries,
        )

    "unarmedRegions: only the entries of a mounted region of the same generation count" in {
      val w = world(Map(a -> 1L, b -> 7L, Loc.root.child(2) -> 3L))
      assert(w.unarmedRegions == Set(a))
    }

    "armed: the arm of the same generation takes the entry out; an old one leaves the newer entry" in {
      val w = world(Map(a -> 1L, b -> 2L))
      assert(w.armed(a, 1L).unarmed == Map(b -> 2L))
      assert(w.armed(b, 1L).unarmed == Map(a -> 1L, b -> 2L))
      assert(w.armed(Loc.root.child(2), 1L).unarmed == w.unarmed)
    }

    "gcComps drops the entries of a region that is gone or that has a new generation" in {
      val w = world(Map(a -> 1L, b -> 9L, Loc.root.child(2) -> 3L))
      assert(Mount.gcComps(w).unarmed == Map(a -> 1L))
    }
end UnarmedTest
