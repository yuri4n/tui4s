package examples.dashboard

// claude 2026-10-13: changed (T2, adapt): the widgets are in their own
// package; tui4s.Tui (the prototype had tui4s.kyoui.Tui)
import examples.widgets.{Tabs, Widgets}
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp}

/** The Dashboard in plain kyo-ui: one tab per series (CPU, Memory, Network), a
  * gauge and a sparkline fed by a sampler (every 250 ms), a log pane fed by a
  * stream (a line every 400 ms). ArrowLeft and ArrowRight change the tab; the
  * tabs are buttons in one focus group, so Tab moves to the next tab and Enter
  * or Space selects it; `p` pauses the sampler (the log goes on); `q` quits.
  * The log pane is a viewport that scrolls, with the newest line first: Tab
  * after the last tab reaches it, and the arrows, PageUp, PageDown, Home and
  * End scroll it. The widgets (the gauge, the sparkline, the tabs) are in
  * `examples.widgets.Widgets`.
  */
// claude 2026-10-19: changed: the Scaladoc names the log pane that scrolls
// (L1a)
object Dashboard:

    /** The series: one tab for each. The tabs need a first label. */
    // claude 2026-10-19: changed: the first series and the others, for the
    // tabs (P1b, A5 g)
    val firstSeries: String      = "CPU"
    val otherSeries: Seq[String] = Seq("Memory", "Network")
    val series: Seq[String]      = firstSeries +: otherSeries

    /** One sample of every series, and the last 40 samples of each. One ref, so
      * one tick is one atomic change.
      */
    final case class Metrics(now: Chunk[Double], history: Chunk[Chunk[Double]])
        derives CanEqual

    object Metrics:
        val empty: Metrics = Metrics(
          Chunk.fill(series.size)(0.0),
          Chunk.fill(series.size)(Chunk.empty),
        )

    final case class State(
        metrics: SignalRef[Metrics],
        paused: SignalRef[Boolean],
        logs: SignalRef[Chunk[(Int, String)]],
    )

    def init(using Frame): State < Sync =
      for
          metrics <- Signal.initRef(Metrics.empty)
          paused  <- Signal.initRef(false)
          logs    <- Signal.initRef(Chunk.empty[(Int, String)])
      yield State(metrics, paused, logs)

    /** One sampler tick with given values (a test or a benchmark can call it
      * directly).
      */
    // claude 2026-10-13: changed (T2, review R1-6): there is no bench yet
    def tick(s: State, values: Chunk[Double])(using Frame): Unit < Sync =
      s.metrics
        .updateAndGet(m =>
          Metrics(
            values,
            m.history.zip(values).map((h, v) => (h :+ v).takeRight(40)),
          ),
        )
        .unit

    /** The sampler: a random walk every `every`, skipped while paused. */
    // claude 2026-10-13: changed (T2): the period is a parameter
    def sampler(s: State, every: Duration = 250.millis)(using
        Frame,
    ): Unit < Async =
      Loop.forever {
        Async.sleep(every).andThen(s.paused.get).map { p =>
          if p then ()
          else
              s.metrics.get.map { m =>
                Kyo
                  .foreach(m.now)(v =>
                    Random.nextDouble
                      .map(r => (v + (r - 0.5) * 0.3).max(0).min(1)),
                  )
                  .map(vs => tick(s, vs))
              }
        }
      }

    /** A log stream: one line every `every`. */
    // claude 2026-10-13: changed (T2): the period is a parameter
    def logStream(every: Duration = 400.millis)(using
        Frame,
    ): Stream[String, Async] =
      Stream[String, Async] {
        Loop(1) { i =>
          Async
            .sleep(every)
            .andThen(
              Emit.valueWith(Chunk(s"event #$i: request served"))(
                Loop.continue(i + 1),
              ),
            )
        }
      }

    def overview(s: State, t: Int): UI =
      div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
        h2(s"${series(t)} usage"),
        Widgets.gauge(s.metrics.map(_.now(t)), 30, Style.Color.green),
        Widgets.sparkline(s.metrics.map(_.history(t)), Style.Color.blue),
      )

    /** The lines that the log keeps. */
    // claude 2026-10-19: created (L1a)
    val logLines: Int = 50

    /** The log pane: its title, and its lines in a viewport 9 rows high (7
      * lines inside the border) that scrolls (`overflowY: scroll`), the newest
      * line first. The viewport is focusable, and it shows a scroll indicator
      * when the lines do not fit; a focused viewport has a heavy border.
      */
    // claude 2026-10-19: changed: a viewport that scrolls (L1a)
    def logPane(s: State): UI =
      div(
        h3("Log"),
        div
          .id("log")
          .style(
            _.border(1.px, Style.Color.slate)
              .padding(0.px, 1.px)
              .height(9.px)
              .overflowY(_.scroll),
          )(
            ul(
              s.logs
                .map(_.reverse)
                .foreachKeyed(_._1.toString)((_, line) => li(line)),
            ),
          ),
      )

    def view(s: State): UI =
      div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('p'), _, _) =>
          s.paused.updateAndGet(!_)
        case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
        case _                                       => ()
      }(
        new Tabs(firstSeries, otherSeries*)(
          heading = h1("Dashboard"),
          panel = overview(s, _),
        ),
        logPane(s),
        span(
          s.paused.map(p =>
            if p then "sampler PAUSED (p resumes)"
            else "sampler running (p pauses)  ←/→ tabs  q quits",
          ),
        )
          .style(_.color(Style.Color.gray)),
      )

    /** The app: the state, the sampler and the log, every `sampleEvery` and
      * `logEvery` (a test passes short periods). The two fibers end when the
      * `Scope` of the UI closes, at the end of the run.
      */
    // claude 2026-10-13: changed (T2): a def with the two periods (the
    // prototype's val app with 250 ms and 400 ms)
    def app(
        sampleEvery: Duration = 250.millis,
        logEvery: Duration = 400.millis,
    ): UI < (Async & Scope) =
      for
          s <- init
          _ <- Fiber.init(sampler(s, sampleEvery))
          _ <- Fiber.init(
                 logStream(logEvery).foreach(line =>
                   // claude 2026-10-19: changed: the log keeps `logLines`
                   // lines, so the pane has some to scroll (L1a)
                   s.logs.updateAndGet(l =>
                     (l :+ (l.lastOption.fold(1)(_._1 + 1), line))
                       .takeRight(logLines),
                   ),
                 ),
               )
      yield view(s)
end Dashboard

/** Runs the Dashboard in the real terminal. Start it with its start script, not
  * with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/dashboard
  * }}}
  */
// claude 2026-10-13: created (T2, RC-33)
object DashboardApp extends TuiApp:
    def ui: UI < (Async & Scope) = Dashboard.app()
