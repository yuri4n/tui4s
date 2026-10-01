package tui4s

import kyo.test.*
import tui4s.fixtures.*
import tui4s.testkit.*

/** SPEC 6.3 TodoTest, the parts without the load and save sources. */
class TodoTest extends TuiSuite:
    "add, toggle, delete" in {
      val t = TuiTest(TodoApp, 48, 12)
      t.typeText("buy milk")
      t.press(Key.Enter)
      t.typeText("call bob")
      t.press(Key.Enter, Key.Tab, ' ')
      assert(t.state.items.map(_.done) == Vector(false, true))
      assert(t.screen.contains("✔ call bob"))
      assert(t.screen.contains("Todo · 1 left"))
      t.press('d')
      assert(t.state.items.map(_.title) == Vector("buy milk"))
    }

    "'d' and 'q' in the text field are text" in {
      val t = TuiTest(TodoApp)
      t.typeText("dq")
      assert(!t.quit && t.state.field.text == "dq")
      assert(t.cursor == Some((3, 1))) // inside the border, after "dq"
    }

    "the help bar shows the keys of the focused pane" in {
      val t = TuiTest(TodoApp)
      assert(t.screen.contains("enter add") && !t.screen.contains("d delete"))
      t.press(Key.Tab)
      assert(t.screen.contains("d delete"))
      assert(TodoApp.itemKeys.explain(Key.Char('d')) == "'d' -> delete")
      assert(t.cursor.isEmpty) // the input has no focus
    }

    "the placeholder shows while the field is empty" in {
      val t = TuiTest(TodoApp, 48, 12)
      assert(t.screen.contains("What needs doing?"))
      t.typeText("x")
      assert(!t.screen.contains("What needs doing?"))
    }

    "an emoji typed into the field arrives as a paste of one grapheme" in {
      val t = TuiTest(TodoApp)
      t.typeText("ok 👍🏽")
      assert(t.state.field.text == "ok 👍🏽" && t.state.field.cursor == 4)
    }

    "the focused pane has the accent border" in {
      val t = TuiTest(TodoApp, 48, 12)
      assert(t.screen.styleAt(0, 0) == Style.fg(Color.Cyan))
      assert(t.screen.styleAt(0, 3) == Style.default)
      t.press(Key.Tab)
      assert(t.screen.styleAt(0, 0) == Style.default)
      assert(t.screen.styleAt(0, 3) == Style.fg(Color.Cyan))
    }
end TodoTest
