package examples.composables

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless
import tui4s.use.Storage

/** The recipes of the README section "More composables", each on the headless
  * test kit: the screen shows what the Scaladoc of the recipe says. The waits
  * are the kit's, with wide bounds.
  */
// claude 2026-10-19: created (L2a review: an example for each composable)
class RecipesSuite extends FunSuite:

    // claude 2026-10-20: created the region below (L5): the docs page
    // "Testing" shows it, with the helpers run and start (L5 review)
    // region: testing
    /** Runs `v` to its end on the Kyo scheduler (at most 60 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(60.seconds)(v).getOrThrow

    private def start(ui: => UI < (Async & Scope))(using
        Frame,
    ): Headless < (Async & Scope) =
      Headless.start(60, 10)(_ => ui)

    /** Waits until the screen shows `text`, at most 10 s. */
    private def shows(h: Headless, text: String)(using Frame): Boolean < Async =
      h.awaitScreen(_.contains(text), 10.seconds)

    test(
      "search: the results of the quiet query; a new query gives new results",
    ) {
      val ok = run {
        for
            h     <- start(Recipes.search(q => Chunk(s"$q-1", s"$q-2")))
            idle  <- shows(h, "type to search")
            _     <- h.typeText("kyo")
            found <- shows(h, "found: kyo-1, kyo-2")
            _     <- h.key(Keyboard.Backspace)
            again <- shows(h, "found: ky-1, ky-2")
        yield (idle, found, again)
      }
      assertEquals(ok, (true, true, true))
    }

    test("progress: 100 writes in a row; the last value shows") {
      val ok = run {
        for
            done <- Signal.initRef(0)
            h    <- start(Recipes.progress(done))
            _    <- Kyo.foreach(1 to 100)(done.set)
            last <- shows(h, "100 %")
        yield last
      }
      assert(ok)
    }
    // endregion: testing

    test("autosave: the save runs after the edits stop, with the last text") {
      val saved = run {
        for
            saved <- Signal.initRef(Chunk.empty[String])
            h     <- start(Recipes.autosave(t => saved.updateAndGet(_ :+ t).unit))
            _     <- h.typeText("note")
            _     <- h.awaitScreen(
                       s => s.contains("note") && s.contains("saved"),
                       10.seconds,
                     )
            all   <- saved.get
        yield all
      }
      assertEquals(saved.lastOption, Some("note"))
    }

    test(
      "answer: the pieces join, and the status says when the answer is complete",
    ) {
      val ok = run {
        for
            h    <- start(
                      Recipes.answer(
                        Stream
                          .init(Seq("Hel", "lo, ", "Kyo"))
                          .map(t => Async.sleep(5.millis).andThen(t)),
                      ),
                    )
            text <- shows(h, "Hello, Kyo")
            done <- shows(h, "(complete)")
        yield (text, done)
      }
      assertEquals(ok, (true, true))
    }

    test(
      "list: 5 of 100 rows show; the arrows move the selection and the window follows it",
    ) {
      val ok = run {
        for
            items <- Signal.initRef(Chunk.range(0, 100).map(i => s"row $i"))
            h     <- start(Recipes.list(items))
            first <- h.awaitScreen(
                       s =>
                         s.contains("> row 0") && s.contains("row 4") &&
                           !s.contains("row 5"),
                       10.seconds,
                     )
            _     <- Kyo.foreach(1 to 6)(_ => h.key(Keyboard.ArrowDown))
            moved <- h.awaitScreen(
                       s =>
                         s.contains("> row 6") && s.contains("row 2") &&
                           !s.contains("row 1 "),
                       10.seconds,
                     )
            // The selected row keeps its key when a row before it goes.
            _     <- items.updateAndGet(_.drop(1))
            kept  <- shows(h, "> row 6")
        yield (first, moved, kept)
      }
      assertEquals(ok, (true, true, true))
    }

    test("confirm: d asks; n keeps the item; y deletes it") {
      val ok = run {
        for
            items <- Signal.initRef(Chunk("a", "b"))
            h     <- start(Recipes.confirm(items))
            _     <- h.char('d')
            asked <- shows(h, "Delete a? (y/n)")
            _     <- h.char('n')
            kept  <- h.awaitScreen(
                       s => s.contains("a, b") && !s.contains("Delete"),
                       10.seconds,
                     )
            _     <- h.char('d')
            _     <- shows(h, "Delete a? (y/n)")
            _     <- h.char('y')
            gone  <- h.awaitScreen(
                       s => !s.contains("a, b") && s.contains("b"),
                       10.seconds,
                     )
        yield (asked, kept, gone)
      }
      assertEquals(ok, (true, true, true))
    }

    test("setting: t changes it; the next run starts with the stored value") {
      val ok = run {
        for
            storage <- Storage.inMemory
            first   <- Scope.run {
                         for
                             h    <- start(Recipes.setting(storage))
                             _    <- shows(h, "theme: light")
                             _    <- h.char('t')
                             dark <- shows(h, "theme: dark")
                         yield dark
                       }
            again   <-
              Scope.run {
                start(Recipes.setting(storage)).map(shows(_, "theme: dark"))
              }
            stored  <- storage.get("dark")
        yield (first, again, stored)
      }
      assertEquals(ok, (true, true, Present("true")))
    }

    test(
      "process: the output lines and the exit code; a missing program is an error",
    ) {
      val ok = run {
        for
            h       <- start(Recipes.process(Chunk("sh", "-c", "echo one; echo two")))
            lines   <- shows(h, "one | two")
            exit    <- shows(h, "exit 0")
            missing <- start(Recipes.process(Chunk("tui4s-no-such-program")))
            error   <- shows(missing, "cannot start")
        yield (lines, exit, error)
      }
      assertEquals(ok, (true, true, true))
    }
end RecipesSuite
