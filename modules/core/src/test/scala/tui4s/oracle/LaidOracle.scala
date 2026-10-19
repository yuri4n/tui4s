package tui4s.oracle

import tui4s.internal.*

/** The oracle of F1: `Laid.without` as it was at commit 95bcf2a, copied
  * without a change. It filters the whole node map and the whole region map.
  * Do not change it: the new code must give the same `Laid`.
  */
// claude 2026-10-19: created (PF-a, F1): a private copy of the old code
private[tui4s] object LaidOracle:
    def without(laid: Laid, prefix: Loc): Laid =
      Laid(
        laid.nodes.filterNot((p, _) => p.isAtOrUnder(prefix)),
        laid.regionOwner.filterNot((r, o) =>
          r.isAtOrUnder(prefix) || o.isAtOrUnder(prefix),
        ),
      )
end LaidOracle
