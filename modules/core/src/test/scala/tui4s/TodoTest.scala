package tui4s

import kyo.*
import kyo.test.*
import tui4s.fixtures.*
import tui4s.testkit.*

/** SPEC 6.3 TodoTest: load at start, edit, save. */
class TodoTest extends TuiSuite:
    private def loaded(items: Item*): TuiTest[Todo] =
        val t = TuiTest(TodoApp, 48, 12)
        t.emit("load", Result.succeed(items.toVector))
        t

    "loads at start, then add, toggle, delete" in {
      val t = TuiTest(TodoApp, 48, 12)
      assert(t.sources == Seq("load"))
      t.emit("load", Result.succeed(Vector(Item("buy milk"))))
      assert(t.sources.isEmpty && t.state.items.size == 1)
      t.typeText("call bob")
      t.press(Key.Enter, Key.Tab, ' ')
      assert(t.state.items.map(_.done) == Vector(false, true))
      assert(t.screen.contains("✔ call bob"))
      assert(t.screen.contains("Todo · 1 left"))
      t.press('d')
      assert(t.state.items.map(_.title) == Vector("buy milk"))
    }

    "a failed load shows in the status" in {
      val t = TuiTest(TodoApp, 48, 12)
      t.emit("load", Result.fail(java.io.IOException("disk")))
      assert(t.state.ready && t.state.status.startsWith("could not load"))
    }

    "'d' and 'q' in the text field are text" in {
      val t = loaded()
      t.typeText("dq")
      assert(!t.quit && t.state.field.text == "dq")
      assert(t.cursor == Some((3, 1))) // inside the border, after "dq"
    }

    "Ctrl-S starts one save task and folds its result" in {
      val t = loaded()
      t.press(Key.Ctrl('s'))
      assert(t.started == Seq("save") && t.state.status == "saving…")
      t.emit("save", Result.succeed(()))
      assert(t.state.status == "saved" && t.stats.tasks == 0)
    }

    "a second Ctrl-S replaces a running save (latest wins)" in {
      val t = loaded(Item("a"))
      t.press(Key.Ctrl('s'), Key.Ctrl('s'))
      assert(t.started == Seq("save", "save") && t.stats.tasks == 1)
      t.emit("save", Result.succeed(()))
      assert(t.state.status == "saved" && t.stats.tasks == 0)
    }

    "the help bar shows the keys of the focused pane" in {
      val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item]))
      assert(t.screen.contains("enter add") && !t.screen.contains("d delete"))
      t.press(Key.Tab)
      assert(t.screen.contains("d delete"))
      assert(TodoApp.itemKeys.explain(Key.Char('d')) == "'d' -> delete")
      assert(t.cursor.isEmpty) // the input has no focus
    }

    "the placeholder shows while the field is empty" in {
      val t = loaded()
      assert(t.screen.contains("What needs doing?"))
      t.typeText("x")
      assert(!t.screen.contains("What needs doing?"))
    }

    "an emoji typed into the field arrives as a paste of one grapheme" in {
      val t = loaded()
      t.typeText("ok 👍🏽")
      assert(t.state.field.text == "ok 👍🏽" && t.state.field.cursor == 4)
    }

    "the focused pane has the accent border" in {
      val t = loaded()
      assert(t.screen.styleAt(0, 0) == Style.fg(Color.Cyan))
      assert(t.screen.styleAt(0, 3) == Style.default)
      t.press(Key.Tab)
      assert(t.screen.styleAt(0, 0) == Style.default)
      assert(t.screen.styleAt(0, 3) == Style.fg(Color.Cyan))
    }
end TodoTest
