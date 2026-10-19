package tui4s.oracle

import tui4s.internal.*

/** The oracle of F2: the two filters "drop the paths under another path" as
  * they were at commit 95bcf2a, copied without a change. `paint` is the root
  * filter of `Paint.apply` (after its `distinct`), `runner` is the dirty
  * region filter of `Runner.layoutStep`. Do not change them: `Loc.tops` must
  * give the same paths in the same order.
  */
// claude 2026-10-19: created (PF-a, F2): a private copy of the old code
private[tui4s] object TopsOracle:
    def paint(ps: Vector[Loc]): Vector[Loc] =
        val roots = ps.distinct
        roots.filterNot(p => roots.exists(q => q != p && p.isUnder(q)))

    def runner(dirtyRegions: Vector[Loc]): Vector[Loc] =
      dirtyRegions.filterNot(r =>
        dirtyRegions.exists(o => o != r && r.isUnder(o)),
      )
end TopsOracle
