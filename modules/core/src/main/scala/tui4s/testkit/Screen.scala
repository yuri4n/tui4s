package tui4s.testkit

import jatatui.core.backend.TestBackend
import jatatui.core.buffer.Buffer
import jatatui.core.style.{Color as JColor, Modifier, Style as JStyle}
import jatatui.core.text.Span as JSpan

import java.text.BreakIterator

/** The style of one screen cell, in plain Scala types (no jatatui type).
  *
  * A colour is `#rrggbb` (lower case) for an RGB colour, a lower-case name
  * (`red`, `light-blue`, `dark-gray`, ...) for a named ANSI colour, or
  * `indexed:<n>` for a 256-colour index. A colour that nobody set is `None`, so
  * `styleAt(x, y) == CellStyle(bold = true)` holds for a bold cell with no
  * colour.
  */
// claude 2026-10-12: created (D-13, RC-28)
final case class CellStyle(
    fg: Option[String] = None,
    bg: Option[String] = None,
    bold: Boolean = false,
    dim: Boolean = false,
    italic: Boolean = false,
    underlined: Boolean = false,
    reversed: Boolean = false,
    crossedOut: Boolean = false,
) derives CanEqual

object CellStyle:
    /** No colour, no attribute. */
    val plain: CellStyle = CellStyle()

    /** The style of a jatatui cell. Reset colours read as unset: a cell reports
      * Reset for the colours that nobody set.
      */
    private[tui4s] def of(st: JStyle): CellStyle =
        def colour(c: java.util.Optional[JColor]): Option[String] =
          if c.isPresent && c.get != JColor.RESET then Some(name(c.get))
          else None
        val m                                                     = st.addModifier()
        CellStyle(
          fg = colour(st.fg()),
          bg = colour(st.bg()),
          bold = m.contains(Modifier.BOLD),
          dim = m.contains(Modifier.DIM),
          italic = m.contains(Modifier.ITALIC),
          underlined = m.contains(Modifier.UNDERLINED),
          reversed = m.contains(Modifier.REVERSED),
          crossedOut = m.contains(Modifier.CROSSED_OUT),
        )
    end of

    /** The text of a jatatui colour: `#rrggbb`, `indexed:<n>`, or the ANSI name
      * in kebab case (`LightBlue` is `light-blue`).
      */
    private def name(c: JColor): String = c match
        case rgb: JColor.Rgb     => f"#${rgb.r}%02x${rgb.g}%02x${rgb.b}%02x"
        case idx: JColor.Indexed => s"indexed:${idx.i}"
        case other               =>
          other.getClass.getSimpleName
            .replaceAll("([a-z])([A-Z])", "$1-$2")
            .toLowerCase
end CellStyle

/** One frame as text plus styles: an immutable copy of the screen.
  *
  * Coordinates are cells: `x` is the column and `y` the row, from 0. A wide
  * character (CJK, many emoji) takes two cells but one place in its row string,
  * as a terminal shows it.
  *
  * @param rows
  *   the rows as a terminal shows them, trailing spaces trimmed
  * @param styles
  *   the style of each cell, `styles(y)(x)`
  * @param cursor
  *   the terminal cursor (column, row), when the screen shows it
  */
// claude 2026-10-12: created (RC-28: the Screen of the old PR 3 with CellStyle)
final case class Screen(
    rows: Vector[String],
    styles: Vector[Vector[CellStyle]],
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
    def styleAt(x: Int, y: Int): CellStyle = styles(y)(x)

    override def toString: String = text
end Screen

/** Builds screens. */
object Screen:
    /** A copy of the screen of `backend`. */
    private[tui4s] def of(backend: TestBackend): Screen =
        val pos = backend.getCursorPosition()
        of(
          backend.buffer(),
          if backend.cursorShown() then Some((pos.x, pos.y)) else None,
        )

    /** A copy of `buf` with the cursor `cursor`. */
    private[tui4s] def of(buf: Buffer, cursor: Option[(Int, Int)]): Screen =
        val area   = buf.area()
        // The trailing cells of a wide character hold " "; skip them, as
        // jatatui's Buffer.diff does, so that a row reads as the terminal
        // shows it ("✔ call bob", not "✔  call bob").
        val rows   = Vector.tabulate(area.height) { y =>
          val sb   = new java.lang.StringBuilder(area.width)
          var skip = 0 // scalafix:ok DisableSyntax.var; P1: rows as a fold
          var x    = 0 // scalafix:ok DisableSyntax.var; P1: rows as a fold
          while x < area.width do // scalafix:ok DisableSyntax.while; P1: rows as a fold
              val sym = buf.cellAt(area.x + x, area.y + y).symbol()
              if skip == 0 then
                  val _ = sb.append(sym)
              skip = (skip.max(width(sym)) - 1).max(0)
              x += 1
          trimEnd(sb.toString)
        }
        val styles = Vector.tabulate(area.height, area.width) { (y, x) =>
          CellStyle.of(buf.cellAt(area.x + x, area.y + y).style())
        }
        Screen(rows, styles, cursor)
    end of

    private def width(s: String): Int = JSpan.raw(s).width()

    private def trimEnd(s: String): String =
        var end = s.length // scalafix:ok DisableSyntax.var; P1: trimEnd as a fold
        while end > 0 && s.charAt(end - 1) == ' ' do end -= 1 // scalafix:ok DisableSyntax.while; P1: trimEnd as a fold
        s.substring(0, end)

    /** The grapheme clusters of `s`, in order. */
    private def graphemes(s: String): Vector[String] =
        val it  = BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = Vector.newBuilder[String]
        var a   = it.first() // scalafix:ok DisableSyntax.var; P1: TextEdit.graphemes
        var b   = it.next()  // scalafix:ok DisableSyntax.var; P1: TextEdit.graphemes
        while b != BreakIterator.DONE do // scalafix:ok DisableSyntax.while; P1: TextEdit.graphemes
            out += s.substring(a, b)
            a = b
            b = it.next()
        out.result()
    end graphemes

    /** The cells `x until x + width` of a row string. */
    private def cells(row: String, x: Int, width: Int): String =
        val sb  = new java.lang.StringBuilder(width)
        var col = 0 // scalafix:ok DisableSyntax.var; P1: cells as a fold
        graphemes(row).foreach { g =>
          val w = Screen.width(g).max(1)
          if col >= x && col + w <= x + width then
              val _ = sb.append(g)
          else if col < x + width && col + w > x then
              // a wide character cut by an edge: the visible cells are blank
              var c = col.max(x) // scalafix:ok DisableSyntax.var; P1: cells as a fold
              while c < (col + w).min(x + width) do // scalafix:ok DisableSyntax.while; P1: cells as a fold
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
// claude 2026-10-12: created (from the old PR 3)
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
  * `assert(diffScreen(screen, expected).isEmpty)`: kyo-test counts only its own
  * assertions.
  */
// claude 2026-10-12: created (from the old PR 3)
def assertScreen(actual: Screen, expected: String): Unit =
  diffScreen(actual, expected).foreach { diff =>
    throw new AssertionError( // scalafix:ok DisableSyntax.throw; KEEP: test frameworks need AssertionError
      s"screen differs:\n$diff\n--- actual screen:\n${actual.text}",
    )
  }
