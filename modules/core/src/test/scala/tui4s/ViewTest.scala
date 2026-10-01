package tui4s

import kyo.test.*

class ViewTest extends Test[Any]:
    private val x = text("a")

    "x.size(3).bordered(t) == x.bordered(t).size(3)" in {
      assert(x.size(3).bordered("t") == x.bordered("t").size(3))
      assert(
        x.size(3).bordered("t") ==
          View.Sized(View.Bordered(x, Border("t"), false), Constraint.Length(3)),
      )
    }

    "every decoration lifts the size outside" in {
      val decorations: Seq[View => View] = Seq(
        _.bordered("t", focused = true),
        _.framed(Border("t", BorderKind.Double)),
        _.padded(1, 2),
        _.style(Style.bold),
        _.popup(10, 3),
        _.id("list"),
        _.tag("ok"),
      )
      val sizes: Seq[View => View]       = Seq(
        _.size(3),
        _.fill(2),
        _.percent(40),
        _.ratio(1, 3),
        _.atLeast(2),
        _.atMost(9),
      )
      for d <- decorations; s <- sizes do
          assert(s(d(x)) == d(s(x)))
          assert(s(d(x)).isInstanceOf[View.Sized])
    }

    "a later size replaces an earlier one" in {
      assert(x.size(3).size(5) == x.size(5))
      assert(x.size(3).fill(2) == View.Sized(x, Constraint.Fill(2)))
      assert(x.fill() == View.Sized(x, Constraint.Fill(1)))
    }

    "views compare by value, gauges included" in {
      assert(text("a", "b".bold) == text("a", "b".bold))
      assert(text("a", "b".bold) != text("a", "b"))
      assert(gauge(0.5, "50%") == gauge(0.5, "50%"))
      assert(gauge(0.5) != gauge(0.6))
      assert(lineGauge(0.5) != gauge(0.5))
      assert(
        column(list(Seq("a", line("b".red)), 1), sparkline(Seq(1L, 2L))) ==
          column(list(Seq("a", line("b".red)), 1), sparkline(Seq(1L, 2L))),
      )
      assert(
        help(fixtures.CounterApp.keys) == help(fixtures.CounterApp.keys),
      )
    }

    "gauge ratios are clamped when the view is built" in {
      assert(gauge(1.5) == gauge(1.0))
      assert(gauge(-2.0) == gauge(0.0))
      assert(gauge(Double.NaN) == gauge(0.0))
      assert(lineGauge(7.0) == lineGauge(1.0))
    }

    "the dsl builds nodes with the documented defaults" in {
      assert(text("a") == View.Text(Seq(Line("a")), Align.Left, false, 0))
      assert(paragraph("a", line("b")).wraps)
      assert(text("a").centered.align == Align.Center)
      assert(
        text("a").right.wrap().scroll(-3) ==
          View.Text(Seq(Line("a")), Align.Right, true, 0),
      )
      assert(column(x) == View.Stack(true, Seq(x), 0, Flex.Start))
      assert(
        row(x).gap(2).flex(Flex.End) == View.Stack(false, Seq(x), 2, Flex.End),
      )
      assert(list(Seq("a"), 0) == View.Items(Seq("a"), 0, true))
      assert(
        table(Seq("h"), Seq(Seq("c")), Nil) ==
          View.Table(Seq("h"), Seq(Seq("c")), Nil, -1, true),
      )
      assert(tabs(Seq("a"), 0) == View.Tabs(Seq("a"), 0))
      assert(gauge(0.5) == View.Gauge(0.5, "", Color.Cyan, false))
      assert(sparkline(Nil) == View.Sparkline(Nil, None, Color.Green))
      assert(input(TextField.empty) == View.Input(TextField.empty, true, ""))
      assert(logPane(Seq("l")) == View.Log(Seq("l")))
      assert(layers(x) == View.Layers(Seq(x)))
      assert(empty == View.Empty && clear == View.Clear)
      assert(x.padded(-1, -2) == View.Padded(x, 0, 0))
      assert(x.popup(-1, 5) == View.Popup(x, 0, 5))
    }

    "escape hatches compare by reference" in {
      val paint
          : (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer) => Unit =
        (_, _) => ()
      assert(draw(paint) == draw(paint))
      assert(draw(paint) != draw((_, _) => ()))
      val p                                                                = jatatui.widgets.paragraph.Paragraph.of("x")
      assert(widget(p) == widget(p))
    }
end ViewTest
