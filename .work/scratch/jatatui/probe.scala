//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using option -deprecation -feature -Wunused:imports

// Headless jatatui probe for tui4s (no TTY, no native library).
// Run: source .work/env.sh && scala-cli run --server=false .work/scratch/jatatui/probe.scala

import jatatui.core.backend.TestBackend
import jatatui.core.buffer.Buffer
import jatatui.core.layout.{Constraint, Layout, Rect}
import jatatui.core.style.{Color, Modifier, Style}
import jatatui.core.terminal.{Frame, Terminal}
import jatatui.core.text.{Line, Span, Text}
import jatatui.core.widgets.Widget
import jatatui.widgets.block.{Block, BorderType}
import jatatui.widgets.gauge.Gauge
import jatatui.widgets.list.{ListItem, ListState, List as JList}
import jatatui.widgets.paragraph.{Paragraph, Wrap}

import java.util.Optional
import scala.jdk.CollectionConverters.*

object Probe:
  def lines(buf: Buffer): Vector[String] =
    val a = buf.area()
    Vector.tabulate(a.height()) { y =>
      (0 until a.width()).map(x => buf.cellAt(a.x() + x, a.y() + y).symbol()).mkString
    }

  var failures = 0
  def check(name: String, ok: Boolean): Unit =
    println(s"[${if ok then "OK  " else "FAIL"}] $name")
    if !ok then failures += 1

  def ui(frame: Frame, state: ListState, percent: Int): Unit =
    val outer = Block
      .bordered()
      .withBorderType(BorderType.Rounded)
      .withTitle(Line.from(" jatatui probe ").centered())
      .withTitleBottom(" q to quit ")
    val inner = outer.inner(frame.area())
    frame.renderWidget(outer, frame.area())

    // Java varargs: pass records built with Scala 3 universal apply (no `new`)
    val Array(top, mid, bottom) =
      Layout.vertical(Constraint.Length(3), Constraint.Fill(1), Constraint.Length(1)).areas(inner, 3): @unchecked

    val para = Paragraph
      .of(Text.from(Line.from(Span.styled("Hello", Style.empty().bold().withFg(Color.YELLOW)), Span.raw(" from Scala 3.9 on JDK 25, wrapped by jatatui."))))
      .withWrap(Wrap(true))
    frame.renderWidget(para, top)

    val items = (0 until 8).map(i => ListItem.of(s"item $i")).asJava
    val list = JList
      .of(items)
      .withHighlightSymbol(">> ")
      .withHighlightStyle(Style.empty().withAddModifier(Modifier.REVERSED))
    frame.renderStatefulWidget(list, mid, state)

    val gauge = Gauge.empty().withPercent(percent).withLabel(s"$percent%").withGaugeStyle(Style.empty().withFg(Color.GREEN))
    frame.renderWidget(gauge, bottom)

    // A Scala lambda is a jatatui Widget (SAM conversion of @FunctionalInterface)
    val corner: Widget = (area, buf) => buf.setString(area.right() - 1, area.y(), "*", Style.empty())
    frame.renderWidget(corner, frame.area())

  def main(args: Array[String]): Unit =
    println(s"java.version=${System.getProperty("java.version")} scala=${scala.util.Properties.versionNumberString}")
    val backend = TestBackend(40, 12)
    val terminal = Terminal.create(backend)
    val state = ListState.of(0, Optional.of[Integer](6)) // Optional<Integer> needs an explicit [Integer]

    val completed = terminal.draw(f => ui(f, state, 42))
    val out = lines(backend.buffer())
    out.foreach(l => println(s"|$l|"))

    val expected = Vector(
      "╭─────────── jatatui probe ────────────*",
      "│Hello from Scala 3.9 on JDK 25,       │",
      "│wrapped by jatatui.                   │",
      "│                                      │",
      "│   item 1                             │",
      "│   item 2                             │",
      "│   item 3                             │",
      "│   item 4                             │",
      "│   item 5                             │",
      "│>> item 6                             │",
      "│████████████████ 42%                  │",
      "╰ q to quit ───────────────────────────╯"
    )
    check("rendered lines equal expected", out == expected)
    if out != expected then
      out.zip(expected).zipWithIndex.filter((p, _) => p._1 != p._2).foreach { case ((a, e), i) =>
        println(s"  row $i\n    got: |$a|\n    exp: |$e|")
      }
    check("ListState.offset was mutated by render (0 -> 1)", state.offset() == 1)
    check("selected still 6", state.selected() == Optional.of[Integer](6))
    val sel = backend.buffer().cellAt(1, 9)
    check("selected row is REVERSED", sel.modifier.contains(Modifier.REVERSED))
    check("'Hello' is bold yellow", {
      val c = backend.buffer().cellAt(1, 1); c.fg == Color.YELLOW && c.modifier.contains(Modifier.BOLD)
    })
    check("CompletedFrame.count == 0", completed.count() == 0)

    // Second frame: select item 0 -> offset back to 0; count updates through TestBackend
    state.select(Optional.of[Integer](0))
    terminal.draw(f => ui(f, state, 43))
    val out2 = lines(backend.buffer())
    check("after select(0): first list row is '>> item 0'", out2(4).startsWith("│>> item 0"))
    check("offset reset to 0", state.offset() == 0)

    // Buffer equality helper (content+styles): Buffer.withLines builds an unstyled expected buffer
    val plain = Buffer.empty(Rect(0, 0, 10, 1))
    plain.setString(0, 0, "abc", Style.empty())
    check("Buffer.withLines equality", plain == Buffer.withLines("abc       "))

    // Buffer.diff returns LIVE cell references
    val prev = Buffer.empty(Rect(0, 0, 3, 1))
    val next = Buffer.empty(Rect(0, 0, 3, 1))
    next.setString(0, 0, "x", Style.empty())
    val upd = prev.diff(next).get(0)
    next.cellAt(0, 0).setSymbol("y")
    check("Buffer.diff returns live Cell refs (update sees later mutation)", upd.cell().symbol() == "y")

    // Layout cache returns the same Rect[] instance
    val l = Layout.horizontal(Constraint.Percentage(50), Constraint.Percentage(50))
    val r = Rect(0, 0, 10, 1)
    val a1 = l.split(r)
    val a2 = Layout.horizontal(Constraint.Percentage(50), Constraint.Percentage(50)).split(r)
    check("Layout.split returns the cached array instance for an equal Layout", a1 eq a2)

    // Out-of-bounds write
    val oob = scala.util.Try(Buffer.empty(Rect(0, 0, 2, 1)).setString(0, 5, "x", Style.empty()))
    println(s"  setString outside area -> ${oob.failed.map(_.getClass.getName).getOrElse("no exception")}")

    println(if failures == 0 then "ALL CHECKS PASSED" else s"$failures CHECK(S) FAILED")
    if failures != 0 then sys.exit(1)
