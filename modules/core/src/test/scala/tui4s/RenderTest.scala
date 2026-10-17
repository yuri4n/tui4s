package tui4s

import kyo.*
import kyo.UI.*

/** The render helper: a kyo-ui value becomes cells. */
// claude 2026-10-12: created
class RenderTest extends ScreenSuite:

    "a text in a div fills the first row" in {
      assertScreen(div("hello"), 10, 2, "hello")
    }

    "the screen has the given size" in {
      val rows = screen(div("x"), 7, 3)
      assert(rows.size == 3)
      assert(rows.head == "x")
    }
end RenderTest
