//> using scala 3.7.4
//> using dep com.olvind.tui::tui:0.0.10
//> using javaOpt -Xmx512m

// Headless probe for tui-scala 0.0.10. No TTY is needed for sections A-H.
// Run: scala-cli run .work/scratch/tui-probe.sc

import tui.*
import tui.widgets.*
import scala.collection.mutable

def lines(buf: Buffer): Seq[String] =
  buf.content.grouped(buf.area.width).map(_.map(_.symbol.str).mkString).toSeq

def show(title: String, buf: Buffer): Unit =
  println(s"--- $title (${buf.area})")
  lines(buf).foreach(l => println(s"|$l|"))

// ---------- A. Headless render: BlockWidget + ParagraphWidget into Buffer.empty ----------
val area = Rect(0, 0, 20, 5)
val buf = Buffer.empty(area)
val block = BlockWidget(
  title = Some(Spans.nostyle("Hello")),
  borders = Borders.ALL,
  borderType = BlockWidget.BorderType.Rounded
)
val para = ParagraphWidget(
  text = Text.nostyle("tui-scala renders without a TTY"),
  block = Some(block),
  wrap = Some(ParagraphWidget.Wrap(trim = true))
)
para.render(area, buf)
show("A. Block+Paragraph into Buffer.empty(Rect(0,0,20,5))", buf)

// Compare with Buffer.withLines (the upstream test idiom)
val expected = Buffer.withLines(
  "╭Hello─────────────╮",
  "│tui-scala renders │",
  "│without a TTY     │",
  "│                  │",
  "╰──────────────────╯"
)
println(s"A. equals Buffer.withLines(...) via diff: ${expected.diff(buf).isEmpty}")

// ---------- B. Full Terminal.draw with an in-memory backend (no JNI) ----------
// Upstream tests/TestBackend.scala stores the Cell REFERENCES it gets from diff. We show both variants.
final class MemBackend(w: Int, h: Int, cloneCells: Boolean) extends Backend {
  val buffer: Buffer = Buffer.empty(Rect(0, 0, w, h))
  var draws = 0
  var cellsWritten = 0
  def draw(content: Array[(Int, Int, Cell)]): Unit =
    draws += 1
    cellsWritten += content.length
    content.foreach { case (x, y, c) => buffer.set(x, y, if cloneCells then c.clone() else c) }
  def hideCursor(): Unit = ()
  def showCursor(): Unit = ()
  def getCursor(): (Int, Int) = (0, 0)
  def setCursor(x: Int, y: Int): Unit = ()
  def clear(): Unit = buffer.reset()
  def size(): Rect = Rect(0, 0, w, h)
  def flush(): Unit = ()
}

def twoFrames(cloneCells: Boolean): MemBackend =
  val be = MemBackend(12, 3, cloneCells)
  val term = Terminal.init(be)
  term.draw(f => f.renderWidget(ParagraphWidget(Text.nostyle("frame-1 AAA\nstays")), f.size))
  // Frame 2 only changes the first line; line 2 ("stays") is identical, so it is NOT in the diff.
  term.draw(f => f.renderWidget(ParagraphWidget(Text.nostyle("frame-2 BBB\nstays")), f.size))
  // Frame 3 is identical to frame 2 -> empty diff, but Terminal resets the old back buffer.
  term.draw(f => f.renderWidget(ParagraphWidget(Text.nostyle("frame-2 BBB\nstays")), f.size))
  be

val aliased = twoFrames(cloneCells = false)
show("B1. backend that stores cell refs (like upstream TestBackend) after 3 frames", aliased.buffer)
val cloned = twoFrames(cloneCells = true)
show("B2. backend that clones cells after 3 frames", cloned.buffer)
println(s"B. cells sent per frame total=${cloned.cellsWritten} draws=${cloned.draws}")

// ---------- C. Borders.remove bug ----------
val allMinusTop = Borders.ALL - Borders.TOP
println(s"C. Borders.ALL=${Borders.ALL.bits.toBinaryString} TOP=${Borders.TOP.bits.toBinaryString} ALL-TOP=${allMinusTop.bits.toBinaryString} (expected ${(Borders.ALL.bits & ~Borders.TOP.bits).toBinaryString}) contains TOP? ${allMinusTop.contains(Borders.TOP)}")

