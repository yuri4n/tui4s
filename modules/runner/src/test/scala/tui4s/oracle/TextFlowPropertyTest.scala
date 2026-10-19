package tui4s.oracle

import kyo.*
import kyo.test.prop.PropertyTest
import tui4s.internal.*

/** L1a, the text flow (white-space, text-wrap, text-overflow) on the texts of
  * [[WrapPropertyTest]] (spaces, double spaces, line breaks, wide characters,
  * emoji, combining marks, lone surrogates, long tokens), at widths 1 to 60:
  *   - with no text-flow property the lines are the old ones: the old wrap
  *     ([[WrapOracle]]) in a column, the line breaks in a row;
  *   - `Wrapping.Whole` keeps each word whole: the lines of a paragraph, joined
  *     with one space, give the paragraph back, and a line wider than the width
  *     is one word;
  *   - `Space.Collapse` leaves no line break, no two spaces in a row and no
  *     space at the two ends of a line;
  *   - the ellipsis of a line fits in its width, ends with "…", and keeps the
  *     longest run of whole glyphs of the line that fits next to it.
  */
// claude 2026-10-19: created (L1a)
class TextFlowPropertyTest extends PropertyTest[Any]:
    import WrapPropertyTest.text
    override protected def numSamples: Int = 200

    "the text flow" - {
      forAll(text, UiGen.between(1, 60)) { (t, w) =>
        assert(Layout.textLines(t, w, TextFlow.column) == WrapOracle.wrap(t, w))
        assert(
          Layout.textLines(t, w, TextFlow.row) == t.split("\n", -1).toVector,
        )
      }

      forAll(text, UiGen.between(1, 60)) { (t, w) =>
        val ok = t.split("\n", -1).forall { para =>
          val lines = Layout.wrapWhole(para, w)
          lines.mkString(" ") == para &&
          lines.forall(l => Layout.textWidth(l) <= w || !l.contains(" "))
        }
        assert(ok)
      }

      forAll(text, UiGen.between(1, 60)) { (t, w) =>
        val flow = TextFlow(Space.Collapse, Wrapping.Split, Cut.Clip)
        assert(
          Layout
            .textLines(t, w, flow)
            .forall(l => !l.contains("\n") && !l.contains("  ") && l == l.trim),
        )
      }

      forAll(text, UiGen.between(1, 60)) { (t, w) =>
        val line = Layout.collapse(t)
        val cut  = Paint.ellipsis(line, w)
        val all  = Paint.glyphs(line)
        // the most glyphs whose cells fit in w - 1 (each glyph has a cell)
        val k    =
          (0 to all.size).filter(n => all.take(n).map(_.width).sum <= w - 1).max
        val fits = Layout.textWidth(cut) <= w && cut.endsWith("…")
        assert(fits && cut == all.take(k).map(_.symbol).mkString + "…")
      }
    }
end TextFlowPropertyTest
