package tui4s.oracle

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.Layout

/** F4: the wrap as one fold that carries the width. It must give the same
  * lines as the old wrap ([[WrapOracle]]) for texts with spaces, double
  * spaces, line breaks, wide characters, emoji, combining marks, lone
  * surrogates and long tokens, at widths 1 to 120. The fold relies on the
  * width additivity of jatatui's `Span.width` across a space and across a code
  * point border; two more properties check it.
  */
// claude 2026-10-19: created (PF-a, F4)
class WrapPropertyTest extends PropertyTest[Any]:
    import WrapPropertyTest.*
    override protected def numSamples: Int = 200

    "Layout.wrap" - {
      forAll(text, UiGen.between(1, 120)) { (t, w) =>
        assert(Layout.wrap(t, w) == WrapOracle.wrap(t, w))
      }

      // Narrow widths split the long tokens and the wide characters.
      forAll(text, UiGen.between(1, 6)) { (t, w) =>
        assert(Layout.wrap(t, w) == WrapOracle.wrap(t, w))
      }

      forAll(piece, piece) { (a, b) =>
        assert(
          Layout.textWidth(s"$a $b") ==
            Layout.textWidth(a) + 1 + Layout.textWidth(b),
        )
      }

      forAll(text, UiGen.between(0, 400)) { (t, k) =>
        val cut = t.offsetByCodePoints(0, k.min(t.codePointCount(0, t.length)))
        assert(
          Layout.textWidth(t) ==
            Layout.textWidth(t.substring(0, cut)) +
            Layout.textWidth(t.substring(cut)),
        )
      }
    }
end WrapPropertyTest

object WrapPropertyTest:
    /** One word: ASCII, a long token, wide CJK, an emoji, a combining mark, a
      * mark alone, a tab, a lone high and a lone low surrogate, or empty.
      */
    val piece: Gen[String] = Gen.oneOf(
      "a",
      "bb",
      "hello",
      "",
      "a-long-token-without-spaces-and-more",
      "中",
      "文字文字",
      "👍",
      "é",
      "́",
      "\t",
      "\uD83D",
      "\uDC4D",
    )

    private val sep: Gen[String] = Gen.oneOf(" ", " ", " ", "  ", "\n")

    /** 0 to 40 words with separators: a space, two spaces or a line break. */
    val text: Gen[String] =
      UiGen
        .between(0, 40)
        .flatMap(n => Gen.listOfN(n, Gen.zip(piece, sep)))
        .map(_.map((p, s) => p + s).mkString)
end WrapPropertyTest
