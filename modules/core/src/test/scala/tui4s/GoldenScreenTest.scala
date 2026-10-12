package tui4s

import jatatui.core.style.Color as JColor
import jatatui.core.style.Modifier
import kyo.*
import kyo.UI.*

/** Golden screens: a static kyo-ui value renders to the expected cells. */
// claude 2026-10-12: created
class GoldenScreenTest extends ScreenSuite:

    "borders" - {
      "solid" in {
        assertScreen(
          div("hi").style(_.border(1.px, _.white)),
          6,
          3,
          """┌────┐
            |│hi  │
            |└────┘""".stripMargin,
        )
      }
      "rounded" in {
        assertScreen(
          div("hi").style(_.border(1.px, _.white).rounded(1.px)),
          6,
          3,
          """╭────╮
            |│hi  │
            |╰────╯""".stripMargin,
        )
      }
      "dashed, with the border colour on the glyphs" in {
        val ui = div("hi").style(
          _.border(1.px, Style.BorderStyle.dashed, Style.Color.red),
        )
        assertScreen(
          ui,
          6,
          3,
          """┌╌╌╌╌┐
            |╎hi  ╎
            |└╌╌╌╌┘""".stripMargin,
        )
        val b  = buffer(ui, 6, 3)
        assert(b.cellAt(0, 0).fg == JColor.Rgb(239, 68, 68))
        assert(b.cellAt(1, 1).fg != JColor.Rgb(239, 68, 68))
      }
    }

    "colours and text attributes" - {
      "text colour and background" in {
        val b = buffer(div("hi").style(_.color(_.red).bg(_.blue)), 4, 1)
        assert(b.cellAt(0, 0).fg == JColor.Rgb(239, 68, 68))
        assert(b.cellAt(0, 0).bg == JColor.Rgb(59, 130, 246))
        // the background fills the whole box, also after the text
        assert(b.cellAt(3, 0).bg == JColor.Rgb(59, 130, 246))
      }
      "a child inherits the text colour" in {
        val b = buffer(div.style(_.color(_.green))(span("a")), 2, 1)
        assert(b.cellAt(0, 0).fg == JColor.Rgb(34, 197, 94))
      }
      "bold, italic, underline; h1 is bold and underlined" in {
        val ui = div.style(_.row.gap(1.px))(
          span("b").style(_.bold),
          span("i").style(_.italic),
          span("u").style(_.underline),
        )
        val b  = buffer(ui, 6, 1)
        assert(b.cellAt(0, 0).modifier.contains(Modifier.BOLD))
        assert(b.cellAt(2, 0).modifier.contains(Modifier.ITALIC))
        assert(b.cellAt(4, 0).modifier.contains(Modifier.UNDERLINED))
        assert(!b.cellAt(2, 0).modifier.contains(Modifier.BOLD))
        val h  = buffer(h1("T"), 2, 1)
        assert(h.cellAt(0, 0).modifier.contains(Modifier.BOLD))
        assert(h.cellAt(0, 0).modifier.contains(Modifier.UNDERLINED))
      }
    }

    "row and column flex" - {
      "a row puts its items side by side; a column stacks them" in {
        assertScreen(div.style(_.row)(span("ab"), span("cd")), 8, 1, "abcd")
        assertScreen(div(span("a"), span("b")), 3, 3, "a\nb")
      }
      "justify: center, end, spaceBetween" in {
        def row(j: Style.Justification) =
          div.style(_.row.justify(j))(span("ab"), span("cd"))
        assertScreen(row(Style.Justification.center), 8, 1, "  abcd")
        assertScreen(row(Style.Justification.end), 8, 1, "    abcd")
        assertScreen(row(Style.Justification.spaceBetween), 8, 1, "ab    cd")
      }
      "flexGrow takes the free space of the row" in {
        val ui =
          div.style(_.row)(span("a").style(_.flexGrow(1).bg(_.red)), span("b"))
        assertScreen(ui, 6, 1, "a    b")
        val b  = buffer(ui, 6, 1)
        assert(b.cellAt(4, 0).bg == JColor.Rgb(239, 68, 68))
        assert(b.cellAt(5, 0).bg != JColor.Rgb(239, 68, 68))
      }
      "flexGrow takes the free rows of a column" in {
        val ui =
          div.style(_.height(3.px))(span("a").style(_.flexGrow(1)), span("b"))
        assertScreen(ui, 3, 3, "a\n\nb")
      }
      // claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:399)
      "flexShrink removes only the deficit; the last item loses the cell" in {
        // three one-cell items in two cells: one cell too many, not three
        assertScreen(
          div.style(_.row)(span("a"), span("b"), span("c")),
          2,
          1,
          "ab",
        )
        assertScreen(div.style(_.row)(span("abc"), span("def")), 5, 1, "abcde")
      }
    }

    "gap and padding" - {
      "gap in a row and in a column" in {
        assertScreen(
          div.style(_.row.gap(2.px))(span("ab"), span("cd")),
          8,
          1,
          "ab  cd",
        )
        assertScreen(
          div.style(_.gap(1.px))(span("a"), span("b")),
          3,
          3,
          "a\n\nb",
        )
      }
      "padding moves the content inside the box" in {
        assertScreen(div("x").style(_.padding(1.px, 2.px)), 7, 3, "\n  x")
      }
      "padding inside a border" in {
        assertScreen(
          div("x").style(_.border(1.px, _.white).padding(0.px, 1.px)),
          5,
          3,
          """┌───┐
            |│ x │
            |└───┘""".stripMargin,
        )
      }
    }

    "overflow is clipped" - {
      "rows below a fixed height are not painted" in {
        val ui = div.style(_.height(2.px))(span("a"), span("b"), span("c"))
        assertScreen(ui, 3, 4, "a\nb")
      }
      "cells right of a fixed width are not painted" in {
        assertScreen(div.style(_.row.width(3.px))(span("abcdef")), 6, 1, "abc")
      }
    }

    "text wraps in a column" - {
      "at the spaces" in {
        assertScreen(div("one two three"), 6, 3, "one\ntwo\nthree")
      }
      "a long word splits" in {
        assertScreen(div("abcdefgh"), 5, 2, "abcde\nfgh")
      }
      // claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:114)
      "a long word of wide characters splits by cells, not by characters" in {
        assertScreen(div("漢字"), 2, 2, "漢\n字")
      }
      "no wrap in a row" in {
        assertScreen(div.style(_.row)(span("one two")), 7, 2, "one two")
      }
    }

    "wide characters" - {
      "take two cells each" in {
        val b = buffer(div.style(_.row)(span("漢"), span("x")), 6, 1)
        assert(b.cellAt(0, 0).symbol() == "漢")
        assert(b.cellAt(2, 0).symbol() == "x")
        val w = buffer(div("漢字"), 6, 1)
        assert(w.cellAt(0, 0).symbol() == "漢")
        assert(w.cellAt(2, 0).symbol() == "字")
        // the cell under the right half of a wide character reads as a space
        assertScreen(div("漢字"), 6, 1, "漢 字")
      }
    }

    "placeholders" - {
      "an image shows its alt text in brackets" in {
        assertScreen(img(ImgSrc.Path("logo.png"), "logo"), 8, 1, "[logo]")
      }
      "an svg (a chart) shows [chart], dim" in {
        val ui = div(Svg.svg)
        assertScreen(ui, 8, 1, "[chart]")
        assert(buffer(ui, 8, 1).cellAt(0, 0).modifier.contains(Modifier.DIM))
      }
    }

    "decorations" - {
      "a button has brackets; list items have markers" in {
        // in a row the button keeps its natural width; a root block stretches
        assertScreen(div.style(_.row)(button("+")), 6, 1, "[ + ]")
        assertScreen(button("+"), 6, 1, "[ +  ]")
        // kyo-ui's base CSS has `ul, ol { list-style: none }`: a marker needs
        // an explicit list style
        assertScreen(ul(li("a"), li("b")), 5, 2, "a\nb")
        assertScreen(
          ul(li("a"), li("b")).style(_.listStyle(_.disc)),
          5,
          2,
          "• a\n• b",
        )
        assertScreen(
          ol(li("a"), li("b")).style(_.listStyle(_.decimal)),
          5,
          2,
          "1. a\n2. b",
        )
      }
    }
end GoldenScreenTest
