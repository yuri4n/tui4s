//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using options -deprecation -feature

// Second headless probe: the remaining widgets and the Inline viewport, called from Scala 3.9.
// Run: source .work/env.sh && scala-cli run --server=false .work/scratch/jatatui/probe-widgets.scala
import jatatui.core.backend.TestBackend
import jatatui.core.buffer.Buffer
import jatatui.core.layout.{Constraint, Rect}
import jatatui.core.style.{Color, Style}
import jatatui.core.symbols.Marker
import jatatui.core.terminal.{Terminal, TerminalOptions, Viewport}
import jatatui.core.text.Line
import jatatui.widgets.Clear
import jatatui.widgets.barchart.{BarChart, BarGroup}
import jatatui.widgets.block.{Block, Padding}
import jatatui.widgets.calendar.{Calendar, DateStyler}
import jatatui.widgets.canvas.{Canvas, Circle, Context, Line as CLine}
import jatatui.widgets.chart.{Axis, Chart, Dataset, GraphType, Point}
import jatatui.widgets.gauge.LineGauge
import jatatui.widgets.input.TextInput
import jatatui.widgets.scrollbar.{Scrollbar, ScrollbarOrientation, ScrollbarState}
import jatatui.widgets.sparkline.Sparkline
import jatatui.widgets.table.{Row, Table, TableState}
import jatatui.widgets.tabs.Tabs

import java.time.LocalDate
import java.util.Optional
import scala.jdk.CollectionConverters.*

object Widgets:
  def show(name: String, buf: Buffer): Unit =
    println(s"--- $name")
    val a = buf.area
    for y <- 0 until a.height do println("|" + (0 until a.width).map(x => buf.cellAt(a.x + x, a.y + y).symbol).mkString + "|")

  def main(args: Array[String]): Unit =
    // Table + TableState
    val tbuf = Buffer.empty(Rect(0, 0, 30, 6))
    val table = Table
      .of(List(Row.ofStrings("1", "alpha"), Row.ofStrings("2", "beta"), Row.ofStrings("3", "gamma")).asJava,
          List[Constraint](Constraint.Length(3), Constraint.Fill(1)).asJava)
      .withHeader(Row.ofStrings("#", "name").withStyle(Style.empty().bold()))
      .withBlock(Block.bordered().withTitle("table"))
      .withHighlightSymbol("> ")
    val ts = TableState().withSelected(1)
    table.render(tbuf.area, tbuf, ts)
    show("Table (selected=1)", tbuf)
    println(s"table state offset=${ts.offset} selected=${ts.selected}")

    // Tabs, LineGauge, Sparkline, Scrollbar, TextInput into one buffer
    val buf = Buffer.empty(Rect(0, 0, 30, 5))
    Tabs.ofStrings("one", "two", "three").withSelected(1).render(Rect(0, 0, 30, 1), buf)
    LineGauge.empty().withRatio(0.5).withLabel("50%").render(Rect(0, 1, 30, 1), buf)
    Sparkline.empty().withDataLongs(1, 3, 5, 7, 5, 3, 1).render(Rect(0, 2, 7, 1), buf)
    val sbs = ScrollbarState.of(100).withPosition(50)
    Scrollbar.of(ScrollbarOrientation.HorizontalBottom).render(Rect(10, 2, 20, 1), buf, sbs)
    TextInput.of("héllo").withCursorPos(2).withFocused(true).render(Rect(0, 3, 30, 1), buf)
    val r = TextInput.insertAt("ab", 1, 'X')
    Clear().render(Rect(0, 4, 30, 1), buf)
    show("Tabs / LineGauge / Sparkline+Scrollbar / TextInput", buf)
    println(s"TextInput.insertAt -> ${r.value} cursor=${r.cursorPos}")

    // BarChart
    val bbuf = Buffer.empty(Rect(0, 0, 20, 5))
    BarChart.empty().withData(BarGroup.LabelledValue.of("a", 3), BarGroup.LabelledValue.of("b", 7)).withBarWidth(3).render(bbuf.area, bbuf)
    show("BarChart", bbuf)

    // Chart
    val cbuf = Buffer.empty(Rect(0, 0, 30, 8))
    Chart.of(Dataset.empty().withName("d").withData(Point(0, 0), Point(5, 5), Point(10, 2)).withGraphType(GraphType.Line).withMarker(Marker.Braille))
      .withXAxis(Axis.empty().withBounds(0, 10).withLabels("0", "10"))
      .withYAxis(Axis.empty().withBounds(0, 5).withLabels("0", "5"))
      .render(cbuf.area, cbuf)
    show("Chart", cbuf)

    // Canvas with a Scala lambda as Consumer<Context>
    val vbuf = Buffer.empty(Rect(0, 0, 20, 6))
    Canvas.empty().withXBounds(Array(0.0, 20.0)).withYBounds(Array(0.0, 10.0)).withMarker(Marker.Braille)
      .withPaintFn((ctx: Context) => { ctx.draw(Circle(10, 5, 4, Color.YELLOW)); ctx.draw(CLine(0, 0, 20, 10, Color.RED)) })
      .render(vbuf.area, vbuf)
    show("Canvas", vbuf)

    // Calendar with a Scala lambda as DateStyler
    val kbuf = Buffer.empty(Rect(0, 0, 22, 8))
    val styler: DateStyler = d => if d.getDayOfMonth == 1 then Style.empty().bold() else Style.empty()
    Calendar.of(LocalDate.of(2026, 9, 30), styler).withShowMonthHeader(Style.empty()).withShowWeekdaysHeader(Style.empty()).render(kbuf.area, kbuf)
    show("Calendar", kbuf)

    // Block padding + Inline viewport + insertBefore on a TestBackend
    val be = TestBackend(20, 6)
    be.setCursorPosition(jatatui.core.layout.Position(0, 2))
    val term = Terminal.withOptions(be, TerminalOptions(Viewport.inline(2)))
    term.draw(f => Block.bordered().withPadding(Padding.uniform(0)).withTitle("inline").render(f.area, f.bufferMut))
    term.insertBefore(1, b => b.setString(0, 0, "log line 1", Style.empty()))
    term.draw(f => Block.bordered().withTitle("inline").render(f.area, f.bufferMut))
    show("Inline viewport after insertBefore (TestBackend screen)", be.buffer)
    println(s"scrollback rows=${be.scrollback.area.height}")
