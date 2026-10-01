package tui4s

import kyo.test.*
import tui4s.testkit.*

class ScreenTest extends TuiSuite:
    "a wide character takes two cells and one place in its row" in {
      val s   = render(text("✔ call bob"), 20, 1)
      assert(s.contains("✔ call bob") && s.row(0) == "✔ call bob")
      val cjk = render(text("a日本b"), 10, 1)
      assert(cjk.row(0) == "a日本b")
      assert(cjk.find("本") == Some((3, 0)))
      assert(cjk.find("b") == Some((5, 0)))
      assert(cjk.region(1, 0, 4, 1) == Vector("日本"))
      assert(cjk.region(2, 0, 4, 1) == Vector(" 本b")) // 日 cut by the edge
      assert(cjk.find("zz").isEmpty)
    }

    "styles read unset colors as unset" in {
      val s = render(text("x".bold.yellow, "y"), 5, 1)
      assert(s.styleAt(0, 0) == Style.bold.fg(Color.Yellow))
      assert(s.styleAt(1, 0) == Style.default)
      assert(s.styleAt(4, 0) == Style.default)
    }

    "text, rows, toString, region" in {
      val s = render(paragraph("ab", "cd"), 4, 3)
      assert(s.rows == Vector("ab", "cd", ""))
      assert(s.text == "ab\ncd\n" && s.toString == s.text)
      assert(s.region(1, 0, 2, 2) == Vector("b", "d"))
      assert(s.cursor.isEmpty)
    }

    "diffScreen: None when equal, rows otherwise" in {
      val s    = render(paragraph("ab", "cd"), 4, 3)
      assert(diffScreen(s, "ab  \ncd\n\n").isEmpty)
      val diff = diffScreen(s, "ab\nce")
      assert(
        diff.exists(d =>
          d.contains("row 1") && d.contains("|cd|") && d.contains("|ce|"),
        ),
      )
      assert(!diff.exists(_.contains("row 0")))
      assert(diffScreen(s, "ab").exists(_.contains("row 1")))
    }

    "assertScreen throws an AssertionError with the diff" in {
      val s = render(text("ab"), 4, 1)
      assertScreen(s, "ab")
      val e = intercept[AssertionError](assertScreen(s, "xy"))
      assert(
        e.getMessage.contains("screen differs") && e.getMessage.contains("|xy|"),
      )
    }
end ScreenTest
