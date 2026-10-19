package tui4s.oracle

import kyo.*
import kyo.test.prop.PropertyTest
import tui4s.internal.*

/** F1: `Laid.without` walks the subtree through `kids`. It must give the same
  * `Laid` as the old filter ([[LaidOracle]]) for every node of a laid-out
  * screen, after `layoutAll` and after a step of `relayout`.
  */
// claude 2026-10-19: created (PF-a, F1)
class LaidWithoutPropertyTest extends PropertyTest[Any]:
    override protected def numSamples: Int = 200

    private def sameForEveryNode(laid: Laid): Boolean =
      laid.nodes.keys.forall(p =>
        laid.without(p) == LaidOracle.without(laid, p),
      )

    "Laid.without equals the old filter" - {
      forAll(UiGen.built(5), UiGen.size) { (b, size) =>
        val (laidA, laidB) = UiGen.steppedLaid(b, size)
        assert(sameForEveryNode(laidA))
        assert(sameForEveryNode(laidB))
      }
    }
end LaidWithoutPropertyTest
