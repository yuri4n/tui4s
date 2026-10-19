package examples.forms

import kyo.*
import kyo.UI.{Keyboard, Modifiers}
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}

/** The form on the headless test kit: the first screen, the keys of each input,
  * the submit by Enter, Reset, and Esc.
  */
// claude 2026-10-19: created (L1c)
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

    /** Presses Tab `n` times. */
    private def tabs(h: Headless, n: Int)(using Frame): Unit < Async =
      Kyo.foreach(1 to n)(_ => h.key(Keyboard.Tab)).unit

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

    test(
      "the keys of each input, then Enter in the textarea submits the form",
    ) {
      val s = run {
        for
            h <- start
            _ <- h.typeText("Ada")
            _ <- tabs(h, 1)
            _ <- h.typeText("pw")
            _ <- tabs(h, 1)
            _ <- h.key(Keyboard.ArrowUp)    // age 31
            _ <- tabs(h, 1)
            _ <- h.key(Keyboard.ArrowRight) // volume 55
            _ <- tabs(h, 1)
            _ <- h.key(Keyboard.Space)      // no news
            _ <- tabs(h, 3)                 // updates, Free, Pro
            _ <- h.key(Keyboard.Space)      // the plan Pro
            _ <- tabs(h, 1)
            _ <- h.key(Keyboard.ArrowDown)  // the colour green
            _ <- tabs(h, 1)
            _ <- h.typeText("hi")
            _ <- h.key(Keyboard.Enter, Modifiers(alt = true))
            _ <- h.typeText("yo")
            _ <- h.key(Keyboard.Enter)
            s <- h.screen
        yield s
      }
      assert(
        s.contains(
          "Sent: Ada, 2 secret chars, age 31, volume 55, no news, pro,",
        ),
        s.text,
      )
      assert(s.contains("green, 2 message lines"), s.text)
      assert(line(s, "Secret").contains("••"), s.text)
      assert(!s.contains("pw"), s.text)
      assert(line(s, "Plan").contains("(•) Pro"), s.text)
    }

    test(
      "Tab skips the disabled field; Reset empties the form and does not submit it",
    ) {
      val s               = run {
        for
            h <- start
            _ <- h.typeText("Bob")
            _ <- tabs(h, 10)           // the read-only Account field
            f <- h.key(Keyboard.Tab)   // Team is disabled: Submit
            _ <- h.key(Keyboard.Tab)
            _ <- h.key(Keyboard.Enter) // Reset
            s <- h.screen
        yield (f.focus, s)
      }
      val (focus, screen) = s
      assert(focus.exists(_ != "Team"), s"$focus")
      assert(screen.contains("The form is empty again."), screen.text)
      assert(!line(screen, "Name").contains("Bob"), screen.text)
    }

    test("the name takes at most 20 characters; Esc quits") {
      val (name, ended) = run {
        for
            h <- start
            _ <- h.typeText("abcdefghijklmnopqrstuvwxyz")
            s <- h.screen
            _ <- h.key(Keyboard.Escape)
            e <- h.awaitEnd(10.seconds)
        yield (line(s, "Name"), e)
      }
      // 20 characters in a field of 20 cells: the caret after them takes a
      // cell, so the field shows the last 19
      assert(name.contains("bcdefghijklmnopqrst") && !name.contains("u"), name)
      assert(ended, "Esc did not end the run")
    }
end FormsSuite
