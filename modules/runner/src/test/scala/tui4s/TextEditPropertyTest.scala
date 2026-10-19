package tui4s

import kyo.UI.{Keyboard, KeyboardEvent, Modifiers, passwordInput}
import kyo.test.prop.{Gen, PropertyTest}
import kyo.{Absent, Maybe, px}
import tui4s.internal.*
import tui4s.oracle.PaintOracle

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

    // claude 2026-10-19: created (L1c)
    "maxLength: a paste is cut by grapheme clusters" - {
      // The paste goes in up to the limit, cut by clusters: the text is the
      // text before the caret, the first clusters of the paste that fit, and
      // the text after the caret.
      forAll(text, text, Gen.int, Gen.int) { (s, p, i, m) =>
        val gs     = TextEdit.graphemes(s)
        val c      = Math.floorMod(i, gs.size + 1)
        val max    = Math.floorMod(m, 10)
        val rules  = Rules(FieldAccess.Edit, max, _ => true)
        val room   = (max - gs.size).max(0)
        val cut    = TextEdit.graphemes(TextEdit.singleLine(p)).take(room).mkString
        val (t, _) = TextEdit.paste(s, c, p, rules)
        assert(t == gs.take(c).mkString + cut + gs.drop(c).mkString)
      }
    }

    // claude 2026-10-19: created (L1c)
    "NumberText: the grammar of a number field" - {
      // The text of a number field while the user types: an optional minus
      // sign first, then digits with at most one point.
      forAll(numberish)(s => assert(NumberText.partial(s) == grammar(s)))

      // A number: the grammar and a digit; its value is the value of
      // Double.parseDouble.
      forAll(numberish) { s =>
        val n = NumberText.parse(s)
        assert(n.isDefined == (grammar(s) && s.exists(_.isDigit)))
        assert(n.forall(_.toDouble == java.lang.Double.parseDouble(s)))
      }

      // The text of a number reads back as the same number.
      forAll(numberish) { s =>
        assert(
          NumberText
            .parse(s)
            .forall(v => NumberText.parse(NumberText.format(v)).contains(v)),
        )
      }

      // Typed one key at a time, a number field holds only its grammar.
      forAll(numberish) { s =>
        val rules = Rules(FieldAccess.Edit, Int.MaxValue, NumberText.partial)
        val typed = s.foldLeft(("", 0)) { case ((t, c), ch) =>
          TextEdit.key(t, c, kb(Keyboard.Char(ch)), rules)
        }
        assert(grammar(typed._1))
      }

      // claude 2026-10-19: created (L1c review): the grid of a range. The
      // value is on a step from min, inside min and max, and no grid value
      // inside them is nearer to the clamped value.
      forAll(grid) { (v, step, lo, span) =>
        val hi = lo + span
        val r  = NumberText.onGrid(BigDecimal(v), Some(step.toDouble), lo, hi)
        val k  = (r - lo) / step
        val c  = BigDecimal(v).max(lo).min(hi)
        assert(k.isWhole && r >= lo && r <= hi, s"$r")
        assert(
          (BigDecimal(lo) to BigDecimal(hi) by BigDecimal(step)).forall(g =>
            (g - c).abs >= (r - c).abs,
          ),
          s"$r",
        )
      }
    }

    // claude 2026-10-19: created (L1c)
    "a password: one cell of the mask for each grapheme cluster; no grapheme of the text goes into the plan" - {
      forAll(text) { s =>
        val n    = TextEdit.count(s)
        val size = Size(n + 2, 1)
        val env  = Scene(
          p => Option.when(p == Loc.root)(Content.Value(s)),
          None,
          size,
          _ => 0,
        )
        val ui   = passwordInput.style(_.width((n + 2).px))
        val plan = Paint(Layout.layoutAll(ui, env), env, PaintOracle.full(size))
        val run  = plan.ops.collect { case Op.Run(_, _, gs, _) => gs }.flatten
        assert(
          run.size == n && run.forall(g => g.symbol == "•" && g.width == 1),
        )
        assert(plan.ops.forall {
          case Op.Put(_, _, sym, _) => sym == " " || sym == "•"
          case _                    => true
        })
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

    /** A text of 0 to 8 characters for a number field: digits, signs, points,
      * and characters that the grammar does not take (a letter, an exponent, a
      * space, a comma, a CJK character); or a text of the grammar.
      */
    // claude 2026-10-19: created (L1c)
    val numberish: Gen[String] =
        val any   = Gen
          .list(
            Gen.oneOf('0', '1', '7', '9', '+', '-', '.', 'e', 'a', ' ', ',',
              '中'),
          )
          .map(_.take(8).mkString)
        val valid = Gen
          .zip3(
            Gen.oneOf("", "-"),
            Gen.list(Gen.oneOf('0', '3', '9')).map(_.take(4).mkString),
            Gen.oneOf("", ".", ".5", ".25"),
          )
          .map((a, b, c) => a + b + c)
        Gen.frequency(1 -> any, 1 -> valid)
    end numberish

    /** A value of -50 to 150, a step of 1 to 30, a min of 0 to 10 and a span
      * (max - min) of 0 to 100, for the grid of a range.
      */
    // claude 2026-10-19: created (L1c review)
    val grid: Gen[(Int, Int, Int, Int)] =
      Gen
        .zip(
          Gen.zip(Gen.int, Gen.int),
          Gen.zip(Gen.int, Gen.int),
        )
        .map { case ((a, b), (c, d)) =>
          (
            Math.floorMod(a, 201) - 50,
            Math.floorMod(b, 30) + 1,
            Math.floorMod(c, 11),
            Math.floorMod(d, 101),
          )
        }

    /** The grammar of a number field, written as a count: an optional minus
      * sign first, then digits and at most one point.
      */
    // claude 2026-10-19: created (L1c); changed: no plus sign (L1c review)
    def grammar(s: String): Boolean =
        val body = s.headOption match
            case Some('-') => s.drop(1)
            case _         => s
        body.count(_ == '.') <= 1 && body.forall(c =>
          c == '.' || c.isDigit && c <= '9' && c >= '0',
        )

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
