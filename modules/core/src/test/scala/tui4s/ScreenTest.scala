package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.Render
import tui4s.testkit.*

/** `testkit.Screen` and `CellStyle`: rows, wide characters, styles, the diff. */
// claude 2026-10-12: created (RC-28; the cases of the old PR 3 ScreenTest)
class ScreenTest extends Test[Any]:

    private def render(ui: UI, w: Int, h: Int): Screen =
      Screen.of(Render.buffer(ui, w, h), None)

    "a wide character takes two cells and one place in its row" in {
      val s   = render(div("✔ call bob"), 20, 1)
      assert(s.contains("✔ call bob") && s.row(0) == "✔ call bob")
      val cjk = render(div("a日本b"), 10, 1)
      assert(cjk.row(0) == "a日本b")
      assert(cjk.find("本") == Some((3, 0)))
      assert(cjk.find("b") == Some((5, 0)))
      assert(cjk.region(1, 0, 4, 1) == Vector("日本"))
      assert(cjk.region(2, 0, 4, 1) == Vector(" 本b")) // 日 cut by the edge
      assert(cjk.find("zz").isEmpty)
    }

    // claude 2026-10-19: created (P1b, A5 d)
    "CellColor: RGB, a palette index, the 16 names, and Reset as unset" in {
      import jatatui.core.style.Color as JColor
      assert(
        CellColor
          .of(JColor.Rgb(99, 102, 241))
          .contains(CellColor.Rgb(99, 102, 241)),
      )
      assert(CellColor.of(JColor.Indexed(42)).contains(CellColor.Indexed(42)))
      assert(CellColor.of(JColor.LIGHT_BLUE).contains(CellColor.LightBlue))
      assert(CellColor.of(JColor.DARK_GRAY).contains(CellColor.DarkGray))
      assert(CellColor.of(JColor.RESET).isEmpty)
      val named = Seq(
        JColor.BLACK,
        JColor.RED,
        JColor.GREEN,
        JColor.YELLOW,
        JColor.BLUE,
        JColor.MAGENTA,
        JColor.CYAN,
        JColor.GRAY,
        JColor.DARK_GRAY,
        JColor.LIGHT_RED,
        JColor.LIGHT_GREEN,
        JColor.LIGHT_YELLOW,
        JColor.LIGHT_BLUE,
        JColor.LIGHT_MAGENTA,
        JColor.LIGHT_CYAN,
        JColor.WHITE,
      ).flatMap(CellColor.of)
      assert(named.distinct.size == 16)
    }

    "styles: a bold coloured cell, and unset colours read as unset" in {
      val ui =
        div.style(_.row)(span("x").style(_.bold.color(Style.Color.yellow)), "y")
      val s  = render(ui, 5, 1)
      val x  = s.styleAt(0, 0)
      assert(
        x.bold && x.fg.exists {
          case CellColor.Rgb(_, _, _) => true
          case _                      => false
        } && x.bg.isEmpty,
        x.toString,
      )
      assert(x == CellStyle(fg = x.fg, bold = true))
      assert(s.styleAt(1, 0) == CellStyle.plain)
      assert(s.styleAt(4, 0) == CellStyle.plain)
    }

    "text, rows, toString, region, cursor" in {
      val s = render(div(p("ab"), p("cd")), 4, 3)
      assert(s.rows == Vector("ab", "cd", ""))
      assert(s.text == "ab\ncd\n" && s.toString == s.text)
      assert(s.region(1, 0, 2, 2) == Vector("b", "d"))
      assert(s.cursor.isEmpty)
    }

    "diffScreen: None when equal, rows otherwise" in {
      val s    = render(div(p("ab"), p("cd")), 4, 3)
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
      val s = render(div("ab"), 4, 1)
      assertScreen(s, "ab")
      val e = intercept[AssertionError](assertScreen(s, "xy"))
      assert(
        e.getMessage.contains("screen differs") && e.getMessage.contains("|xy|"),
      )
    }
end ScreenTest