// ---------- D. Layout cache growth (Array in case class key) ----------
Layout.LAYOUT_CACHE.clear()
val r = Rect(0, 0, 80, 24)
for _ <- 1 to 1000 do
  Layout(direction = Direction.Vertical, constraints = Array(Constraint.Length(3), Constraint.Min(0))).split(r)
println(s"D. LAYOUT_CACHE.size after 1000 splits of an 'equal' layout built per frame = ${Layout.LAYOUT_CACHE.size}")
Layout.LAYOUT_CACHE.clear()
val stableConstraints = Array[Constraint](Constraint.Length(3), Constraint.Min(0))
for _ <- 1 to 1000 do
  Layout(direction = Direction.Vertical, constraints = stableConstraints).split(r)
println(s"D. LAYOUT_CACHE.size when the constraints Array instance is reused = ${Layout.LAYOUT_CACHE.size}")

// ---------- E. Layout.split returns the cached mutable Array ----------
val lay = Layout(constraints = stableConstraints)
val first = lay.split(r)
first(0) = Rect(9, 9, 9, 9) // caller mutates its result
val second = lay.split(r)
println(s"E. second split(0) after caller mutated first result: ${second(0)} (same array instance: ${first eq second})")

// ---------- F. Buffer.diff returns references into the next buffer ----------
val prev = Buffer.empty(Rect(0, 0, 3, 1))
val next = Buffer.empty(Rect(0, 0, 3, 1))
next.setString(0, 0, "abc", Style.DEFAULT)
val upd = prev.diff(next)
next.get(0, 0).setSymbol("Z")
println(s"F. diff update[0] symbol after mutating next buffer: '${upd(0)._3.symbol.str}' (diff size ${upd.length})")

// ---------- G. ListWidget render mutates its State ----------
val items = (0 until 50).map(i => ListWidget.Item(Text.nostyle(s"item $i"))).toArray
val st = ListWidget.State(selected = Some(40))
val before = st.copy()
ListWidget(items = items).render(Rect(0, 0, 10, 5), Buffer.empty(Rect(0, 0, 10, 5)), st)
println(s"G. ListWidget.State before=$before after render=$st")

// ---------- H. Out-of-bounds access ----------
try { Buffer.empty(Rect(0, 0, 2, 2)).get(5, 5); println("H. no exception") }
catch { case e: Throwable => println(s"H. get(5,5) on 2x2 -> ${e.getClass.getName}: ${e.getMessage.take(80)}") }

// ---------- I. Frame cost: 200x50, block + paragraph + list + table, no-op backend ----------
import java.lang.management.ManagementFactory
val tmx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
val tid = Thread.currentThread().threadId()
final class NullBackend(w: Int, h: Int) extends Backend {
  var cells = 0L
  def draw(content: Array[(Int, Int, Cell)]): Unit = cells += content.length
  def hideCursor(): Unit = (); def showCursor(): Unit = (); def getCursor(): (Int, Int) = (0, 0)
  def setCursor(x: Int, y: Int): Unit = (); def clear(): Unit = (); def size(): Rect = Rect(0, 0, w, h); def flush(): Unit = ()
}
val nb = NullBackend(200, 50)
val term = Terminal.init(nb)
val loremText = Text.nostyle(("lorem ipsum dolor sit amet " * 40).grouped(90).mkString("\n"))
val rows = (0 until 100).map(i => TableWidget.Row(Array(TableWidget.Cell(Text.nostyle(s"r$i")), TableWidget.Cell(Text.nostyle(s"value $i"))))).toArray
val widths = Array[Constraint](Constraint.Length(10), Constraint.Min(0))
val cols = Array[Constraint](Constraint.Percentage(50), Constraint.Percentage(50))
val listState = ListWidget.State(selected = Some(0))
val tableState = TableWidget.State(selected = Some(0))
def frame(i: Int): Unit =
  term.draw { f =>
    val chunks = Layout(direction = Direction.Horizontal, constraints = cols).split(f.size)
    f.renderWidget(ParagraphWidget(loremText, block = Some(BlockWidget(title = Some(Spans.nostyle(s"frame $i")), borders = Borders.ALL)), wrap = Some(ParagraphWidget.Wrap(true))), chunks(0))
    listState.select(Some(i % 50))
    f.renderStatefulWidget(ListWidget(items = items, block = Some(BlockWidget(borders = Borders.ALL)), highlightSymbol = Some(">> ")), Rect(chunks(1).x, 0, chunks(1).width, 25))(listState)
    tableState.select(Some(i % 100))
    f.renderStatefulWidget(TableWidget(rows = rows, widths = widths, block = Some(BlockWidget(borders = Borders.ALL))), Rect(chunks(1).x, 25, chunks(1).width, 25))(tableState)
  }
