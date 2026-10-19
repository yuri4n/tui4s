package examples.forms

import kyo.*
import kyo.UI.{Keyboard, Modifiers}
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}

/** The form on the headless test kit: the first screen, one leaf for each kind
  * of input, the submit by Enter, Reset, and Esc.
  */
// claude 2026-10-19: created (L1c); changed: one leaf for each kind of input
// (L1c review)
class FormsSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    private def start(using Frame): Headless < (Async & Scope) =
      Headless.start(60, 24)(Forms.ui)

    /** The line of the screen that has `label`. */
    private def line(s: Screen, label: String): String =
      s.text.linesIterator.find(_.contains(label)).getOrElse("")

    /** The words of the screen, one space between them: a sentence that wraps
      * over two lines is one string here.
      */
    private def words(s: Screen): String =
      s.text.split("\\s+").filter(_.nonEmpty).mkString(" ")

    /** Presses Tab `n` times. */
    private def tabs(h: Headless, n: Int)(using Frame): Unit < Async =
      Kyo.foreach(1 to n)(_ => h.key(Keyboard.Tab)).unit

    /** Starts the form, moves the focus `n` fields down (Tab), runs `keys`, and
      * gives the screen.
      */
    private def after(n: Int)(keys: Headless => Any < Async)(using
        Frame,
    ): Screen =
      run {
        for
            h <- start
            _ <- tabs(h, n)
            _ <- keys(h)
            s <- h.screen
        yield s
      }

    test("the first screen shows each input with its value") {
      val s = run(start.map(_.screen))
      assert(s.contains("Sign up"), s.text)
      assert(line(s, "Name").contains("Name"), s.text)
      assert(line(s, "Age").contains("30"), s.text)
      assert(line(s, "Volume").contains("●"), s.text)
      assert(line(s, "Mail").contains("[x] news"), s.text)
      assert(line(s, "Mail").contains("[ ] updates"), s.text)
      assert(line(s, "Plan").contains("(•) Free"), s.text)
      assert(line(s, "Plan").contains("( ) Pro"), s.text)
      assert(line(s, "Colour").contains("Blue"), s.text)
      assert(line(s, "Size").contains("Medium"), s.text)
      assert(line(s, "Account").contains("ada-01"), s.text)
      assert(line(s, "Team").contains("core"), s.text)
      assert(s.contains("Submit") && s.contains("Reset"), s.text)
      assert(s.contains("Fill the form, then press Enter."), s.text)
      // the name field has the focus: the terminal cursor is in it
      assert(
        s.cursor.exists(
          _._2 == s.text.linesIterator.indexWhere(_.contains("Name")),
        ),
        s"${s.cursor}",
      )
    }

    test("a text input: the name takes at most 20 characters; Enter submits") {
      val s = after(0)(h =>
        h.typeText("abcdefghijklmnopqrstuvwxyz").andThen(h.key(Keyboard.Enter)),
      )
      // 20 characters in a field of 20 cells: the caret after them takes a
      // cell, so the field shows the last 19
      assert(
        line(s, "Name").contains("bcdefghijklmnopqrst") &&
          !line(s, "Name").contains("u"),
        s.text,
      )
      assert(words(s).contains("Sent: abcdefghijklmnopqrst,"), s.text)
    }

    test("a password: one • for each character; the text is not on the screen") {
      val s = after(1)(h => h.typeText("pw").andThen(h.key(Keyboard.Enter)))
      assert(line(s, "Secret").contains("••"), s.text)
      assert(!s.contains("pw"), s.text)
      assert(words(s).contains("Sent: , secret, age 30,"), s.text)
    }

    test("a number: ArrowUp and ArrowDown step the age; letters do not go in") {
      val s = after(2)(h =>
        h.key(Keyboard.ArrowUp)
          .andThen(h.key(Keyboard.ArrowUp))
          .andThen(h.key(Keyboard.ArrowDown))
          .andThen(h.typeText("x"))
          .andThen(h.key(Keyboard.Enter)),
      )
      assert(line(s, "Age").contains("31"), s.text)
      assert(words(s).contains("age 31,"), s.text)
    }

    test("a range: ArrowRight and ArrowLeft move the volume by its step of 5") {
      val s = after(3)(h =>
        h.key(Keyboard.ArrowRight)
          .andThen(h.key(Keyboard.ArrowRight))
          .andThen(h.key(Keyboard.ArrowLeft))
          .andThen(h.key(Keyboard.Enter)),
      )
      assert(words(s).contains("volume 55,"), s.text)
    }

    test("the checkboxes: Space toggles news and updates") {
      // Enter on a checkbox toggles it too, so the submit is an Enter in the
      // name field (Shift-Tab goes back to it)
      val s = after(4)(h =>
        h.key(Keyboard.Space)
          .andThen(h.key(Keyboard.Tab))
          .andThen(h.key(Keyboard.Space))
          .andThen(
            Kyo.foreach(1 to 5)(_ =>
              h.key(Keyboard.Tab, Modifiers(shift = true)),
            ),
          )
          .andThen(h.key(Keyboard.Enter)),
      )
      assert(line(s, "Mail").contains("[ ] news"), s.text)
      assert(line(s, "Mail").contains("[x] updates"), s.text)
      assert(words(s).contains("no news, updates,"), s.text)
    }

    test("a radio group: Space on Pro checks it and clears Free") {
      val s = after(7)(h => h.key(Keyboard.Space).andThen(h.key(Keyboard.Enter)))
      assert(line(s, "Plan").contains("( ) Free"), s.text)
      assert(line(s, "Plan").contains("(•) Pro"), s.text)
      assert(words(s).contains(", pro,"), s.text)
    }

    test("a select and a dropdown: ArrowDown picks the next option; Enter on them does not submit") {
      val s = after(8)(h =>
        h.key(Keyboard.ArrowDown)
          .andThen(h.key(Keyboard.Enter))
          .andThen(h.key(Keyboard.Tab))
          .andThen(h.key(Keyboard.ArrowDown)),
      )
      // Enter on the select went to the next option (blue, green, blue)
      assert(line(s, "Colour").contains("Blue"), s.text)
      assert(line(s, "Size").contains("Large"), s.text)
      assert(s.contains("Fill the form, then press Enter."), s.text)
    }

    test("the textarea: Alt-Enter and Ctrl-J give new lines; Enter submits the form") {
      val s = after(10)(h =>
        h.typeText("hi")
          .andThen(h.key(Keyboard.Enter, Modifiers(alt = true)))
          .andThen(h.typeText("yo"))
          .andThen(h.key(Keyboard.Char('j'), Modifiers(ctrl = true)))
          .andThen(h.typeText("ok"))
          .andThen(h.key(Keyboard.Enter)),
      )
      val rows = s.text.linesIterator.toVector
      val at   = rows.indexWhere(_.contains("Message"))
      assert(
        rows.slice(at, at + 3).map(_.trim.split(" ").last) ==
          Vector("hi", "yo", "ok"),
        s.text,
      )
      assert(words(s).contains("size m, 3 message lines"), s.text)
    }

    test("a read-only field takes the focus and the caret, and no edit") {
      val s = after(11)(h =>
        h.typeText("x")
          .andThen(h.key(Keyboard.Backspace))
          .andThen(h.paste("y")),
      )
      assert(line(s, "Account").contains("ada-01"), s.text)
      assert(!line(s, "Account").contains("x"), s.text)
      assert(!line(s, "Account").contains("y"), s.text)
      assert(
        s.cursor.exists(
          _._2 == s.text.linesIterator.indexWhere(_.contains("Account")),
        ),
        s"${s.cursor}",
      )
    }

    test(
      "Tab skips the disabled field; Reset gives every field its first value and does not submit",
    ) {
      val s = run {
        for
            h <- start
            _ <- h.typeText("Bob")
            _ <- tabs(h, 4)
            _ <- h.key(Keyboard.Space)   // no news
            _ <- tabs(h, 4)              // the colour
            _ <- h.key(Keyboard.ArrowDown)
            _ <- tabs(h, 3)              // the read-only Account field
            _ <- h.key(Keyboard.Tab)     // Team is disabled: Submit
            _ <- h.key(Keyboard.Tab)     // Reset
            s <- h.screen
            _ <- h.key(Keyboard.Enter)
            r <- h.screen
        yield (s, r)
      }
      val (before, screen) = s
      // Reset has the focus: the terminal cursor is hidden on a button
      assert(before.cursor.isEmpty, s"${before.cursor}")
      assert(screen.contains("The form has its first values again."), screen.text)
      assert(!line(screen, "Name").contains("Bob"), screen.text)
      assert(line(screen, "Mail").contains("[x] news"), screen.text)
      assert(line(screen, "Colour").contains("Blue"), screen.text)
    }

    test("Esc quits") {
      val ended = run {
        for
            h <- start
            _ <- h.key(Keyboard.Escape)
            e <- h.awaitEnd(10.seconds)
        yield e
      }
      assert(ended, "Esc did not end the run")
    }
end FormsSuite
