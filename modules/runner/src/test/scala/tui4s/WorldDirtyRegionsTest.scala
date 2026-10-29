package tui4s

import kyo.UI
import kyo.test.AssertScope
import kyo.test.prop.PropertyTest
import tui4s.internal.{Loc, Msg, Size, World}
import tui4s.oracle.TopsPropertyTest

/** Dirty region marks retain first-seen order without changing other state. */
// codex 2026-10-28: created: compare region marking with an independent sequence oracle.
class WorldDirtyRegionsTest extends PropertyTest[Any]:
    override protected def numSamples: Int = 200

    // codex 2026-10-28: created: check every prefix of the writes, including an existing dirty mark.
    private def checkPaths(paths: Vector[Loc])(using AssertScope): Unit =
        val kept    = Loc.root.item("kept")
        val initial = World
          .init(UI.empty, Size(23, 9))
          .copy(
            focus = Some(kept),
            carets = Map(kept -> 2),
            nextGen = 17L,
            dirtyRegions = Vector(kept),
            dirtyNodes = Set(kept.content),
            full = false,
            pending = Set(kept),
            backlog = Vector(Msg.Repair),
            updates = 7,
            quit = true,
            repaired = 3,
            youngNow = Set(kept),
            youngPrev = Set(kept.content),
            settleTimeouts = 2,
            unarmed = Map(kept -> 4L),
            followed = Some(kept),
            scrolled = Set(kept),
            edited = Some(kept -> "before"),
          )
        paths
          .scanLeft(initial)((world, path) => world.markRegion(path))
          .zipWithIndex
          .foreach { (actual, count) =>
            val expected = (initial.dirtyRegions ++ paths.take(count)).distinct
            assert(actual == initial.copy(dirtyRegions = expected))
          }
    end checkPaths

    // codex 2026-10-28: created: generated duplicates and deep paths obey first-seen sequence semantics.
    "marking paths preserves their first occurrence and all other world fields" - {
      forAll(TopsPropertyTest.paths)(paths => checkPaths(paths))
    }

    // codex 2026-10-28: created: ancestry is not equality in either insertion order.
    "an ancestor and its descendant are both retained" in {
      val parent = Loc.root.item("a/b")
      val child  = parent.content.child(0)
      checkPaths(Vector(child, parent, child, parent))
      checkPaths(Vector(parent, child, parent, child))
    }

    // codex 2026-10-28: created: independently constructed equal paths share one mark, while distinct steps stay distinct.
    "equal reconstructed paths share a mark without conflating different steps" in {
      val first   = Loc.root.item("a/b").content.child(2)
      val rebuilt = Loc.screen.child(0).item("a/b").content.child(2)
      checkPaths(
        Vector(
          first,
          Loc.root.child(2),
          rebuilt,
          Loc.root.child(20),
          Loc.root.item("a").item("b").content.child(2),
          Loc.root.item("kept"),
          rebuilt,
        ),
      )
    }
end WorldDirtyRegionsTest