for i <- 0 until 3000 do frame(i) // warm-up
val n = 2000
val a0 = tmx.getThreadAllocatedBytes(tid)
val t0 = System.nanoTime()
nb.cells = 0
for i <- 0 until n do frame(i)
val t1 = System.nanoTime()
val a1 = tmx.getThreadAllocatedBytes(tid)
println(f"I. 200x50 frame: ${(t1 - t0) / 1e3 / n}%.1f us/frame, ${(a1 - a0).toDouble / n / 1024}%.1f KiB allocated/frame, ${nb.cells.toDouble / n}%.0f changed cells/frame")

// Diff alone on a 200x50 buffer with no changes
val b1 = Buffer.empty(Rect(0, 0, 200, 50)); val b2 = Buffer.empty(Rect(0, 0, 200, 50))
for _ <- 0 until 2000 do b1.diff(b2)
val d0 = System.nanoTime(); val da0 = tmx.getThreadAllocatedBytes(tid)
for _ <- 0 until 5000 do b1.diff(b2)
val d1 = System.nanoTime(); val da1 = tmx.getThreadAllocatedBytes(tid)
println(f"I. Buffer.diff 200x50 (10000 cells, no changes): ${(d1 - d0) / 1e3 / 5000}%.1f us, ${(da1 - da0).toDouble / 5000}%.0f bytes/call")

// Buffer.setString cost (grapheme segmentation + Grapheme alloc)
val sb = Buffer.empty(Rect(0, 0, 200, 1)); val s200 = "x" * 200
for _ <- 0 until 20000 do sb.setString(0, 0, s200, Style.DEFAULT)
val s0 = System.nanoTime(); val sa0 = tmx.getThreadAllocatedBytes(tid)
for _ <- 0 until 20000 do sb.setString(0, 0, s200, Style.DEFAULT)
val s1 = System.nanoTime(); val sa1 = tmx.getThreadAllocatedBytes(tid)
println(f"I. setString 200 ASCII chars: ${(s1 - s0) / 1e3 / 20000}%.2f us, ${(sa1 - sa0).toDouble / 20000}%.0f bytes/call (${(sa1 - sa0).toDouble / 20000 / 200}%.0f B/char)")

// ---------- J. Concurrent Layout.split from several threads (global unsynchronized HashMap) ----------
Layout.LAYOUT_CACHE.clear()
val errors = new java.util.concurrent.atomic.AtomicInteger(0)
val firstErr = new java.util.concurrent.atomic.AtomicReference[String]("")
val threads = (0 until 8).map { t =>
  val th = new Thread(() =>
    var i = 0
    while i < 20000 do
      try Layout(constraints = Array(Constraint.Length(i % 7), Constraint.Min(0))).split(Rect(0, 0, 80 + t, 24 + (i % 50)))
      catch { case e: Throwable => errors.incrementAndGet(); firstErr.compareAndSet("", e.toString.take(120)) }
      i += 1
  )
  th.setDaemon(true); th.start(); th
}
threads.foreach(_.join(60000))
println(s"J. concurrent split: errors=${errors.get} first=${firstErr.get} cacheSize=${Layout.LAYOUT_CACHE.size} (160000 distinct keys inserted) alive=${threads.count(_.isAlive)}")

// ---------- K. Native lib load without a TTY ----------
try
  val jni = new tui.crossterm.CrosstermJni()
  println("K. CrosstermJni loaded (native lib extracted and loaded)")
  try println(s"K. terminalSize() = ${jni.terminalSize()}")
  catch { case e: Throwable => println(s"K. terminalSize() without TTY -> ${e.getClass.getName}: ${e.getMessage}") }
  try println(s"K. poll(0) = ${jni.poll(new tui.crossterm.Duration(0, 0))}")
  catch { case e: Throwable => println(s"K. poll(0) without TTY -> ${e.getClass.getName}: ${e.getMessage}") }
catch { case e: Throwable => println(s"K. load failed: $e") }
println("DONE")
