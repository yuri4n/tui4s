package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.{Layout, Paint}
import tui4s.testkit.{Headless, Screen}

/** The editing of a text input by grapheme clusters (OS-11, OS-15 part): the
  * cases of the old `TextFieldTest` (origin/feat/core-1-model-view), driven by
  * keys on the headless kit. Each case reads the bound ref and the screen: the
  * text cursor is the reversed cell of row 0, and its column is the cell width
  * of the graphemes before the caret.
  */
// claude 2026-10-13: created (C3)
class TextEditTest extends RunnerSuite:

    private val thumb  = "\uD83D\uDC4D\uD83C\uDFFD" /* thumbs up + skin tone: 4
     * UTF-16 chars */
    private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" /*
     * three people joined by ZWJ */
    private val flag   = "\uD83C\uDDEB\uD83C\uDDF7" // two regional indicators
    private val eAcute = "e\u0301"                  // e + COMBINING ACUTE ACCENT: one grapheme

    /** The column of the text cursor (the reversed cell) on row 0. */
    private def caretX(s: Screen): Int = s.styles(0).indexWhere(_.reversed)

    /** The cell width of `s` (the caret column after `s`). */
    private def cells(s: String): Int = Layout.textWidth(s)

    /** A run with one text input bound to a ref that holds `init`, at (0, 0),
      * `width` cells wide; the input has the focus. `f` drives it.
      */
    private def field[A](
        init: String,
        readOnly: Boolean = false,
        width: Int = 30,
    )(
        f: (Headless, SignalRef[String]) => A < (Async & Abort[Throwable]),
    )(using Frame): A < (Async & Scope & Abort[Throwable]) =
      for
          ref <- Signal.initRef(init)
          h   <- Headless.start(40, 3)(_ =>
                   div(
                     input
                       .value(ref)
                       .readOnly(readOnly)
                       .style(_.width(width.px)),
                   ),
                 )
          a   <- f(h, ref)
      yield a

    /** The text of the ref and the caret column on the screen. */
    private def state(h: Headless, ref: SignalRef[String])(using
        Frame,
    ): (String, Int) < Sync =
      for
          t <- ref.get
          s <- h.screen
      yield (t, caretX(s))

    "ASCII: insert, move, delete" in {
      field("") { (h, ref) =>
        for
            _ <- h.typeText("abc")
            a <- state(h, ref)
            _  = assert(a == ("abc", 3), s"$a")
            _ <- h.key(Keyboard.ArrowLeft).andThen(h.key(Keyboard.Backspace))
            b <- state(h, ref)
            _  = assert(b == ("ac", 1), s"$b")
            _ <- h.key(Keyboard.Delete)
            c <- state(h, ref)
            _  = assert(c == ("a", 1), s"$c")
            _ <- h.key(Keyboard.Home).andThen(h.char('>'))
            d <- state(h, ref)
            _  = assert(d == (">a", 1), s"$d")
            _ <- h.key(Keyboard.End).andThen(h.char('<'))
            e <- state(h, ref)
            _  = assert(e == (">a<", 3), s"$e")
            _ <- h.key(Keyboard.ArrowLeft)
                   .andThen(h.key(Keyboard.ArrowLeft))
                   .andThen(h.key(Keyboard.ArrowRight))
            g <- state(h, ref)
        yield assert(g == (">a<", 2), s"$g")
      }
    }

    "a combining mark belongs to its letter" in {
      field("a" + eAcute + "b") { (h, ref) =>
        for
            a <- state(h, ref)
            _  = assert(a == ("a" + eAcute + "b", 3), s"$a")
            _ <- h.key(Keyboard.ArrowLeft).andThen(h.key(Keyboard.Backspace))
            b <- state(h, ref)
            _  = assert(b == ("ab", 1), s"$b")
            // a typed combining mark joins the letter before it
            _ <- h.key(Keyboard.End)
            _ <- h.typeText("e")
            _ <- h.char('́')
            c <- state(h, ref)
            _  = assert(c == ("ab" + eAcute, 3), s"$c")
            _ <- h.key(Keyboard.ArrowLeft)
            d <- state(h, ref)
        yield assert(d == ("ab" + eAcute, 2), s"$d")
      }
    }

    "an emoji with a skin tone, a ZWJ family and a flag are one grapheme each" in {
      field(thumb + family + flag) { (h, ref) =>
        for
            a <- state(h, ref)
            _  = assert(
                   a == (thumb + family + flag, cells(thumb + family + flag)),
                   s"$a",
                 )
            _ <- h.key(Keyboard.ArrowLeft).andThen(h.key(Keyboard.Backspace))
            b <- state(h, ref)
            _  = assert(b == (thumb + flag, cells(thumb)), s"$b")
            _ <- h.key(Keyboard.Home).andThen(h.key(Keyboard.Delete))
            c <- state(h, ref)
            _  = assert(c == (flag, 0), s"$c")
            // a typed family (5 code points, 3 of them outside the BMP) is one
            // grapheme
            _ <- h.key(Keyboard.ArrowRight)
            _ <- h.typeText(family)
            d <- state(h, ref)
            _  = assert(d == (flag + family, cells(flag + family)), s"$d")
            _ <- h.key(Keyboard.ArrowLeft)
            e <- state(h, ref)
        yield assert(e == (flag + family, cells(flag)), s"$e")
      }
    }

    "CJK: the cursor cell after two wide characters is x + 4" in {
      // "\u65E5\u672C" is 日本: two wide characters
      field("\u65E5\u672C") { (h, ref) =>
        for
            a <- state(h, ref)
            _  = assert(a == ("\u65E5\u672C", 4), s"$a")
            _ <- h.key(Keyboard.ArrowLeft)
            b <- state(h, ref)
            _  = assert(b == ("\u65E5\u672C", 2), s"$b")
            _ <- h.char('x')
            c <- state(h, ref)
            s <- h.screen
        yield assert(
          c == ("\u65E5x\u672C", 3) && s.row(0).startsWith("\u65E5x\u672C"),
          s"$c\n$s",
        )
      }
    }

    "a narrow field scrolls by cells: the cursor stays inside it" in {
      for
          ascii <- field("abcdefghij", width = 6) { (h, _) =>
                     for
                         s0 <- h.screen
                         _  <- h.key(Keyboard.Home)
                         s1 <- h.screen
                     yield (s0.row(0), caretX(s0), s1.row(0), caretX(s1))
                   }
          _      = assert(ascii == ("fghij", 5, "abcdef", 0), s"$ascii")
          // four wide characters (8 cells) in 6 cells: the cursor after the
          // last one needs 1 cell, so the field shows the last two (4 cells)
          wide  <- field("\u65E5\u672C\u8A9E\u30C6", width = 6) { (h, _) =>
                     h.screen.map(s => (s.row(0), caretX(s)))
                   }
      yield assert(wide == ("\u8A9E\u30C6", 4), s"$wide")
    }

    /** The search that Paint used before `scrollStart`: it sums the cells again
      * for each start (quadratic in the graphemes before the caret).
      */
    // claude 2026-10-13: created (review R2-2)
    private def scrollReference(
        ws: Vector[Int],
        caret: Int,
        cw: Int,
        w: Int,
    ): (Int, Int) =
        val first = Iterator
          .iterate(0)(_ + 1)
          .find(i => i >= caret || ws.slice(i, caret).sum + cw <= w)
          .getOrElse(caret)
        (first, ws.slice(first, caret).sum)
    end scrollReference

    // claude 2026-10-13: created (review R2-2)
    "the scroll of a field (Paint.scrollStart) is the first start that fits, found in one walk back from the caret" in {
      val rnd    = scala.util.Random(13)
      val fields = Vector.fill(5000) {
        val n     = rnd.nextInt(80)
        val ws    = Vector.fill(n)(rnd.nextInt(3)) // 0, 1 or 2 cells
        val caret = rnd.nextInt(n + 1)
        (ws, caret, ws.lift(caret).getOrElse(1).max(1), rnd.nextInt(16))
      }
      val wrong  = fields.filter((ws, c, cw, w) =>
        Paint.scrollStart(ws, c, cw, w) != scrollReference(ws, c, cw, w),
      )
      assert(
        wrong.isEmpty,
        s"${wrong.size} fields differ, first: ${wrong.headOption}",
      )
      // 200 000 graphemes: the quadratic search needs about 2 * 10^10 additions
      val long   = Vector.fill(200_000)(1)
      assert(Paint.scrollStart(long, long.size, 1, 30) == (199_971, 29))
    }

    "Ctrl-A and Ctrl-E move to the start and the end" in {
      field("hello") { (h, ref) =>
        for
            _ <- h.key(Keyboard.Char('a'), Modifiers(ctrl = true))
            a <- state(h, ref)
            _  = assert(a == ("hello", 0), s"$a")
            _ <- h.key(Keyboard.Char('e'), Modifiers(ctrl = true))
            b <- state(h, ref)
        yield assert(b == ("hello", 5), s"$b")
      }
    }

    "Ctrl-U deletes before the cursor, Ctrl-K after it" in {
      for
          u <- field("hello world") { (h, ref) =>
                 h.key(Keyboard.ArrowLeft)
                   .andThen(h.key(Keyboard.ArrowLeft))
                   .andThen(h.key(Keyboard.Char('u'), Modifiers(ctrl = true)))
                   .andThen(state(h, ref))
               }
          _  = assert(u == ("ld", 0), s"$u")
          k <- field("hello world") { (h, ref) =>
                 h.key(Keyboard.ArrowLeft)
                   .andThen(h.key(Keyboard.ArrowLeft))
                   .andThen(h.key(Keyboard.Char('k'), Modifiers(ctrl = true)))
                   .andThen(state(h, ref))
               }
      yield assert(k == ("hello wor", 9), s"$k")
    }

    "Ctrl-W deletes the word before the cursor" in {
      def ctrlW(h: Headless): FrameStats < Async =
        h.key(Keyboard.Char('w'), Modifiers(ctrl = true))
      for
          w      <- field("hello big world") { (h, ref) =>
                      ctrlW(h)
                        .andThen(state(h, ref))
                        .map(a => ctrlW(h).andThen(state(h, ref)).map(b => (a, b)))
                    }
          _       = assert(
                      w == (("hello big ", 10), ("hello ", 6)),
                      s"$w",
                    )
          spaces <-
            field("one two   ")((h, ref) => ctrlW(h).andThen(state(h, ref)))
          _       = assert(spaces == ("one ", 4), s"$spaces")
          middle <-
            field("one two") { (h, ref) =>
              h.key(Keyboard.ArrowLeft).andThen(ctrlW(h)).andThen(state(h, ref))
            }
      yield assert(middle == ("one o", 4), s"$middle")
      end for
    }

    "a key that changes nothing keeps the text and the caret, and its frame paints nothing" in {
      val atEnd   = Seq(
        KeyboardEvent(Keyboard.ArrowRight, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.End, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Char('e'), Modifiers(ctrl = true), Absent),
        KeyboardEvent(Keyboard.Delete, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Char('k'), Modifiers(ctrl = true), Absent),
        KeyboardEvent(Keyboard.ArrowUp, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.F1, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Enter, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Char('x'), Modifiers(ctrl = true), Absent),
        KeyboardEvent(Keyboard.Char('\u0007'), Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Unknown("Shift"), Modifiers.none, Absent),
      )
      val atStart = Seq(
        KeyboardEvent(Keyboard.ArrowLeft, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Home, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Char('a'), Modifiers(ctrl = true), Absent),
        KeyboardEvent(Keyboard.Backspace, Modifiers.none, Absent),
        KeyboardEvent(Keyboard.Char('u'), Modifiers(ctrl = true), Absent),
        KeyboardEvent(Keyboard.Char('w'), Modifiers(ctrl = true), Absent),
      )
      field("abc") { (h, ref) =>
        for
            e   <- Kyo.foreach(atEnd)(ev => h.press(ev).map(f => (ev, f)))
            a   <- state(h, ref)
            _    = assert(a == ("abc", 3), s"$a")
            _   <- h.key(Keyboard.Home)
            s   <- Kyo.foreach(atStart)(ev => h.press(ev).map(f => (ev, f)))
            b   <- state(h, ref)
            _    = assert(b == ("abc", 0), s"$b")
            busy = (e ++ s).filter((_, f) => f.repainted != 0 || f.updates != 0)
        yield assert(busy.isEmpty, s"keys whose frame painted: $busy")
      }
    }

    // claude 2026-10-13: created (C3, RC-14 part): the paste cases of the old
    // TextFieldTest, with the bracketed paste of the kit (Headless.paste)
    "a paste inserts at the caret as one edit; line breaks and tabs become spaces, control characters are dropped" in {
      for
          a     <- field("ad") { (h, ref) =>
                     for
                         _  <- h.key(Keyboard.ArrowLeft)
                         fs <- h.paste("bc")
                         st <- state(h, ref)
                     yield (st, fs.size)
                   }
          _      = assert(a == (("abcd", 3), 1), s"$a")
          lines <- field("") { (h, ref) =>
                     h.paste("one\r\ntwo\nthree\tfour\u0007")
                       .andThen(state(h, ref))
                   }
          _      = assert(lines == ("one two three four", 18), s"$lines")
          emoji <- field("ab") { (h, ref) =>
                     h.key(Keyboard.ArrowLeft)
                       .andThen(h.paste(family))
                       .andThen(state(h, ref))
                   }
          _      = assert(
                     emoji == ("a" + family + "b", cells("a" + family)),
                     s"$emoji",
                   )
          none  <- field("abc") { (h, ref) =>
                     for
                         f1 <- h.paste("")
                         f2 <- h.paste("\u0007")
                         st <- state(h, ref)
                     yield (st, (f1 ++ f2).map(f => (f.repainted, f.updates)))
                   }
      yield assert(none == (("abc", 3), Seq((0, 0), (0, 0))), s"$none")
    }

    "readOnly keeps the text; the caret still moves" in {
      field("abc", readOnly = true) { (h, ref) =>
        for
            _ <- h.typeText("x ")
            _ <- h.key(Keyboard.Backspace)
            _ <- h.key(Keyboard.Char('u'), Modifiers(ctrl = true))
            _ <- h.paste("pasted")
            a <- state(h, ref)
            _  = assert(a == ("abc", 3), s"$a")
            _ <- h.key(Keyboard.ArrowLeft)
            b <- state(h, ref)
        yield assert(b == ("abc", 2), s"$b")
      }
    }

    // claude 2026-10-13: created (review R2-5)
    "Enter commits the text with onChange; on a readOnly input it runs no onChange (kyo-ui's rule)" in {
      def commits(readOnly: Boolean)(using
          Frame,
      ): Vector[String] < (Async & Scope) =
        for
            log <- AtomicRef.init(Vector.empty[String])
            ref <- Signal.initRef("abc")
            h   <- Headless.start(40, 3)(_ =>
                     div(
                       input
                         .value(ref)
                         .readOnly(readOnly)
                         .onChange(s => log.updateAndGet(_ :+ s))
                         .style(_.width(30.px)),
                     ),
                   )
            _   <- h.key(Keyboard.Enter)
            l   <- log.get
        yield l
      for
          editable <- commits(readOnly = false)
          ro       <- commits(readOnly = true)
      yield assert(
        editable == Vector("abc") && ro.isEmpty,
        s"onChange on an editable input: $editable; on a readOnly input: $ro",
      )
    }
end TextEditTest
