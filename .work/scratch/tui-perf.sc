//> using scala 3.7.4
//> using dep com.olvind.tui::tui:0.0.10
//> using javaOpt -Xmx512m

// Per-widget cost breakdown for tui-scala 0.0.10 on a 200x50 terminal with a no-op backend.
import tui.*
import tui.widgets.*
import java.lang.management.ManagementFactory

val tmx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
val tid = Thread.currentThread().threadId()
final class NullBackend(w: Int, h: Int) extends Backend {
  var cells = 0L
  def draw(content: Array[(Int, Int, Cell)]): Unit = cells += content.length
  def hideCursor(): Unit = (); def showCursor(): Unit = (); def getCursor(): (Int, Int) = (0, 0)
  def setCursor(x: Int, y: Int): Unit = (); def clear(): Unit = (); def size(): Rect = Rect(0, 0, w, h); def flush(): Unit = ()
}

def bench(name: String, n: Int = 1500)(body: (Frame, Int) => Unit): Unit =
  val nb = NullBackend(200, 50)
  val term = Terminal.init(nb)
  for i <- 0 until n do term.draw(f => body(f, i))
  nb.cells = 0
  val a0 = tmx.getThreadAllocatedBytes(tid); val t0 = System.nanoTime()
  for i <- 0 until n do term.draw(f => body(f, i))
  val t1 = System.nanoTime(); val a1 = tmx.getThreadAllocatedBytes(tid)
  println(f"$name%-45s ${(t1 - t0) / 1e3 / n}%8.1f us/frame ${(a1 - a0).toDouble / n / 1024}%9.1f KiB/frame ${nb.cells.toDouble / n}%7.0f cells/frame")

val full = Rect(0, 0, 200, 50)
val lorem = Text.nostyle(("lorem ipsum dolor sit amet " * 40).grouped(90).mkString("\n")) // ~1080 chars
val items = (0 until 50).map(i => ListWidget.Item(Text.nostyle(s"item $i"))).toArray
val rows = (0 until 100).map(i => TableWidget.Row(Array(TableWidget.Cell(Text.nostyle(s"r$i")), TableWidget.Cell(Text.nostyle(s"value $i"))))).toArray
val widths = Array[Constraint](Constraint.Length(10), Constraint.Min(0))
val ls = ListWidget.State(selected = Some(0)); val ts = TableWidget.State(selected = Some(0))

bench("empty draw (autoresize+diff+reset)")((_, _) => ())
bench("BlockWidget ALL borders full screen")((f, _) => f.renderWidget(BlockWidget(borders = Borders.ALL), full))
bench("Paragraph ~1080 chars, wrap, 100x50")((f, _) => f.renderWidget(ParagraphWidget(lorem, wrap = Some(ParagraphWidget.Wrap(true))), Rect(0, 0, 100, 50)))
bench("Paragraph ~1080 chars, no wrap, 100x50")((f, _) => f.renderWidget(ParagraphWidget(lorem), Rect(0, 0, 100, 50)))
bench("List 50 items, 100x25, moving selection")((f, i) => { ls.select(Some(i % 50)); f.renderStatefulWidget(ListWidget(items = items, highlightSymbol = Some(">> ")), Rect(100, 0, 100, 25))(ls) })
bench("Table 100 rows, 100x25, moving selection")((f, i) => { ts.select(Some(i % 100)); f.renderStatefulWidget(TableWidget(rows = rows, widths = widths), Rect(100, 25, 100, 25))(ts) })
bench("Table 100 rows, fresh widths Array per frame")((f, i) => { ts.select(Some(i % 100)); f.renderStatefulWidget(TableWidget(rows = rows, widths = Array(Constraint.Length(10), Constraint.Min(0))), Rect(100, 25, 100, 25))(ts) })
println(s"LAYOUT_CACHE size after benches = ${Layout.LAYOUT_CACHE.size}")
bench("Gauge full width changing ratio")((f, i) => f.renderWidget(GaugeWidget(ratio = GaugeWidget.Ratio((i % 100) / 100.0), useUnicode = true), Rect(0, 0, 200, 3)))
