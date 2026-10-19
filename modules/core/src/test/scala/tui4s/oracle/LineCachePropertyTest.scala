package tui4s.oracle

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** F4, the line cache (PF-b): the layout wraps a text once, when it measures
  * it, and the painter takes these lines ([[Lines]]).
  *   - The law of [[Lines.at]]: when it gives lines for a width, they are the
  *     lines that [[Layout.textLines]] gives at that width, for every flow, on
  *     the texts of [[WrapPropertyTest]] (the greedy wrap is stable from the
  *     width of the widest line to the width of the wrap).
  *   - The plan of a screen with the lines of the layout equals the plan of
  *     the same screen with no lines (the painter wraps again): after a full
  *     layout and after a step, on the random trees and on the trees with
  *     layers.
  */
// claude 2026-10-19: created (PF-b, F4)
class LineCachePropertyTest extends PropertyTest[Any]:
    import LineCachePropertyTest.*
    override protected def numSamples: Int = 200

    "the lines of the layout and the lines of the painter" - {
      forAll(
        WrapPropertyTest.text,
        flows,
        UiGen.between(1, 40),
        UiGen.between(0, 40),
      ) { (t, f, w, at) =>
        val lines = Lines.of(w, f, Layout.textLines(t, w, f))
        assert(lines.at(at, f).forall(_ == Layout.textLines(t, at, f)))
      }

      forAll(UiGen.built(5, UiGen.text, StepPropertyTest.styles), UiGen.size) {
        (b, size) =>
          val env  = b.sceneA(size)
          val laid = Layout.layoutAll(b.ui, env)
          val full = PaintOracle.full(size)
          assert(Paint(laid, env, full) == Paint(bare(laid), env, full))
          val (_, laidB, dmg) = PaintPropertyTest.stepped(b, size)
          val envB            = b.sceneB(size)
          assert(Paint(laidB, envB, dmg) == Paint(bare(laidB), envB, dmg))
      }

      forAll(OverlayPropertyTest.withLayers, UiGen.size) { (b, size) =>
        val env  = b.sceneA(size)
        val laid = Layout.layoutAll(b.ui, env)
        val full = PaintOracle.full(size)
        assert(Paint(laid, env, full) == Paint(bare(laid), env, full))
      }
    }
end LineCachePropertyTest

object LineCachePropertyTest:
    /** Every text flow: white space, wrap and cut. */
    val flows: Gen[TextFlow] =
      Gen
        .zip3(
          Gen.oneOf(Space.Keep, Space.Collapse, Space.KeepBreaks),
          Gen.oneOf(Wrapping.Off, Wrapping.Split, Wrapping.Whole),
          Gen.oneOf(Cut.Clip, Cut.Ellipsis),
        )
        .map(TextFlow(_, _, _))

    /** `laid` with no lines in its text nodes: the painter wraps again. */
    def bare(laid: Laid): Laid =
      laid.copy(nodes = laid.nodes.map((p, n) => p -> n.copy(lines = None)))
end LineCachePropertyTest
