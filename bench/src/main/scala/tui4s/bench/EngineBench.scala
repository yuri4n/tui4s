package tui4s.bench

import examples.dashboard.Dash
import examples.dashboard.DashboardApp
import jatatui.core.backend.TestBackend
import jatatui.core.terminal.Terminal
import org.openjdk.jmh.annotations.*
import tui4s.Next
import tui4s.View
import tui4s.internal.CountingBackend
import tui4s.internal.Engine
import tui4s.internal.Fold
import tui4s.internal.Item
import tui4s.internal.Painter
import tui4s.internal.Supervisor

/** B2 (SPEC 7.3): the framework's own work for one change of the Dashboard
  * whose view is equal: the fold, the sources diff, `view`, and the view
  * equality (no frame). Budget: <= 50 us.
  *
  * `sbt "bench/Jmh/run -i 5 -wi 5 -w 1s -r 1s -f 1 tui4s.bench.EngineBench"`
  */
@State(org.openjdk.jmh.annotations.Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(java.util.concurrent.TimeUnit.MICROSECONDS)
class EngineBench:
    private var engine: Engine[Dash]                = null.asInstanceOf[Engine[Dash]]
    private var terminal: Terminal[CountingBackend] =
      null.asInstanceOf[Terminal[CountingBackend]]
    private var equalChange: Seq[Item]              = Nil
    private var newSample: Seq[Item]                = Nil

    private def draw(view: View): Int =
        val _ = terminal.draw(frame => engine.painter.paint(view, frame))
        terminal.backend().lastCount

    @Setup
    def setup(): Unit =
        val app                        = DashboardApp
        engine = Engine(
          app,
          app.options,
          Painter(app.options.theme),
          Supervisor[Dash](),
          null,
        )
        terminal = Terminal.create(CountingBackend(TestBackend(120, 40)))
        require(engine.start() && engine.syncWork())
        require(engine.firstFrame(120, 40, draw))
        // A screen with content: 40 samples and 30 log lines.
        val fill: Dash => Next[Dash]   = d =>
          (1 to 40)
            .foldLeft(d)((d, i) => d.sample((i % 10) / 10.0))
            .copy(logs = Vector.tabulate(30)(i => s"GET /api/items/$i 200"))
        discard(step(Seq(Fold(Fold.Direct, fill))))
        val touch: Dash => Next[Dash]  = d => d.copy()
        equalChange = Seq(Fold(Fold.Direct, touch))
        var flip                       = false
        val sample: Dash => Next[Dash] = d =>
            flip = !flip
            d.copy(cpu = if flip then 0.25 else 0.75)
        newSample = Seq(Fold(Fold.Direct, sample))
    end setup

    private def step(batch: Seq[Item]): Boolean =
        discard(engine.fold(batch))
        discard(engine.syncWork())
        engine.sup.toStart.clear()
        engine.sup.toStop.clear()
        engine.drawIfNeeded(draw)

    /** A new state with an equal view: no frame. */
    @Benchmark
    def equalView(): Boolean = step(equalChange)

    /** A new gauge value: one frame at 120x40, for comparison. */
    @Benchmark
    def changedView(): Boolean = step(newSample)

    private inline def discard[A](a: A): Unit =
        val _ = a
end EngineBench
