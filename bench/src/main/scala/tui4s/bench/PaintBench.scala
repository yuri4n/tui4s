package tui4s.bench

import examples.dashboard.Dash
import examples.dashboard.DashboardApp
import examples.todo.Item
import examples.todo.Pane
import examples.todo.Todo
import examples.todo.TodoApp
import jatatui.core.backend.TestBackend
import jatatui.core.terminal.Terminal
import org.openjdk.jmh.annotations.*
import tui4s.View
import tui4s.internal.CountingBackend
import tui4s.internal.Painter

/** B3 (SPEC 7.3): one full frame at 120x40 on a `TestBackend` (paint, jatatui
  * diff, flush) for the Counter, the Todo with 100 items, and the Dashboard.
  * Two views alternate, so each frame changes some cells. Budget: <= 0.5 / 2.0
  * / 1.5 ms and <= 2 MiB allocated per frame (`-prof gc`).
  *
  * `sbt "bench/Jmh/run -i 5 -wi 5 -w 1s -r 1s -f 1 -prof gc
  * tui4s.bench.PaintBench"`
  */
@State(org.openjdk.jmh.annotations.Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(java.util.concurrent.TimeUnit.MICROSECONDS)
class PaintBench:
    private val painter                             = Painter(tui4s.Theme())
    private var terminal: Terminal[CountingBackend] =
      null.asInstanceOf[Terminal[CountingBackend]]
    private var counter: Array[View]                = Array.empty
    private var todo: Array[View]                   = Array.empty
    private var dashboard: Array[View]              = Array.empty
    private var i                                   = 0

    @Setup
    def setup(): Unit =
        terminal = Terminal.create(CountingBackend(TestBackend(120, 40)))
        val counterApp = examples.counter.CounterApp
        counter = Array(counterApp.view(41), counterApp.view(42))
        val items      =
          Vector.tabulate(100)(n => Item(s"item number $n", n % 3 == 0))
        val t          = Todo(items = items, ready = true, status = "100 loaded")
          .copy(focus = Todo().focus.next)
        todo = Array(
          TodoApp.view(t.copy(selected = 10)),
          TodoApp.view(t.copy(selected = 11)),
        )
        val d          = (1 to 120)
          .foldLeft(Dash())((d, n) => d.sample((n % 10) / 10.0))
          .copy(logs = Vector.tabulate(60)(n => s"GET /api/items/$n 200"))
        dashboard =
          Array(DashboardApp.view(d), DashboardApp.view(d.sample(0.33)))
        require(t.focus.is(Pane.Items))
    end setup

    private def frame(views: Array[View]): Int =
        i += 1
        val v = views(i & 1)
        val _ = terminal.draw(frame => painter.paint(v, frame))
        terminal.backend().lastCount

    @Benchmark
    def counterFrame(): Int = frame(counter)

    @Benchmark
    def todoFrame(): Int = frame(todo)

    @Benchmark
    def dashboardFrame(): Int = frame(dashboard)
end PaintBench
