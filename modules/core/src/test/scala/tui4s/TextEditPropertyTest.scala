package tui4s

import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}
import kyo.test.prop.{Gen, PropertyTest}
import kyo.{Absent, Maybe}
import tui4s.internal.{AreaLine, AreaText, Break, Rules, TextEdit}

/** Property tests of the grapheme split of [[TextEdit]] and of the multi-line
  * editing of a textarea ([[AreaText]]), with kyo-test-prop under the kyo-test
  * framework. The texts come from explicit generators: ASCII, white space, line
  * breaks, a combining mark, emoji with a skin tone, a ZWJ sequence, a flag, a
  * wide CJK character and a lone surrogate.
  */
// claude 2026-10-19: changed: the properties of the textarea (L1c)
class TextEditPropertyTest extends PropertyTest[Any]:
    import TextEditPropertyTest.*
    override protected def numSamples: Int = 200

    "TextEdit.graphemes" - {
      // The split loses no char and adds no char.
      forAll(text)(s => assert(TextEdit.graphemes(s).mkString == s))

      // No grapheme is empty.
      forAll(text)(s => assert(TextEdit.graphemes(s).forall(_.nonEmpty)))

      // A line break is one cluster of its own: "\n", "\r\n" or "\r".
      forAll(text)(s =>
        assert(
          TextEdit
            .graphemes(s)
            .filter(_.exists(c => c == '\n' || c == '\r'))
            .forall(AreaText.isBreak),
        ),
      )
    }

    "AreaText.linesOf" - {
      // The lines cover the graphemes in order, with the line breaks
      // between them; a wrapped line is not empty; the last line ends the
      // text; a line is not wider than the field, except its spaces at the
      // end and a grapheme wider than the field (a line of its own).
      forAll(text, width) { (s, w) =>
        val gs    = TextEdit.graphemes(s)
        val ls    = AreaText.linesOf(gs, w)
        val glued = ls.flatMap(l =>
          gs.slice(l.start, l.end) ++
            (if l.brk == Break.Newline then Vector(gs(l.end)) else Vector.empty),
        )
        assert(glued == gs)
        assert(ls.lastOption.exists(_.brk == Break.End))
        assert(ls.forall(l => l.brk != Break.Wrap || l.end > l.start))
        assert(ls.forall { l =>
          val shown = gs.slice(l.start, l.end).reverse.dropWhile(_.isBlank)
          shown.size <= 1 || shown.map(AreaText.cells).sum <= w
        })
      }
    }

    "AreaText.key: the line keys" - {
      // ArrowDown goes to the next line and keeps the column in cells, or
      // stops at the end of a shorter line (or before a wide glyph that
      // would pass the column); on the last line it goes to the end.
      forAll(text, width, Gen.int) { (s, w, i) =>
        val (gs, ls, c) = at(s, w, i)
        val r           = AreaText.row(ls, c)
        val next        = AreaText.key(s, c, kb(Keyboard.ArrowDown), w, 3, Rules.free)
        assert(next._1 == s)
        if r == ls.size - 1 then assert(next._2 == gs.size)
        else
            assert(
              keeps(gs, ls(r + 1), AreaText.column(gs, ls(r), c), next._2),
            )
      }

      // ArrowUp, the same upwards; on the first line it goes to the start.
      forAll(text, width, Gen.int) { (s, w, i) =>
        val (gs, ls, c) = at(s, w, i)
        val r           = AreaText.row(ls, c)
        val next        = AreaText.key(s, c, kb(Keyboard.ArrowUp), w, 3, Rules.free)
        if r == 0 then assert(next._2 == 0)
        else
            assert(
              keeps(gs, ls(r - 1), AreaText.column(gs, ls(r), c), next._2),
            )
      }

      // Home and End go to the ends of the visual line of the caret.
      forAll(text, width, Gen.int) { (s, w, i) =>
        val (_, ls, c) = at(s, w, i)
        val l          = ls(AreaText.row(ls, c))
        assert(
          AreaText.key(s, c, kb(Keyboard.Home), w, 3, Rules.free)._2 == l.start,
        )
        assert(
          AreaText.key(s, c, kb(Keyboard.End), w, 3, Rules.free)._2 == l.last,
        )
      }

      // A paste is one edit that keeps its line breaks: the text is the text
      // before the caret, the paste (\r\n and \r become \n, a tab a space,
      // no other control character), and the text after the caret.
      forAll(text, text, Gen.int) { (s, p, i) =>
        val gs     = TextEdit.graphemes(s)
        val c      = Math.floorMod(i, gs.size + 1)
        val clean  = p
          .replace("\r\n", "\n")
          .replace('\r', '\n')
          .replace('\t', ' ')
          .filterNot(ch => ch != '\n' && Character.isISOControl(ch))
        val before = gs.take(c).mkString
        val (t, _) = AreaText.paste(s, c, p, Rules.free)
        assert(t == before + clean + gs.drop(c).mkString)
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

    /** The width of a textarea: 1 to 12 cells. */
    // claude 2026-10-19: created (L1c)
    val width: Gen[Int] = Gen.int.map(i => 1 + Math.floorMod(i, 12))

    /** The key `k` with no modifier. */
    def kb(k: Keyboard): KeyboardEvent =
      KeyboardEvent(k, Modifiers.none, Absent: Maybe[String])

    /** The graphemes of `s`, its lines `w` cells wide, and the caret `i`
      * (modulo the number of graphemes plus one).
      */
    def at(s: String, w: Int, i: Int): (Vector[String], Vector[AreaLine], Int) =
        val gs = TextEdit.graphemes(s)
        (gs, AreaText.linesOf(gs, w), Math.floorMod(i, gs.size + 1))

    /** True when the caret `c` is on the line `l` at the column `col`, or at
      * the end of the line, or before the first glyph that passes `col`.
      */
    def keeps(gs: Vector[String], l: AreaLine, col: Int, c: Int): Boolean =
        val got = AreaText.column(gs, l, c)
        c >= l.start && c <= l.last && got <= col &&
        (c == l.last || got + AreaText.cells(gs(c)) > col)
end TextEditPropertyTest
