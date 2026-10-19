package examples.gallery

import examples.gallery.Gallery.Family
import kyo.*
import kyo.UI.*
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}
import tui4s.widgets.Colors

/** The gallery on the headless test kit: one leaf for each widget. A leaf
  * starts the gallery, shows a family (its F key), goes to the widget with Tab
  * (the family list has the focus first), presses the keys of the widget and
  * reads the screen.
  */
// claude 2026-10-19: created (L2b)
class GallerySuite extends FunSuite:

    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    /** The gallery on a 100 x 30 screen, at `family`, after `tabs` Tabs. */
    private def at(family: Family, tabs: Int)(using
        Frame,
    ): Headless < (Async & Scope) =
      for
          h <- Headless.start(100, 30, stylesheet = Colors.defaults)(_ =>
                 Gallery.app,
               )
          _ <- h.key(Gallery.familyKeys(family.ordinal))
          _ <- Kyo.foreach(Chunk.fill(tabs)(()))(_ => h.key(Keyboard.Tab))
      yield h

    /** The first row of `s` with `text`, or "". */
    private def rowWith(s: Screen, text: String): String =
      s.rows.find(_.contains(text)).getOrElse("")

    /** Runs `keys` on the gallery at `family` after `tabs` Tabs, and returns
      * the screen.
      */
    private def screen(family: Family, tabs: Int)(
        keys: Headless => Any < Async,
    )(using Frame): Screen =
      run(at(family, tabs).map(h => keys(h).andThen(h.screen)))

    private def has(s: Screen, texts: String*): Unit =
      texts.foreach(t => assert(s.contains(t), s"no '$t' in\n${s.text}"))

    test("the family list: ArrowDown shows the data family") {
      has(
        screen(Family.Containers, 0)(_.key(Keyboard.ArrowDown)),
        "› Data",
        "ListView (",
      )
    }

    // ---- containers

    test("Panel: the title is on the top border") {
      val s = screen(Family.Containers, 0)(_ => ())
      assert(rowWith(s, "Panel ─").contains("╭"), s.text)
      has(s, "A border style and a title on it.")
    }

    test("SplitPane: ArrowRight on the divider widens the first pane") {
      val (before, after) = run {
        for
            h <- at(Family.Containers, 1)
            a <- h.screen
            _ <- h.key(Keyboard.ArrowRight)
            _ <- h.key(Keyboard.ArrowRight)
            b <- h.screen
        yield (a.find("Right").map(_._1), b.find("Right").map(_._1))
      }
      assert(
        before.nonEmpty && after.exists(_ > before.get),
        s"$before -> $after",
      )
    }

    test("Accordion: Enter on a header opens its section") {
      val s = screen(Family.Containers, 2)(_.key(Keyboard.Enter))
      has(s, "▾ General", "Name and email.")
    }

    test("Tabs: ArrowRight selects the next tab") {
      has(
        screen(Family.Containers, 4)(_.key(Keyboard.ArrowRight)),
        "The panel of tab 2.",
      )
    }

    // ---- data

    test("Sparkline: one block per value") {
      has(screen(Family.Data, 0)(_ => ()), "▂▄▇▅▃")
    }

    test("Gauge: the bar and the percentage") {
      has(screen(Family.Data, 0)(_ => ()), "████░░░░░░  42%")
    }

    test("ProgressBar: the bar and done of the total") {
      has(screen(Family.Data, 0)(_ => ()), "▕███       ▏ 3/10")
    }

    test("BarChart: a row for each bar, with its value") {
      val s = screen(Family.Data, 0)(_ => ())
      assert(
        rowWith(s, "Tue").trim.endsWith("7.5") && rowWith(s, "Tue").contains(
          "████",
        ),
        s.text,
      )
    }

    test("Spinner: the frame changes on the clock") {
      val (first, changed) = run {
        for
            h <- at(Family.Data, 0)
            a <- h.screen
            c <- h.awaitScreen(
                   s => rowWith(s, "working") != rowWith(a, "working"),
                   5.seconds,
                 )
        yield (rowWith(a, "working"), c)
      }
      assert(first.nonEmpty && changed, first)
    }

    test("ListView: ArrowDown moves the selection") {
      val s = screen(Family.Data, 1)(h =>
        h.key(Keyboard.ArrowDown).andThen(h.key(Keyboard.ArrowDown)),
      )
      has(s, "› cherry")
    }

    test("DataTable: the key 2 sorts by the year") {
      val s = screen(Family.Data, 2)(_.char('2'))
      has(s, "Year ▲")
      assert(s.find("Haskell").get._2 < s.find("OCaml").get._2, s.text)
    }

    test("TreeView: ArrowRight opens a node") {
      has(
        screen(Family.Data, 3)(_.key(Keyboard.ArrowRight)),
        "▾ src",
        "Main.scala",
      )
    }

    test("Pager: End shows the last lines") {
      has(
        screen(Family.Data, 4)(_.key(Keyboard.End)),
        "event 40: served",
        "lines 38-40 of 40",
      )
    }

    // ---- input

    test("SearchBox: the query follows the text after a pause") {
      val ok = run {
        for
            h  <- at(Family.Input, 1)
            _  <- h.typeText("kyo")
            ok <- h.awaitScreen(_.contains("query: kyo"), 5.seconds)
        yield ok
      }
      assert(ok)
    }

    test("Autocomplete: typing shows a suggestion; Enter picks it") {
      val (open, picked) = run {
        for
            h <- at(Family.Input, 2)
            _ <- h.typeText("sc")
            a <- h.screen
            _ <- h.key(Keyboard.Enter)
            b <- h.screen
        yield (a, b)
      }
      has(open, "› Scala")
      assert(
        !picked.contains("› Scala") && rowWith(picked, "Scala").trim
          .endsWith("│ Scala"),
        picked.text,
      )
    }

    test("Checklist: Space checks the selected item") {
      has(
        screen(Family.Input, 3)(_.key(Keyboard.Space)),
        "› [x] write the widgets",
      )
    }

    test("RadioGroup: ArrowDown chooses the next option") {
      has(screen(Family.Input, 4)(_.key(Keyboard.ArrowDown)), "(•) Medium")
    }

    test("NumberStepper: ArrowUp adds one step") {
      has(screen(Family.Input, 5)(_.key(Keyboard.ArrowUp)), "◂ 4 ▸")
    }

    test("DateField: a day past the month is a problem") {
      has(
        screen(Family.Input, 6)(_.typeText("2024-02-30")),
        "✗ day 30 is not 1 to 29",
      )
    }

    // ---- feedback

    test("StatusBar: the left text and the right slot") {
      val s = screen(Family.Feedback, 0)(_ => ())
      assert(
        rowWith(s, "gallery.scala  120 lines").trim.endsWith("UTF-8"),
        s.text,
      )
    }

    test("KeyHints: the hints of a widget, and of the keymap of the app") {
      has(
        screen(Family.Feedback, 0)(_ => ()),
        "↑/k up",
        "F1 containers",
        "Ctrl-P palette",
      )
    }

    test("CommandPalette: Ctrl-P opens it; a search and Enter run a command") {
      val (open, done) = run {
        for
            h <- at(Family.Feedback, 0)
            _ <- h.key(Keyboard.Char('p'), Modifiers(ctrl = true))
            a <- h.screen
            _ <- h.typeText("data")
            _ <- h.key(Keyboard.Enter)
            b <- h.screen
        yield (a, b)
      }
      has(open, "Commands", "› containers")
      has(done, "ListView (")
      assert(!done.contains("Commands"), done.text)
    }

    test("Modal.confirm: Enter on Delete asks; y answers") {
      val (asked, answered) = run {
        for
            h <- at(Family.Feedback, 2)
            _ <- h.key(Keyboard.Enter)
            _ <- h.awaitScreen(_.contains("Delete the file?"), 5.seconds)
            a <- h.screen
            _ <- h.char('y')
            _ <- h.awaitScreen(_.contains("deleted"), 5.seconds)
            b <- h.screen
        yield (a, b)
      }
      has(asked, "Delete the file?", "[ Yes ]")
      assert(
        answered.contains("deleted") && !answered.contains("Delete the file?"),
        answered.text,
      )
    }

    test("Modal.prompt: a name and Enter answer it") {
      val ok = run {
        for
            h  <- at(Family.Feedback, 3)
            _  <- h.key(Keyboard.Enter)
            _  <- h.awaitScreen(_.contains("New name?"), 5.seconds)
            _  <- h.typeText("todo.md")
            _  <- h.key(Keyboard.Enter)
            ok <- h.awaitScreen(_.contains("name: todo.md"), 5.seconds)
        yield ok
      }
      assert(ok)
    }

    test("Toasts: a toast shows, then goes away") {
      val (shown, gone) = run {
        for
            h <- at(Family.Feedback, 4)
            _ <- h.key(Keyboard.Enter)
            a <- h.awaitScreen(_.contains("✓ Saved"), 5.seconds)
            b <- h.awaitScreen(!_.contains("✓ Saved"), 5.seconds)
        yield (a, b)
      }
      assert(shown && gone)
    }

    // ---- navigation

    test("Menu: ArrowDown opens the File menu; Enter runs an item") {
      val (open, done) = run {
        for
            h <- at(Family.Navigation, 1)
            _ <- h.key(Keyboard.ArrowDown)
            a <- h.screen
            _ <- h.key(Keyboard.ArrowDown)
            _ <- h.key(Keyboard.Enter)
            b <- h.screen
        yield (a, b)
      }
      has(open, "› New", "Ctrl-N", "Open")
      has(done, "File › Open")
    }

    test("Tooltip: the text shows while the button has the focus") {
      has(screen(Family.Navigation, 3)(_ => ()), "Saves the file")
    }

    test("Breadcrumbs: Enter on a part goes back to it") {
      val s = screen(Family.Navigation, 4)(_.key(Keyboard.Enter))
      assert(
        !s.contains("[ docs ]") && rowWith(s, "home").trim == "home",
        s.text,
      )
    }

    test("Pagination: ArrowRight goes to the next page") {
      has(
        screen(Family.Navigation, 6)(_.key(Keyboard.ArrowRight)),
        "page 2 of 12",
        "[2]",
      )
    }

    test("Tags: a text and Enter add a tag") {
      has(
        screen(Family.Navigation, 9)(h =>
          h.typeText("zio").andThen(h.key(Keyboard.Enter)),
        ),
        "zio ×",
      )
    }

    test("Wizard: Next goes to the next step") {
      has(
        screen(Family.Navigation, 10)(_.key(Keyboard.Enter)),
        "● Profile",
        "Add a photo.",
      )
    }
end GallerySuite
