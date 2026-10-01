package tui4s

import kyo.test.*
import scala.language.strictEquality
import tui4s.testkit.*

/** Users who compile with `-language:strictEquality` can compare the model, the
  * view and the screen with `==`. This file does not compile when one of these
  * types has no `CanEqual`.
  */
class StrictEqualityTest extends TuiSuite:
    "styles, colors, lines, spans, views and focus compare with ==" in {
      val screen = render(text("a".bold.yellow), 3, 1)
      assert(screen.styleAt(0, 0) == Style.bold.fg(Color.Yellow))
      assert(
        Color.Red != Color.Blue && Color.rgb(1, 2, 3) == Color.rgb(1, 2, 3),
      )
      assert(line("a", "b".red) == Line("a", "b".red))
      assert("a".bold == "a".bold && "a".bold != "a".dim)
      assert(text("a") == text("a") && column(text("a")) != row(text("a")))
      assert(list(Seq("a"), 0).bordered("t") == list(Seq("a"), 0).bordered("t"))
      assert(Focus(1, 2).next == Focus(1, 2).to(2))
      assert(TuiTest(still(text("a")), 3, 1).view == text("a"))
    }
end StrictEqualityTest
