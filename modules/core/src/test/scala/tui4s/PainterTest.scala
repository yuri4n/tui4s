package tui4s

import java.util.concurrent.atomic.AtomicInteger
import jatatui.core.style.Style as JStyle
import jatatui.widgets.paragraph.Paragraph
import kyo.test.*
import tui4s.testkit.*

class PainterTest extends TuiSuite:
    "text: alignment" in {
      assertShows(render(text("hi"), 8, 1), "hi")
      assertShows(render(text("hi").centered, 8, 1), "   hi")
      assertShows(render(text("hi").right, 8, 1), "      hi")
    }

    "paragraph: wraps at words; wrap(false) cuts; scroll" in {
      val p = paragraph("one two three four")
      assertShows(render(p, 9, 3), "one two\nthree\nfour")
      assertShows(render(p.wrap(false), 9, 3), "one two t")
      assertShows(render(paragraph("a", "b", "c").scroll(1), 5, 3), "b\nc")
    }

    "stack: sizes, gap and flex" in {
      val col = column(text("a").size(1), text("b").size(1)).gap(1)
      assertShows(render(col, 3, 4), "a\n\nb")
      assertShows(render(row(text("L").size(3), text("R")), 6, 1), "L  R")
      assertShows(
        render(column(text("x").size(1)).flex(Flex.End), 3, 3),
        "\n\nx",
      )
      assertShows(
        render(column(text("x").size(1)).flex(Flex.Center), 3, 3),
        "\nx",
      )
      assertShows(
        render(
          row(text("a").size(1), text("b").size(1)).flex(Flex.SpaceBetween),
          5,
          1,
        ),
        "a   b",
      )
      assertShows(
        render(row(text("ab").percent(50), text("cd").ratio(1, 2)), 6, 1),
        "ab cd",
      )
    }

    "layers and popup: the popup is cleared and painted on top" in {
      val base  = paragraph("xxxxxxxx", "xxxxxxxx", "xxxxxxxx", "xxxxxxxx")
      val view  = layers(base, text("hi").bordered().popup(6, 3))
      val shown = render(view, 8, 4)
      assertShows(
        shown,
        """x╭────╮x
            |x│hi  │x
            |x╰────╯x
            |xxxxxxxx""".stripMargin,
      )
      assertShows(render(layers(base, clear), 8, 4), "")
    }

    "list: highlight and a scroll offset kept across frames" in {
      val items = (0 until 10).map(i => s"item$i")
      val app   = App[Int](
        0,
        n => list(items, n),
        (n, e) =>
          e match
              case Key.Down => n + 1
              case Key.Up   => n - 1
              case _        => n,
      )
      val t     = TuiTest(app, 10, 3)
      assertShows(t.screen, "> item0\n  item1\n  item2")
      t.press(Key.Down, Key.Down, Key.Down, Key.Down, Key.Down)
      assertShows(t.screen, "  item3\n  item4\n> item5")
      t.press(Key.Up)
      // A fresh ListState would scroll to item2 at the top; the kept one
      // stays.
      assertShows(t.screen, "  item3\n> item4\n  item5")
    }

    "list: the focused and unfocused highlights come from the theme" in {
      val focused   = render(list(Seq("a", "b"), 1), 6, 2)
      val unfocused = render(list(Seq("a", "b"), 1, focused = false), 6, 2)
      assert(focused.styleAt(2, 1) == Style.reversed)
      assert(unfocused.styleAt(2, 1) == Style.dim)
      assert(focused.styleAt(2, 0) == Style.default)
      assertShows(render(list(Seq("a", "b"), -1), 6, 2), "a\nb")
    }

    "widget state is swept when its node is not painted, and .id keeps it" in {
      final case class S(
          selected: Int = 0,
          banner: Boolean = false,
          shown: Boolean = true,
      )
      val items              = (0 until 10).map(i => s"item$i")
      def app(keep: Boolean) = App[S](
        S(),
        s =>
            val l    = list(items, s.selected)
            val body =
              if s.shown then (if keep then l.id("items") else l) else empty
            if s.banner then row(text("!").size(1), body) else row(body)
        ,
        (s, e) =>
          e match
              case Key.Down      => s.copy(selected = s.selected + 1)
              case Key.Up        => s.copy(selected = s.selected - 1)
              case Key.Char('b') => s.copy(banner = !s.banner)
              case Key.Char('h') => s.copy(shown = !s.shown)
              case _             => s,
      )
      val kept               = TuiTest(app(keep = true), 10, 3)
      kept.press(Key.Down, Key.Down, Key.Down, Key.Down, Key.Down, Key.Up, 'b')
      assertShows(kept.screen, "!  item3\n > item4\n   item5")
      val moved              = TuiTest(app(keep = false), 10, 3)
      moved.press(Key.Down, Key.Down, Key.Down, Key.Down, Key.Down, Key.Up, 'b')
      assertShows(moved.screen, "!  item2\n   item3\n > item4")
      val swept              = TuiTest(app(keep = true), 10, 3)
      swept.press(Key.Down, Key.Down, Key.Down, Key.Down, Key.Down, Key.Up, 'h')
      assertShows(swept.screen, "")
      swept.press('h')
      assertShows(swept.screen, "  item2\n  item3\n> item4")
    }

    "table: header, rows, widths, selection" in {
      val view  = table(
        Seq("Name", "Qty"),
        Seq(Seq("apple", "3"), Seq(line("pear".bold), "10")),
        Seq(Constraint.Length(6), Constraint.Length(3)),
        selected = 1,
      )
      val shown = render(view, 14, 4)
      assertShows(shown, "  Name   Qty\n  apple  3\n> pear   10")
      assert(shown.styleAt(2, 0) == Style.bold)
      assertShows(
        render(table(Seq("a", "b"), Seq(Seq("1", "2")), Nil), 8, 2),
        "a    b\n1    2", // equal widths, then one cell of spacing
      )
    }

    "tabs: the selected title in the accent color" in {
      val shown  = render(tabs(Seq("One", "Two"), 1), 12, 1)
      assertShows(shown, " One │ Two")
      val (x, _) = shown.find("Two").get
      assert(shown.styleAt(x, 0) == Style.bold.fg(Color.Cyan))
      assertShows(render(tabs(Seq("One", "Two"), 9), 12, 1), " One │ Two")
      assertShows(render(tabs(Nil, 0), 12, 1), "")
    }

    "gauge: label, ratio, clamping" in {
      assertShows(render(gauge(0.5, "50%"), 10, 1), "███50%")
      // ratatui (and jatatui) leave one cell after the label blank
      assertShows(render(gauge(1.0), 10, 1), "███100% ██")
      assertShows(render(gauge(0.0), 10, 1), "    0%")
      val line = render(lineGauge(0.5, "half"), 14, 1)
      assertShows(line, "half ─────────")
      assert(line.styleAt(5, 0) == Style.fg(Color.Cyan)) // filled
      assert(line.styleAt(13, 0) == Style.default)       // not filled
    }

    "sparkline: one bar per value, the newest that fit" in {
      assertShows(
        render(sparkline(Seq(1L, 2L, 4L, 8L), Some(8L)), 6, 1),
        "▁▂▄█",
      )
      assertShows(render(sparkline((1L to 10L), Some(10L)), 3, 1), "▆▇█")
    }

    "input: text, cursor, placeholder, scrolling" in {
      val typed     = render(input(TextField("hello")), 8, 1)
      assertShows(typed, "hello")
      assert(typed.cursor == Some((5, 0)))
      val blank     =
        render(input(TextField.empty, placeholder = "type here"), 12, 1)
      assertShows(blank, "type here")
      assert(blank.styleAt(0, 0) == Style.dim && blank.cursor == Some((0, 0)))
      val unfocused = render(input(TextField("x"), focused = false), 8, 1)
      assert(unfocused.cursor.isEmpty)
      val long      = render(input(TextField("abcdefghij")), 5, 1)
      assertShows(long, "ghij")
      assert(long.cursor == Some((4, 0)))
      val wide      = render(input(TextField("日本語")), 4, 1)
      assertShows(wide, "語")
      assert(wide.cursor == Some((2, 0)))
      val left      = TextField("abcdefghij").edit(Key.Home).edit(Key.Right)
      val start     = render(input(left), 5, 1)
      assertShows(start, "abcde")
      assert(start.cursor == Some((1, 0)))
    }

    "log: the newest lines, bottom-aligned" in {
      assertShows(render(logPane(Seq("1", "2", "3", "4")), 5, 3), "2\n3\n4")
      assertShows(render(logPane(Seq("1", line("2".red))), 5, 4), "\n\n1\n2")
      assertShows(render(logPane(Seq("abcdefgh")), 5, 1), "abcde")
    }

    "help: whole entries, then an ellipsis; theme styles" in {
      val keys   = fixtures.CounterApp.keys
      assertShows(
        render(help(keys), 40, 1),
        "+ increment · - decrement · q quit",
      )
      assertShows(render(help(keys), 30, 1), "+ increment · - decrement …")
      assertShows(render(help(keys), 12, 1), "+ increment")
      assertShows(render(help(keys), 8, 1), "+ increm")
      assertShows(
        render(help(keys).right, 30, 1),
        "   + increment · - decrement …",
      )
      val shown  = render(help(keys), 40, 1)
      assert(shown.styleAt(0, 0) == Style.bold)
      assert(shown.styleAt(2, 0) == Style.dim)
      val themed = TuiTest(
        still(
          help(keys),
          TuiOptions(theme = Theme(helpKey = Style.fg(Color.Red))),
        ),
        40,
        1,
      ).screen
      assert(themed.styleAt(0, 0) == Style.fg(Color.Red))
    }

    "bordered, framed, padded, styled" in {
      assertShows(
        render(text("a").bordered(" t "), 6, 3),
        "╭ t ─╮\n│a   │\n╰────╯",
      )
      assertShows(
        render(text("a").framed(Border("x", BorderKind.Double)), 5, 3),
        "╔x══╗\n║a  ║\n╚═══╝",
      )
      assertShows(
        render(text("a").framed(Border(kind = BorderKind.Plain)), 3, 3),
        "┌─┐\n│a│\n└─┘",
      )
      val focused = render(text("a").bordered("t", focused = true), 5, 3)
      assert(focused.styleAt(0, 0) == Style.fg(Color.Cyan))
      assert(focused.styleAt(1, 0) == Style.fg(Color.Cyan))
      val plain   = render(text("a").bordered("t"), 5, 3)
      assert(plain.styleAt(0, 0) == Style.default)
      val magenta = TuiTest(
        still(
          text("a").bordered("t", focused = true),
          TuiOptions(theme = Theme(accent = Color.Magenta)),
        ),
        5,
        3,
      ).screen
      assert(magenta.styleAt(0, 0) == Style.fg(Color.Magenta))
      assertShows(render(text("a").padded(2, 1), 5, 3), "\n  a")
      val styled  = render(text("a").style(Style.bg(Color.Blue)), 3, 1)
      assert(styled.styleAt(0, 0) == Style.bg(Color.Blue))
      assert(styled.styleAt(2, 0) == Style.bg(Color.Blue))
    }

    "escape hatches: widget, draw, stateful" in {
      assertShows(render(widget(Paragraph.of("raw")), 5, 1), "raw")
      assertShows(
        render(
          draw((area, buf) =>
            buf.setString(area.x + 1, area.y, "d", JStyle.empty()),
          ),
          5,
          1,
        ),
        " d",
      )
      final case class S(n: Int = 0, shown: Boolean = true)
      val counter: (
          jatatui.core.layout.Rect,
          jatatui.core.buffer.Buffer,
          AtomicInteger,
      ) => Unit =
        (area, buf, st) =>
          buf.setString(
            area.x,
            area.y,
            st.incrementAndGet().toString,
            JStyle.empty(),
          )
      val app   = App[S](
        S(),
        s =>
          if s.shown then
              column(
                text(s.n.toString).size(1),
                stateful(AtomicInteger())(counter),
              )
          else empty,
        (s, e) =>
          e match
              case Key.Char('+') => s.copy(n = s.n + 1)
              case Key.Char('h') => s.copy(shown = !s.shown)
              case _             => s,
      )
      val t     = TuiTest(app, 5, 2)
      assertShows(t.screen, "0\n1")
      t.press('+')
      assertShows(t.screen, "1\n2") // the state is kept
      t.press('h')
      t.press('h')
      assertShows(t.screen, "1\n1") // swept while hidden, fresh again
    }

    "mouse tags: the tagged areas under the pointer, top-most first" in {
      val view = layers(
        row(text("left").tag("left"), text("right").tag("right")).tag("base"),
        text("pop").tag("pop").popup(3, 1),
      )
      val app  = App[List[String]](
        Nil,
        _ => view,
        (s, e) =>
          e match
              case m: Mouse => m.tags
              case _        => s,
      )
      val t    = TuiTest(app, 10, 3)
      t.click(0, 0)
      assert(t.state == List("left", "base"))
      t.click(9, 2)
      assert(t.state == List("right", "base"))
      t.click(4, 1)
      assert(t.state == List("pop", "left", "base"))
    }

    "an empty area paints nothing and does not fail" in {
      assertShows(render(column(text("a").size(0), text("b")), 3, 1), "b")
      assertShows(render(text("a").bordered(), 1, 1), "╭")
      assertShows(render(empty, 3, 1), "")
      assertShows(render(column(), 3, 1), "")
    }
end PainterTest
