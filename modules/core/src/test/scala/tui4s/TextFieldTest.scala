package tui4s

import kyo.test.*

class TextFieldTest extends Test[Any]:
    private def run(field: TextField, events: Event*): TextField =
      events.foldLeft(field)(_.edit(_))

    private def typed(text: String): Seq[Event] = text.map(Key.Char(_))

    "ASCII: insert, move, delete" in {
      val f = run(TextField.empty, typed("abc")*)
      assert(f.text == "abc" && f.cursor == 3)
      val g = run(f, Key.Left, Key.Backspace)
      assert(g.text == "ac" && g.cursor == 1)
      val h = run(g, Key.Delete)
      assert(h.text == "a" && h.cursor == 1)
      val i = run(h, Key.Home, Key.Char('>'))
      assert(i.text == ">a" && i.cursor == 1)
      val j = run(i, Key.End, Key.Char('<'))
      assert(j.text == ">a<" && j.cursor == 3)
      assert(run(j, Key.Left, Key.Left, Key.Right).cursor == 2)
    }

    "TextField(text) puts the cursor at the end" in {
      assert(TextField("hello").cursor == 5)
      assert(TextField("").cursor == 0 && TextField("") == TextField.empty)
    }

    "a combining mark belongs to its letter" in {
      val decomposed = "é" // e + COMBINING ACUTE ACCENT, one grapheme
      val f          = TextField("a" + decomposed + "b")
      assert(f.cursor == 3)
      val g          = run(f, Key.Left, Key.Backspace)
      assert(g.text == "ab" && g.cursor == 1)
      val typedMark  = run(TextField("e"), Key.Char('́'))
      assert(typedMark.text == decomposed && typedMark.cursor == 1)
      val right      = run(TextField(decomposed), Key.Home, Key.Right)
      assert(right.cursor == 1)
    }

    "an emoji is one grapheme" in {
      val thumb  = "👍🏽"     // thumbs up + skin tone: 4 UTF-16 chars
      val family = "👨‍👩‍👧" // three people joined by ZWJ
      val flag   = "🇫🇷"     // two regional indicators
      val f      = TextField(thumb + family + flag)
      assert(f.cursor == 3)
      val g      = run(f, Key.Left, Key.Backspace)
      assert(g.text == thumb + flag && g.cursor == 1)
      val h      = run(g, Key.Home, Key.Delete)
      assert(h.text == flag && h.cursor == 0)
      val pasted = run(TextField("ab"), Key.Left, Paste(family))
      assert(pasted.text == "a" + family + "b" && pasted.cursor == 2)
    }

    "Ctrl-A and Ctrl-E move to the start and the end" in {
      val f = run(TextField("hello"), Key.Ctrl('a'))
      assert(f.cursor == 0)
      assert(run(f, Key.Ctrl('e')).cursor == 5)
    }

    "Ctrl-U and Ctrl-K delete before and after the cursor" in {
      val f = run(TextField("hello world"), Key.Left, Key.Left)
      val u = run(f, Key.Ctrl('u'))
      assert(u.text == "ld" && u.cursor == 0)
      val k = run(f, Key.Ctrl('k'))
      assert(k.text == "hello wor" && k.cursor == 9)
    }

    "Ctrl-W deletes the word before the cursor" in {
      val f      = TextField("hello big world")
      val w      = run(f, Key.Ctrl('w'))
      assert(w.text == "hello big " && w.cursor == 10)
      val w2     = run(w, Key.Ctrl('w'))
      assert(w2.text == "hello " && w2.cursor == 6)
      val spaces = run(TextField("one two   "), Key.Ctrl('w'))
      assert(spaces.text == "one " && spaces.cursor == 4)
      val middle = run(TextField("one two"), Key.Left, Key.Ctrl('w'))
      assert(middle.text == "one o" && middle.cursor == 4)
    }

    "Paste inserts at the cursor; line breaks and tabs become spaces" in {
      val f     = run(TextField("ad"), Key.Left, Paste("bc"))
      assert(f.text == "abcd" && f.cursor == 3)
      val lines = run(TextField.empty, Paste("one\r\ntwo\nthree\tfour\u0007"))
      assert(lines.text == "one two three four" && lines.cursor == 18)
    }

    "an event that changes nothing returns the same object" in {
      val f       = TextField("abc")
      val end     = Seq[Event](
        Key.Right,
        Key.End,
        Key.Ctrl('e'),
        Key.Delete,
        Key.Ctrl('k'),
        Key.Up,
        Key.F(1),
        Key.Enter,
        Key.Ctrl('x'),
        Key.Char('\u0007'),
        Paste(""),
        Paste("\u0007"),
        Resize(1, 1),
        FocusGained,
      )
      assert(end.forall(e => f.edit(e) eq f))
      val start   = run(f, Key.Home)
      val atStart = Seq[Event](
        Key.Left,
        Key.Home,
        Key.Ctrl('a'),
        Key.Backspace,
        Key.Ctrl('u'),
        Key.Ctrl('w'),
      )
      assert(atStart.forall(e => start.edit(e) eq start))
      assert(TextField.empty.cleared eq TextField.empty)
    }

    "isBlank and cleared" in {
      assert(TextField("  ").isBlank && TextField.empty.isBlank)
      assert(!TextField(" a ").isBlank)
      assert(TextField("abc").cleared == TextField.empty)
    }
end TextFieldTest
