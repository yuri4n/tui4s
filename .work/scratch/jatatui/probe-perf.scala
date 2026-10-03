//> using scala 3.9.0
//> using dep com.olvind.jatatui:jatatui-core:0.31.0
//> using dep com.olvind.jatatui:jatatui-widgets:0.31.0
//> using javaOpt -Xmx512m

// Rough per-frame cost of jatatui (Block + Paragraph + List on 120x40) into a TestBackend / no-op backend.
// Run: source .work/env.sh && scala-cli run --server=false .work/scratch/jatatui/probe-perf.scala

import jatatui.core.backend.{Backend, ClearType, TestBackend, WindowSize}
import jatatui.core.buffer.{Buffer, BufferUpdate}
import jatatui.core.layout.{Constraint, Layout, Position, Rect, Size}
import jatatui.core.style.{Color, Modifier, Style}
import jatatui.core.terminal.{Frame, Terminal}
import jatatui.core.text.Line
import jatatui.widgets.block.{Block, BorderType}
import jatatui.widgets.list.{ListItem, ListState, List as JList}
import jatatui.widgets.paragraph.{Paragraph, Wrap}

import java.lang.management.ManagementFactory
import java.util.Optional
import java.util.concurrent.{Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

object Perf:
  private val mx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def alloc(): Long = mx.getThreadAllocatedBytes(Thread.currentThread().threadId())

  final class NoopBackend(w: Int, h: Int) extends Backend:
    var updates = 0L
    def draw(content: java.lang.Iterable[BufferUpdate]): Unit = content.forEach(_ => updates += 1)
    def hideCursor(): Unit = ()
    def showCursor(): Unit = ()
    def getCursorPosition(): Position = Position.ORIGIN
    def setCursorPosition(position: Position): Unit = ()
    def clear(): Unit = ()
    def clearRegion(clearType: ClearType): Unit = ()
    def size(): Size = Size(w, h)
    def windowSize(): WindowSize = WindowSize(Size(w, h), Size(0, 0))
    def flush(): Unit = ()
    def scrollRegionUp(regionStart: Int, regionEnd: Int, lineCount: Int): Unit = ()
    def scrollRegionDown(regionStart: Int, regionEnd: Int, lineCount: Int): Unit = ()

  val loremWords = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua".split(' ')
  val lorem: String = Iterator.continually(loremWords).flatten.take(180).mkString(" ") // ~1100 chars
  val items: java.util.List[ListItem] = (0 until 100).map(i => ListItem.of(s"item number $i")).asJava

  def ui(frame: Frame, state: ListState, rebuild: Boolean): Unit =
    val block = Block.bordered().withBorderType(BorderType.Rounded).withTitle(" perf ")
    val inner = block.inner(frame.area())
    frame.renderWidget(block, frame.area())
    val Array(left, right) = Layout.horizontal(Constraint.Percentage(60), Constraint.Fill(1)).areas(inner, 2): @unchecked
    frame.renderWidget(Paragraph.of(lorem).withWrap(Wrap(true)).withBlock(Block.bordered().withTitle("text")), left)
    val its = if rebuild then (0 until 100).map(i => ListItem.of(s"item number $i")).asJava else items
    val list = JList.of(its).withBlock(Block.bordered().withTitle("list")).withHighlightSymbol("> ")
      .withHighlightStyle(Style.empty().withAddModifier(Modifier.REVERSED).withFg(Color.YELLOW))
    frame.renderStatefulWidget(list, right, state)

  def measure(name: String, frames: Int)(step: Int => Unit): Unit =
    for i <- 0 until 300 do step(i) // warm-up
    System.gc()
    val a0 = alloc(); val t0 = System.nanoTime()
    for i <- 0 until frames do step(i)
    val t1 = System.nanoTime(); val a1 = alloc()
    println(f"$name%-62s ${(t1 - t0) / 1000.0 / frames}%8.1f us/frame ${(a1 - a0) / 1024.0 / frames}%9.1f KiB/frame")

  def main(args: Array[String]): Unit =
    println(s"java.version=${System.getProperty("java.version")} cpus=${Runtime.getRuntime.availableProcessors()} os.arch=${System.getProperty("os.arch")}")
    val W = 120; val H = 40; val N = 500

    // 1. Terminal + TestBackend, selection moves every frame
    locally {
      val be = TestBackend(W, H); val term = Terminal.create(be); val st = ListState.empty()
      measure("A Terminal(TestBackend) 120x40, selection moves", N) { i => st.select(Optional.of[Integer](i % 100)); term.draw(f => ui(f, st, false)) }
    }
    // 2. Terminal + no-op backend, static frame (nothing changes)
    locally {
      val be = NoopBackend(W, H); val term = Terminal.create(be); val st = ListState.of(0, Optional.of[Integer](3))
      measure("B Terminal(no-op) 120x40, identical frames", N) { _ => term.draw(f => ui(f, st, false)) }
      be.updates = 0; term.draw(f => ui(f, st, false)); println(s"   changed cells in an identical frame: ${be.updates}")
    }
    // 3. Terminal + no-op backend, selection moves, list items rebuilt each frame (typical declarative wrapper)
    locally {
      val be = NoopBackend(W, H); val term = Terminal.create(be); val st = ListState.empty()
      measure("C Terminal(no-op) 120x40, selection moves + items rebuilt", N) { i => st.select(Optional.of[Integer](i % 100)); term.draw(f => ui(f, st, true)) }
      be.updates = 0; st.select(Optional.of[Integer](50)); term.draw(f => ui(f, st, true)); println(s"   changed cells when selection moves 49->50 style: ${be.updates}")
    }
    // 4. Render only into a Buffer (no Terminal, no diff)
    locally {
      val buf = Buffer.empty(Rect(0, 0, W, H)); val be = TestBackend(W, H); val term = Terminal.create(be); val st = ListState.empty()
      val frame = term.getFrame()
      measure("D widgets only (render into the back buffer, no diff/reset)", N) { i => st.select(Optional.of[Integer](i % 100)); ui(frame, st, false) }
    }
    // 4b. Each widget alone, rendered into a private Buffer (allocation breakdown)
    locally {
      val buf = Buffer.empty(Rect(0, 0, W, H)); val full = buf.area(); val st = ListState.of(0, Optional.of[Integer](5))
      val half = Rect(1, 1, 70, 38)
      val emptyTerm = Terminal.create(NoopBackend(W, H))
      measure("0 Terminal.draw with an empty callback (diff+reset baseline)", N) { _ => emptyTerm.draw(_ => ()) }
      measure("1 Block.bordered full 120x40", N) { _ => Block.bordered().withTitle(" perf ").render(full, buf) }
      measure("2 Paragraph ~1100 chars wrapped, 70x38", N) { _ => Paragraph.of(lorem).withWrap(Wrap(true)).render(half, buf) }
      measure("3 Paragraph ~1100 chars NOT wrapped, 70x38", N) { _ => Paragraph.of(lorem).render(half, buf) }
      measure("4 List 100 items (prebuilt), 48x38, stateful", N) { _ => JList.of(items).withHighlightSymbol("> ").render(Rect(72, 1, 46, 38), buf, st) }
    }
    // 5. Diff and reset alone
    locally {
      val a = Buffer.empty(Rect(0, 0, W, H)); val b = Buffer.empty(Rect(0, 0, W, H))
      measure("E Buffer.diff 120x40 identical buffers", N) { _ => a.diff(b) }
      measure("F Buffer.reset 120x40", N) { _ => b.reset() }
      measure("G Buffer.setStyle full 120x40 (Style.empty)", N) { _ => b.setStyle(b.area(), Style.empty()) }
      measure("H cellAt over all 4800 cells", N) { _ => var y = 0; while y < H do { var x = 0; while x < W do { b.cellAt(x, y); x += 1 }; y += 1 } }
      measure("I setString 100 ASCII chars", N) { _ => b.setString(0, 0, "x" * 100, Style.empty()) }
    }
    // 6. Layout cache hit vs miss
    locally {
      val r = Rect(0, 0, W, H)
      measure("J Layout.split cache hit (equal Layout rebuilt per call)", N) { _ => Layout.horizontal(Constraint.Percentage(60), Constraint.Fill(1)).split(r) }
      measure("K Layout.split cache miss (new area every call, 3 constraints)", N) { i => Layout.vertical(Constraint.Length(3), Constraint.Min(1), Constraint.Length(1)).split(Rect(0, 0, W, 10 + (i % 5000))) }
    }
    // 7. Concurrency smoke test of the global Layout cache (8 threads x 5000 splits, distinct + shared keys)
    locally {
      val pool = Executors.newFixedThreadPool(8)
      val errors = new java.util.concurrent.atomic.AtomicInteger(0)
      val wrong = new java.util.concurrent.atomic.AtomicInteger(0)
      for t <- 0 until 8 do pool.submit(new Runnable {
        def run(): Unit =
          try
            for i <- 0 until 5000 do
              val h = 10 + ((i * 7 + t) % 2000)
              val res = Layout.vertical(Constraint.Length(3), Constraint.Fill(1)).split(Rect(0, 0, 80, h))
              if res(0).height() != 3 || res(1).height() != h - 3 then wrong.incrementAndGet()
          catch case e: Throwable => { errors.incrementAndGet(); e.printStackTrace() }
      })
      pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
      println(s"L concurrent Layout.split: errors=${errors.get} wrongResults=${wrong.get} cacheCapacity=${Layout.cacheCapacity()}")
    }
