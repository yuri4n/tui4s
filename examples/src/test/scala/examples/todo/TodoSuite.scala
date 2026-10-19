package examples.todo

import kyo.*
import kyo.UI.{Keyboard, Modifiers}
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The Todo app on the headless test kit, on a file in a temporary directory:
  * the load, the keys of the input and of the list, the save, a paste.
  */
// claude 2026-10-13: created (T2, RC-33; the test ideas of PR 5's TodoSuite)
class TodoSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    /** A path in a new temporary directory, with `lines` in it (no file when
      * `lines` is empty).
      */
    private def file(lines: String*): Path =
        val p =
          Files.createTempDirectory("tui4s-todo").nn.resolve("todo.txt").nn
        if lines.nonEmpty then
            val _ = Files.writeString(
              p,
              lines.mkString("", "\n", "\n"),
              StandardCharsets.UTF_8,
            )
        p
    end file

    /** Leave room for the full status path, also in macOS temporary dirs. */
    private def start(path: Path)(using Frame): Headless < (Async & Scope) =
        val width = 90.max(path.toString.length + 40)
        Headless.start(width, 16)(_ => Todo.app(path.toString))

    /** The row of the input. */
    private def input(s: Screen): String =
      s.text.linesIterator.find(_.contains("New:")).getOrElse("")

    test("the start loads the file: the items, the count, the status") {
      val path = file("[ ] buy milk", "[x] call bob")
      val s    = run(start(path).map(_.screen))
      assert(s.contains("buy milk") && s.contains("call bob"), s.text)
      assert(s.contains("1 open / 2"), s.text)
      assert(s.contains(s"loaded 2 items from $path"), s.text)
    }

    test("a file that cannot be read: the status says so, the list is empty") {
      val dir = Files.createTempDirectory("tui4s-todo").nn
      val s   = run(start(dir).map(_.screen))
      assert(s.contains(s"could not load $dir"), s.text)
      assert(s.contains("0 open / 0"), s.text)
    }

    test("type a text, Enter adds it, and the input is empty again") {
      val s = run {
        for
            h <- start(file())
            _ <- h.typeText("buy milk")
            _ <- h.key(Keyboard.Enter)
            s <- h.screen
        yield s
      }
      assert(s.contains("[ ] buy milk"), s.text)
      assert(s.contains("1 open / 1"), s.text)
      assert(!input(s).contains("buy milk"), input(s))
    }

    test("d and q in the input are text, and the run goes on") {
      val (s, ended) = run {
        for
            h     <- start(file())
            _     <- h.typeText("dq")
            s     <- h.screen
            ended <- h.finished
        yield (s, ended)
      }
      assert(input(s).contains("dq"), s.text)
      assert(!ended, "q in the input ended the run")
    }

    test("Tab moves to the list; Space toggles; the arrow moves; d deletes") {
      val (toggled, deleted) = run {
        for
            h       <- start(file("[ ] buy milk", "[ ] call bob"))
            _       <- h.key(Keyboard.Tab)
            _       <- h.key(Keyboard.Space)
            toggled <- h.screen
            _       <- h.key(Keyboard.ArrowDown)
            _       <- h.char('d')
            deleted <- h.screen
        yield (toggled, deleted)
      }
      assert(toggled.contains("[x] buy milk"), toggled.text)
      assert(toggled.contains("1 open / 2"), toggled.text)
      assert(!deleted.contains("call bob"), deleted.text)
      assert(deleted.contains("0 open / 1"), deleted.text)
    }

    test("Ctrl-S writes the list to the file") {
      val path = file()
      val s    = run {
        for
            h <- start(path)
            _ <- h.typeText("tea")
            _ <- h.key(Keyboard.Enter)
            _ <- h.typeText("cake")
            _ <- h.key(Keyboard.Enter)
            _ <- h.key(Keyboard.Tab)
            _ <- h.key(Keyboard.Space)
            _ <- h.key(Keyboard.Char('s'), Modifiers(ctrl = true))
            s <- h.screen
        yield s
      }
      assert(s.contains(s"saved 2 items to $path"), s.text)
      assertEquals(
        Files.readString(path, StandardCharsets.UTF_8),
        "[x] tea\n[ ] cake\n",
      )
    }

    test(
      "a paste is one edit; a line break in it is a space and adds nothing",
    ) {
      val (one, frames, twoLines, added) = run {
        for
            h        <- start(file())
            frames   <- h.paste("tea time")
            one      <- h.screen
            _        <- h.paste("\nand cake")
            twoLines <- h.screen
            _        <- h.key(Keyboard.Enter)
            added    <- h.screen
        yield (one, frames, twoLines, added)
      }
      assertEquals(frames.size, 1, "a paste is one event, one frame")
      assert(input(one).contains("tea time"), one.text)
      assert(input(twoLines).contains("tea time and cake"), twoLines.text)
      assert(twoLines.contains("0 open / 0"), twoLines.text)
      assert(added.contains("[ ] tea time and cake"), added.text)
    }

    test("Esc ends the run") {
      val ended = run {
        for
            h     <- start(file())
            _     <- h.key(Keyboard.Escape)
            ended <- h.awaitEnd(10.seconds)
        yield ended
      }
      assert(ended, "Esc did not end the run")
    }

    /** The numbers of the rows "item <n>" that `s` shows, top first. */
    // claude 2026-10-19: created (L1a)
    private def items(s: Screen): Vector[Int] =
      """\[.\] item (\d+)""".r
        .findAllMatchIn(s.text)
        .map(_.group(1).nn.toInt)
        .toVector

    // claude 2026-10-19: created (L1a)
    test(
      "a list longer than the screen: each add keeps the input in view with the newest items above it; Tab to the list and the arrows keep the selected row in view; a long title ends with …",
    ) {
      val (added, top, bottom, long) = run {
        for
            h      <- Headless.start(50, 12)(_ => Todo.app(file().toString))
            _      <- Kyo.foreach(1 to 12)(i =>
                        h.typeText(s"item $i").andThen(h.key(Keyboard.Enter)),
                      )
            added  <- h.screen
            _      <- h.key(Keyboard.Tab)
            top    <- h.screen
            _      <- Kyo.foreach(1 to 11)(_ => h.key(Keyboard.ArrowDown))
            bottom <- h.screen
            // Shift-Tab: from the list back to the input (Tab would go to
            // the check box of the first row)
            _      <- h.key(Keyboard.Tab, Modifiers(shift = true))
            _      <-
              h.typeText(
                "a very long title that is wider than the list on this screen",
              )
            _      <- h.key(Keyboard.Enter)
            long   <- h.screen
        yield (added, top, bottom, long)
      }
      // the body has the 9 rows between the header and the footer: the
      // newest items, the bottom border of the list, the input
      assert(items(added) == (6 to 12).toVector, added.text)
      assert(added.contains("New:") && added.contains("█"), added.text)
      // the list has the focus; its selected row is the first one
      assert(items(top) == (1 to 9).toVector, top.text)
      // the selection went down to the last row; the list followed it
      assert(items(bottom) == (4 to 12).toVector, bottom.text)
      val title                      = long.text.linesIterator.find(_.contains("a very long"))
      assert(
        title.exists(l => l.contains("…") && !l.contains("screen")),
        long.text,
      )
    }
end TodoSuite
