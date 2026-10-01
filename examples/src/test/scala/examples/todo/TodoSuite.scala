package examples.todo

import kyo.Result
import munit.FunSuite
import tui4s.*
import tui4s.testkit.*

class TodoSuite extends FunSuite:
    /** 80 x 24: at 48 columns the help bar has no room for "d delete". */
    private def loaded(items: Item*): TuiTest[Todo] =
      TuiTest(TodoApp).emit("load", Result.succeed(items.toVector))

    test("loads at start, then add, toggle, delete") {
      val t = TuiTest(TodoApp, 48, 12)
      assertEquals(t.sources, Seq("load"))
      t.emit("load", Result.succeed(Vector(Item("buy milk"))))
      assert(t.sources.isEmpty && t.state.items.size == 1)
      t.typeText("call bob")
      t.press(Key.Enter, Key.Tab, ' ')
      assertEquals(t.state.items.map(_.done), Vector(false, true))
      assert(t.screen.contains("✔ call bob"), t.screen.text)
      t.press('d')
      assertEquals(t.state.items.map(_.title), Vector("buy milk"))
    }

    test("'d' and 'q' in the text field are text") {
      val t = loaded().typeText("dq")
      assert(!t.quit)
      assertEquals(t.state.field.text, "dq")
      assert(t.cursor.isDefined)
    }

    test("Ctrl-S starts one save task and folds its result") {
      val t = loaded().press(Key.Ctrl('s'))
      assertEquals(t.started, Seq("save"))
      assertEquals(t.state.status, "saving…")
      t.emit("save", Result.succeed(()))
      assertEquals(t.state.status, "saved")
    }

    test("the help bar shows the keys of the focused pane") {
      val t = loaded()
      assert(t.screen.contains("enter add") && !t.screen.contains("d delete"))
      t.press(Key.Tab)
      assert(t.screen.contains("d delete"), t.screen.text)
      assertEquals(TodoApp.itemKeys.explain(Key.Char('d')), "'d' -> delete")
    }

    test("a failed load says so and leaves the list empty") {
      val t = TuiTest(TodoApp, 48, 12)
      t.emit("load", Result.fail(java.io.IOException("disk gone")))
      assert(t.state.ready && t.state.items.isEmpty)
      assert(t.state.status.startsWith("could not load"), t.state.status)
    }
end TodoSuite
