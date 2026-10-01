package tui4s.testkit

import jatatui.core.buffer.Buffer
import jatatui.core.style.Color as JColor
import jatatui.core.style.Style as JStyle
import jatatui.core.text.Span as JSpan
import java.util.Optional
import tui4s.Style
import tui4s.TextField

/** One frame as text plus styles: an immutable copy of a jatatui buffer.
  *
  * Coordinates are cells: `x` is the column and `y` the row, from 0. A wide
  * character (CJK, many emoji) takes two cells but one place in its row string,
  * as a terminal shows it.
  *
  * @param rows
  *   the rows as a terminal shows them, trailing spaces trimmed
  * @param styles
  *   the style of each cell, `styles(y)(x)`. A color that nobody set reads as
  *   unset, so `styleAt(x, y) == Style.bold.fg(Color.Yellow)` holds for a bold
  *   yellow cell.
  * @param cursor
  *   the terminal cursor (column, row), when a focused input shows it
  */
final case class Screen(
    rows: Vector[String],
    styles: Vector[Vector[Style]],
    cursor: Option[(Int, Int)],
) derives CanEqual:
    /** The rows joined with '\n'. */
    def text: String = rows.mkString("\n")

    /** The row `y`, trailing spaces trimmed. */
    def row(y: Int): String = rows(y)

    /** True when one row contains `s`. */
    def contains(s: String): Boolean = rows.exists(_.contains(s))

    /** The cell (column, row) where `s` first appears. */
    def find(s: String): Option[(Int, Int)] =
      rows.iterator.zipWithIndex.collectFirst {
        case (r, y) if r.contains(s) =>
          (Screen.width(r.substring(0, r.indexOf(s))), y)
      }

    /** The cells of a rectangle, one string per row, trailing spaces trimmed. A
      * wide character cut by the edge of the rectangle reads as a space.
      */
    def region(x: Int, y: Int, width: Int, height: Int): Vector[String] =
      rows.slice(y, y + height).map(r => Screen.cells(r, x, width))

    /** The style of the cell (x, y). */
    def styleAt(x: Int, y: Int): Style = styles(y)(x)

    override def toString: String = text
end Screen

/** Builds screens. */
object Screen:
    /** A copy of `buf` with the cursor `cursor`. */
    private[tui4s] def of(buf: Buffer, cursor: Option[(Int, Int)]): Screen =
        val area   = buf.area()
        // The trailing cells of a wide character hold " "; skip them, as
        // jatatui's Buffer.diff does, so that a row reads as the terminal
        // shows it ("✔ call bob", not "✔  call bob").
        val rows   = Vector.tabulate(area.height) { y =>
          val sb   = new java.lang.StringBuilder(area.width)
          var skip = 0
          var x    = 0
          while x < area.width do
              val sym = buf.cellAt(area.x + x, area.y + y).symbol()
              if skip == 0 then
                  val _ = sb.append(sym)
              skip = (skip.max(width(sym)) - 1).max(0)
              x += 1
          trimEnd(sb.toString)
        }
        val styles = Vector.tabulate(area.height, area.width) { (y, x) =>
          normalized(buf.cellAt(area.x + x, area.y + y).style())
        }
        Screen(rows, styles, cursor)
    end of

    /** A cell style with Reset colors read as unset: a cell reports Reset for
      * the colors that nobody set.
      */
    private def normalized(st: JStyle): Style =
        def unset(c: Optional[JColor]): Optional[JColor] =
          c.filter(_ != JColor.RESET)
        Style.fromJ(
          JStyle(
            unset(st.fg()),
            unset(st.bg()),
            unset(st.underlineColor()),
            st.addModifier(),
            st.subModifier(),
          ),
        )
    end normalized

    private def width(s: String): Int = JSpan.raw(s).width()

    private def trimEnd(s: String): String =
        var end = s.length
        while end > 0 && s.charAt(end - 1) == ' ' do end -= 1
        s.substring(0, end)

    /** The cells `x until x + width` of a row string. */
    private def cells(row: String, x: Int, width: Int): String =
        val sb  = new java.lang.StringBuilder(width)
        var col = 0
        TextField.graphemes(row).foreach { g =>
          val w = Screen.width(g).max(1)
          if col >= x && col + w <= x + width then
              val _ = sb.append(g)
          else if col < x + width && col + w > x then
              // a wide character cut by an edge: the visible cells are blank
              var c = col.max(x)
              while c < (col + w).min(x + width) do
                  val _ = sb.append(' ')
                  c += 1
          end if
          col += w
        }
        trimEnd(sb.toString)
    end cells
end Screen

/** `None` when the screen shows `expected`; otherwise a row-by-row diff.
  * Trailing spaces and trailing empty rows are ignored on both sides.
  * Framework-neutral.
  */
def diffScreen(actual: Screen, expected: String): Option[String] =
    def clean(rows: Seq[String]): Vector[String] =
      rows.iterator
        .map(_.replaceAll("\\s+$", ""))
        .toVector
        .reverse
        .dropWhile(_.isEmpty)
        .reverse
    val act                                      = clean(actual.rows)
    val exp                                      = clean(expected.linesIterator.toSeq)
    if act == exp then None
    else
        val rows = (0 until act.size.max(exp.size)).collect {
          case i if act.lift(i) != exp.lift(i) =>
            s"row $i\n  actual:   |${act.lift(i).getOrElse("")}|\n" +
              s"  expected: |${exp.lift(i).getOrElse("")}|"
        }
        Some(rows.mkString("\n"))
    end if
end diffScreen

/** Throws `java.lang.AssertionError` with the diff when the screen does not
  * show `expected` (munit, zio-test, weaver, JUnit). In kyo-test write
  * `assert(diffScreen(t.screen, expected).isEmpty)`: kyo-test counts only its
  * own assertions.
  */
def assertScreen(actual: Screen, expected: String): Unit =
  diffScreen(actual, expected).foreach { diff =>
    throw new AssertionError(
      s"screen differs:\n$diff\n--- actual screen:\n${actual.text}",
    )
  }
