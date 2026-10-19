package tui4s

import jatatui.core.style.{Color as JColor, Modifier}
import kyo.*
import kyo.UI.*
import tui4s.internal.{Loc, Offset, Render}

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
      // claude 2026-10-12: created (PR #10 review: Codex, Props.scala:303)
      "rowReverse and columnReverse place the items from the end" in {
        assertScreen(
          div.style(_.rowReverse)(span("a"), span("b")),
          4,
          1,
          "  ba",
        )
        assertScreen(
          div.style(_.columnReverse.height(3.px))(span("a"), span("b")),
          1,
          3,
          "\nb\na",
        )
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

    // claude 2026-10-19: created (L1a)
    "viewports (overflow)" - {
      val first                                   = Loc.root.child(0)
      def lines(n: Int): Seq[UI.Ast.HtmlChildVal] =
        (1 to n).map[UI.Ast.HtmlChildVal](i => span(s"l$i"))
      "a viewport clips its kids to its box; the offset selects the rows; an offset past the end shows the last rows" in {
        val ui = div(
          div.style(_.height(2.px).overflowY(_.scroll))(lines(4)*),
          span("end"),
        )
        assertScreen(ui, 4, 4, "l1\nl2\nend")
        assertScreen(
          ui,
          4,
          4,
          "l3\nl4\nend",
          scroll = Map(first -> Offset(0, 2)),
        )
        assertScreen(
          ui,
          4,
          4,
          "l3\nl4\nend",
          scroll = Map(first -> Offset(0, 9)),
        )
      }
      "overflow hidden clips and does not scroll" in {
        val ui = div(
          div.style(_.height(2.px).overflow(_.hidden))(lines(3)*),
          span("end"),
        )
        assertScreen(
          ui,
          4,
          4,
          "l1\nl2\nend",
          scroll = Map(first -> Offset(0, 1)),
        )
      }
      "a viewport in a column shrinks to the free rows (min-height 0); a box does not" in {
        val ui  = div.style(_.height(4.px))(
          span("top"),
          div.style(_.overflowY(_.auto))(lines(5)*),
          span("end"),
        )
        assertScreen(ui, 4, 4, "top\nl1\nl2\nend")
        val box = div.style(_.height(4.px))(span("top"), div(lines(5)*))
        assertScreen(box, 4, 4, "top\nl1\nl2\nl3")
      }
      "a row viewport scrolls horizontally: its items keep their natural widths" in {
        val ui = div.style(_.row.width(4.px).overflowX(_.auto))(
          span("ab"),
          span("cd"),
          span("ef"),
        )
        assertScreen(ui, 6, 1, "abcd")
        assertScreen(ui, 6, 1, "cdef", scroll = Map(Loc.root -> Offset(2, 0)))
      }
    }

    // claude 2026-10-19: created (L1a)
    "the scroll indicator" - {
      def lines(n: Int): Seq[UI.Ast.HtmlChildVal] =
        (1 to n).map[UI.Ast.HtmlChildVal](i => span(s"l$i"))
      "a list that overflows shows a track with a thumb in its right column; the thumb moves with the offset; none when nothing overflows" in {
        val ui  = div.style(_.height(4.px).overflowY(_.scroll))(lines(8)*)
        assertScreen(ui, 4, 4, "l1 █\nl2 █\nl3 │\nl4 │")
        assertScreen(
          ui,
          4,
          4,
          "l3 │\nl4 █\nl5 █\nl6 │",
          scroll = Map(Loc.root -> Offset(0, 2)),
        )
        assertScreen(
          ui,
          4,
          4,
          "l5 │\nl6 │\nl7 █\nl8 █",
          scroll = Map(Loc.root -> Offset(0, 4)),
        )
        val few = div.style(_.height(4.px).overflowY(_.scroll))(lines(2)*)
        assertScreen(few, 4, 4, "l1\nl2")
        // 2 rows are too few for an indicator
        val low = div.style(_.height(2.px).overflowY(_.scroll))(lines(8)*)
        assertScreen(low, 4, 2, "l1\nl2")
      }
      // claude 2026-10-19: created (L1a review): the focus mark of a
      // viewport
      "a focused viewport has a heavy track (its focus mark when it has no border)" in {
        val ui = div.style(_.height(4.px).overflowY(_.scroll))(lines(8)*)
        assertScreen(ui, 4, 4, "l1 █\nl2 █\nl3 ┃\nl4 ┃", focus = Some(Loc.root))
      }
      "with a border and a padding, the indicator is in the right column of the padding box" in {
        val ui = div.style(
          _.border(1.px, _.white)
            .padding(0.px, 1.px)
            .height(6.px)
            .overflowY(_.auto),
        )(lines(10)*)
        assertScreen(
          ui,
          8,
          6,
          """┌──────┐
            |│ l1  █│
            |│ l2  █│
            |│ l3  ││
            |│ l4  ││
            |└──────┘""".stripMargin,
        )
      }
      "scrollbarWidth(none) hides it (the viewport keeps its width); scrollbarColor colours it" in {
        val none = div.style(
          _.height(3.px).overflowY(_.scroll).scrollbarWidth(_.none),
        )(span("abcd"), span("b"), span("c"), span("d"))
        assertScreen(none, 4, 3, "abcd\nb\nc")
        val b    = buffer(
          div.style(
            _.height(3.px)
              .overflowY(_.scroll)
              .scrollbarColor(Style.Color.red, Style.Color.blue),
          )(lines(6)*),
          4,
          3,
        )
        assert(b.cellAt(3, 0).fg == JColor.Rgb(239, 68, 68))
        assert(b.cellAt(3, 2).fg == JColor.Rgb(59, 130, 246))
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

    // claude 2026-10-19: created (L1a)
    "white-space, text-wrap and the ellipsis" - {
      "whiteSpace(noWrap) keeps one line; with overflow hidden and textOverflow(ellipsis) the cut is \"…\"; without overflow hidden it clips" in {
        def title(s: Style) = div.style(s.width(8.px))("a long  title here")
        val nowrap          = Style.whiteSpace(_.noWrap)
        assertScreen(title(nowrap), 10, 2, "a long t")
        assertScreen(
          title(nowrap.overflow(_.hidden).textOverflow(_.ellipsis)),
          10,
          2,
          "a long …",
        )
        assertScreen(title(Style.empty), 10, 3, "a long\ntitle\nhere")
      }
      "the ellipsis cuts by cells: a wide glyph that does not fit next to it is dropped" in {
        def cut(w: Int) = div.style(
          _.width(w.px)
            .overflow(_.hidden)
            .textOverflow(_.ellipsis)
            .whiteSpace(_.noWrap),
        )("漢字漢字漢字")
        // the cell under the right half of a wide character reads as a space
        assertScreen(cut(7), 10, 1, "漢 字 漢 …")
        assertScreen(cut(6), 10, 1, "漢 字 …")
        assertScreen(cut(1), 10, 1, "…")
      }
      "pre keeps the spaces and the line breaks and does not wrap; preWrap keeps them and wraps; normal and preLine collapse" in {
        val text                      = "a  b\n  c d e f g"
        def box(ws: Style.WhiteSpace) =
          div.style(_.width(6.px).whiteSpace(ws))(text)
        assertScreen(box(Style.WhiteSpace.pre), 8, 3, "a  b\n  c d")
        assertScreen(box(Style.WhiteSpace.preWrap), 8, 3, "a  b\n  c d\ne f g")
        assertScreen(box(Style.WhiteSpace.normal), 8, 3, "a b c\nd e f\ng")
        assertScreen(box(Style.WhiteSpace.preLine), 8, 3, "a b\nc d e\nf g")
      }
      "a text in a row item with a definite width wraps; without the width it does not" in {
        assertScreen(
          div.style(_.row)(
            span("one two three").style(_.width(5.px)),
            span("x"),
          ),
          10,
          3,
          "one\ntwo  x\nthree",
        )
        assertScreen(
          div.style(_.row)(span("one two three"), span("x")),
          16,
          3,
          "one two threex",
        )
      }
      // claude 2026-10-19: created (L1a review): a row shrinks a text that
      // wraps; the text is as high as its lines at its new width
      "a text that wraps in a row of a definite width with a sibling: the row shrinks it and it keeps all its lines" in {
        assertScreen(
          div.style(_.row.width(10.px))("aaaa bbbb cccc", span("XYZ")),
          12,
          4,
          "aaaa\nbbbb    XY\ncccc",
        )
      }
      "textWrap(noWrap) keeps a long word whole (it overflows); textWrap(ellipsis) with overflow hidden cuts it with \"…\"" in {
        val text = "ab abcdefgh cd"
        assertScreen(
          div.style(_.width(5.px).textWrap(_.noWrap))(text),
          8,
          3,
          "ab\nabcde\ncd",
        )
        assertScreen(
          div
            .style(_.width(5.px).textWrap(_.ellipsis).overflow(_.hidden))(text),
          8,
          3,
          "ab\nabcd…\ncd",
        )
        assertScreen(
          div.style(_.width(5.px))(text),
          8,
          4,
          "ab\nabcde\nfgh\ncd",
        )
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

    // claude 2026-10-19: created (L1a)
    "wide characters in decorations" - {
      "a select with a wide label keeps its arrow in the last cell; a button and a list marker measure their wide labels by cells" in {
        val pick = select.value("a")(
          option.value("a")("漢字"),
          option.value("b")("b"),
        )
        assertScreen(div.style(_.row)(pick), 8, 1, "漢 字  ▾")
        assertScreen(div.style(_.row)(button("漢")), 8, 1, "[ 漢  ]")
        assertScreen(
          ul(li("中文"), li("👍")).style(_.listStyle(_.decimal)),
          8,
          2,
          "1. 中 文\n2. 👍",
        )
      }
      "a text input shows a wide value by cells; its placeholder clips by cells" in {
        assertScreen(
          div.style(_.row)(input.value("中文字").style(_.width(5.px))),
          8,
          1,
          "中 文",
        )
        assertScreen(
          div.style(_.row)(input.placeholder("漢字漢字").style(_.width(5.px))),
          8,
          1,
          "漢 字",
        )
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

    // claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:264)
    "percentage sizes" - {
      "a percentage height is relative to the parent, not to the screen" in {
        val ui = div.style(_.height(4.px))(
          div("x").style(_.height(50.pct).bg(_.red)),
        )
        val b  = buffer(ui, 4, 20)
        assert(b.cellAt(0, 1).bg == JColor.Rgb(239, 68, 68))
        assert(b.cellAt(0, 2).bg != JColor.Rgb(239, 68, 68))
      }
    }

    // claude 2026-10-12: created (PR #10 review: Codex, Paint.scala:393)
    "inputs" - {
      "a range input shows its constant value" in {
        assertScreen(
          div.style(_.row)(rangeInput.value(50.0)),
          12,
          1,
          "─────●──────",
        )
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

    // claude 2026-10-19: created (L1b)
    "overlays and z-order" - {
      val list = ul(li("one"), li("two"), li("three"), li("four"), li("five"))
      "a centered modal over a list: its background covers the list" in {
        val ui = div(
          list,
          div.style(_.position(_.overlay).justify(_.center).align(_.center))(
            div.style(_.border(1.px, _.white).bg(_.black).padding(0.px, 1.px))(
              "Help",
            ),
          ),
        )
        assertScreen(
          ui,
          12,
          5,
          """one
            |tw┌──────┐
            |th│ Help │
            |fo└──────┘
            |five""".stripMargin,
        )
      }
      "a toast at the bottom right" in {
        val ui = div(
          list,
          div.style(
            _.position(_.fixed)
              .bottom(0.px)
              .right(0.px)
              .bg(_.blue)
              .padding(0.px, 1.px),
          )("Saved"),
        )
        assertScreen(
          ui,
          12,
          5,
          """one
            |two
            |three
            |four
            |five  Saved""".stripMargin,
        )
      }
      "a dropdown under a button, at the right edge of its relative box" in {
        val ui = div(
          div.style(_.row.justify(_.end))(
            div.style(_.position(_.relative))(
              button("Menu"),
              div.style(
                _.position(_.dropdown)
                  .width(9.px)
                  .border(1.px, _.white)
                  .bg(_.black),
              )(
                "Open",
                "Save as",
              ),
            ),
          ),
          list,
        )
        assertScreen(
          ui,
          14,
          6,
          """      [ Menu ]
            |one  ┌───────┐
            |two  │Open   │
            |three│Save as│
            |four └───────┘
            |five""".stripMargin,
        )
      }
      "a relative shift moves the element, not its siblings" in {
        val ui = div.style(_.row)(
          span("ab"),
          span("cd").style(_.position(_.relative).left(1.px).top(1.px)),
          span("ef"),
        )
        assertScreen(
          ui,
          8,
          2,
          """ab  ef
            |   cd""".stripMargin,
        )
      }
      "a higher zIndex paints over a lower one; equal ones in document order" in {
        def box(t: String, x: Int, z: Int) =
          div.style(
            _.position(_.fixed).top(0.px).left(x.px).zIndex(z).bg(_.black),
          )(t)
        assertScreen(
          div(box("AAAA", 0, 2), box("BBBB", 2, 1), box("CC", 5, 1)),
          8,
          1,
          "AAAABCC",
        )
      }
      "a fixed element escapes a viewport; an absolute one in it is clipped" in {
        val ui =
          div.style(_.height(2.px).overflow(_.hidden).position(_.relative))(
            "x",
            div.style(_.position(_.absolute).top(1.px).left(2.px))("abs\nabs"),
            div.style(_.position(_.fixed).top(3.px).left(0.px))("fixed"),
          )
        assertScreen(
          ui,
          8,
          4,
          """x
            |  abs
            |
            |fixed""".stripMargin,
        )
      }
    }

    // claude 2026-10-19: created (L1b)
    "flex-wrap and display" - {
      "flexWrap(wrap): the items break into 3 lines, each as high as its tallest item" in {
        val ui = div.style(
          _.row.flexWrap(_.wrap).gap(1.px).width(11.px).align(_.start),
        )(
          span("aaa"),
          span("bbb"),
          div("c1", "c2").style(_.border(1.px, _.white)),
          span("dd"),
          span("eeeeeeeee"),
        )
        assertScreen(
          ui,
          12,
          8,
          """aaa bbb
            |
            |┌──┐ dd
            |│c1│
            |│c2│
            |└──┘
            |
            |eeeeeeeee""".stripMargin,
        )
      }
      // claude 2026-10-19: created (L1b review): the lines stretch
      "flexWrap(wrap) in a higher box: each line gets an equal part of the free rows (CSS align-content: normal)" in {
        def box(t: String) = div(t).style(_.border(1.px, _.white))
        val ui             = div.style(_.row.flexWrap(_.wrap).width(4.px).height(9.px))(
          box("a"),
          box("b"),
        )
        assertScreen(
          ui,
          6,
          9,
          """┌─┐
            |│a│
            |│ │
            |│ │
            |└─┘
            |┌─┐
            |│b│
            |│ │
            |└─┘""".stripMargin,
        )
      }
      "displayNone: the element takes no space and paints nothing; display(block) and display(flex) keep the layout" in {
        val ui = div.style(_.row.gap(1.px))(
          span("a"),
          span("hidden").style(_.displayNone),
          span("b").style(_.display(_.block)),
          span("c").style(_.display(_.flex)),
        )
        assertScreen(ui, 8, 1, "a b c")
      }
    }

    // claude 2026-10-19: created (L1b)
    "custom colours (Color.Var)" - {
      val green  = JColor.Rgb(34, 197, 94)
      val red    = JColor.Rgb(239, 68, 68)
      val blue   = JColor.Rgb(59, 130, 246)
      val sheet  = Stylesheet
        .vars(
          "accent" -> "#22c55e",
          "panel"  -> "rgb(59, 130, 246)",
          "warn"   -> "var(--brand, #ef4444)",
        )
        .scopedVars(Selector.cls("alert"), "accent" -> "#ef4444")
      def accent = Style.Color.variable("accent")
      "the root declares them; a class block declares over them in its subtree; a fallback; no value is no colour" in {
        val ui = div(
          span("a").style(_.color(accent)),
          div.cssClass("alert")(span("b").style(_.color(accent))),
          span("c").style(_.color(Style.Color.variable("warn"))),
          span("d").style(_.color(Style.Color.variable("none"))),
          span("e").style(_.bg(Style.Color.variable("panel"))),
        )
        val b  = Render.buffer(ui, 2, 5, sheet = sheet)
        assert(b.cellAt(0, 0).fg == green)
        assert(b.cellAt(0, 1).fg == red)
        assert(b.cellAt(0, 2).fg == red)
        assert(b.cellAt(0, 3).fg == JColor.RESET)
        assert(b.cellAt(0, 4).bg == blue)
        // with no stylesheet, no colour
        assert(Render.buffer(ui, 2, 5).cellAt(0, 0).fg == JColor.RESET)
      }
      // claude 2026-10-19: created (L1b review): an unset var() inherits
      "a text colour with no value keeps the colour of the parent" in {
        val ui = div.style(_.color(Style.Color.rgb(239, 68, 68)))(
          span("x").style(_.color(Style.Color.variable("none"))),
          span("y").style(_.bg(Style.Color.variable("none"))),
        )
        val b  = Render.buffer(ui, 2, 2, sheet = sheet)
        assert(b.cellAt(0, 0).fg == red)
        assert(b.cellAt(0, 1).fg == red)
        assert(b.cellAt(0, 1).bg == JColor.RESET)
      }
    }
end GoldenScreenTest
