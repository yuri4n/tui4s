package tui4s

import kyo.test.prop.{Gen, PropertyTest}
import tui4s.internal.TextEdit

/** Property tests of the grapheme split of [[TextEdit]], with kyo-test-prop
  * under the kyo-test framework. The texts come from explicit generators:
  * ASCII, white space, line breaks, a combining mark, emoji with a skin tone, a
  * ZWJ sequence, a flag, a wide CJK character and a lone surrogate.
  */
// claude 2026-10-17: created (purity plan, phase P0): the smoke suite of the
// property tests
class TextEditPropertyTest extends PropertyTest[Any]:
    import TextEditPropertyTest.*

    "TextEdit.graphemes" - {
      // The split loses no char and adds no char.
      forAll(text)(s => assert(TextEdit.graphemes(s).mkString == s))

      // No grapheme is empty, and `count` agrees with the split.
      forAll(text) { s =>
        val gs = TextEdit.graphemes(s)
        assert(gs.forall(_.nonEmpty) && TextEdit.count(s) == gs.size)
      }
    }
end TextEditPropertyTest

object TextEditPropertyTest:
    /** One piece of a text: a grapheme, a part of a grapheme, or a control
      * character.
      */
    val piece: Gen[String] = Gen.oneOf(
      "a",
      "Z",
      "7",
      " ",
      "\t",
      "\n",
      "\r\n",
      "e\u0301",                                          // e + COMBINING ACUTE ACCENT
      "\u0301",                                           // a combining mark alone
      "\uD83D\uDC4D",                                     // thumbs up: a surrogate pair
      "\uD83D\uDC4D\uD83C\uDFFD",                         // thumbs up + skin tone
      "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", // three people joined by ZWJ
      "\uD83C\uDDEB\uD83C\uDDF7",                         // a flag: two regional indicators
      "\u4E2D",                                           // a wide CJK character
      "\uD83D",                                           // a high surrogate alone
    )

    /** A text of 0 to `size` pieces. */
    val text: Gen[String] = Gen.list(piece).map(_.mkString)
end TextEditPropertyTest
