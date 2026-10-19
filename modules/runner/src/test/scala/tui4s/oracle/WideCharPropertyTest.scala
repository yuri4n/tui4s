package tui4s.oracle

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.*

/** L1a, wide characters in the measures that place a glyph, on the texts of
  * [[WrapPropertyTest]] (wide CJK, emoji, combining marks, lone surrogates):
  *   - `Paint.fit` (the label of a select or a dropdown) gives exactly the
  *     cells it is asked for: the longest run of whole glyphs that fits, then
  *     spaces;
  *   - `Paint.scrollStart` (the scroll of a text input) shows the caret: the
  *     cells from the first shown grapheme to the caret, plus the cell of the
  *     cursor, fit in the field, and one more grapheme before would not fit.
  */
// claude 2026-10-19: created (L1a)
class WideCharPropertyTest extends PropertyTest[Any]:
    import WideCharPropertyTest.*
    override protected def numSamples: Int = 200

    "wide characters" - {
      forAll(WrapPropertyTest.text, UiGen.between(0, 30)) { (t, c) =>
        val out = Paint.fit(t, c)
        val all = Paint.glyphs(t)
        val k   =
          (0 to all.size).filter(n => all.take(n).map(_.width).sum <= c).max
        val pad = c - all.take(k).map(_.width).sum
        assert(Layout.textWidth(out) == c)
        assert(out == all.take(k).map(_.symbol).mkString + " " * pad)
      }

      forAll(widths, UiGen.between(0, 60), UiGen.between(2, 40)) {
        (ws, at, field) =>
          val caret         = at.min(ws.size)
          val cursorW       = ws.lift(caret).getOrElse(1).max(1)
          val (first, used) = Paint.scrollStart(ws, caret, cursorW, field)
          assert(first <= caret && used == ws.slice(first, caret).sum)
          assert(first == caret || used + cursorW <= field)
          assert(first == 0 || used + ws(first - 1) + cursorW > field)
      }
    }
end WideCharPropertyTest

object WideCharPropertyTest:
    /** The cell widths of 0 to 40 graphemes: 1 (ASCII), 2 (CJK, an emoji) or 4
      * and 6 (an emoji with a skin tone, a ZWJ family).
      */
    val widths: Gen[Vector[Int]] =
      UiGen
        .between(0, 40)
        .flatMap(n => Gen.listOfN(n, Gen.oneOf(1, 1, 2, 2, 4, 6)))
        .map(_.toVector)
end WideCharPropertyTest
