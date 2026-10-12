package tui4s.fixtures

import kyo.*
import kyo.UI.*

/** The Dashboard in plain kyo-ui: tabs (Left / Right), a gauge and a sparkline
  * fed by a 250 ms sampler, a log pane fed by a stream, `p` pauses the sampler
  * (logs go on), `q` quits.
  */
object Dashboard:

    val series = Seq("CPU", "Memory", "Network")

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
        tab: SignalRef[Int],
        metrics: SignalRef[Metrics],
        paused: SignalRef[Boolean],
        logs: SignalRef[Chunk[(Int, String)]],
    )

    def init(using Frame): State < Sync =
      for
          tab     <- Signal.initRef(0)
          metrics <- Signal.initRef(Metrics.empty)
          paused  <- Signal.initRef(false)
          logs    <- Signal.initRef(Chunk.empty[(Int, String)])
      yield State(tab, metrics, paused, logs)

    /** One sampler tick with given values (the bench calls this directly). */
    def tick(s: State, values: Chunk[Double])(using Frame): Unit < Sync =
      s.metrics
        .updateAndGet(m =>
          Metrics(
            values,
            m.history.zip(values).map((h, v) => (h :+ v).takeRight(40)),
          ),
        )
        .unit

    /** The sampler: a random walk every 250 ms, skipped while paused. */
    def sampler(s: State)(using Frame): Unit < Async =
      Loop.forever {
        Async.sleep(250.millis).andThen(s.paused.get).map { p =>
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

    /** A log stream: one line every 400 ms. */
    def logStream(using Frame): Stream[String, Async] =
      Stream[String, Async] {
        Loop(1) { i =>
          Async
            .sleep(400.millis)
            .andThen(
              Emit.valueWith(Chunk(s"event #$i: request served"))(
                Loop.continue(i + 1),
              ),
            )
        }
      }

    def overview(s: State): UI =
      s.tab.render { t =>
        div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
          h2(s"${series(t)} usage"),
          Widgets.gauge(s.metrics.map(_.now(t)), 30, Style.Color.green),
          Widgets.sparkline(s.metrics.map(_.history(t)), Style.Color.blue),
        )
      }

    def logPane(s: State): UI =
      div.style(
        _.border(1.px, Style.Color.slate).padding(0.px, 1.px).height(10.px),
      )(
        h3("Log"),
        ul(s.logs.foreachKeyed(_._1.toString)((_, line) => li(line))),
      )

    def view(s: State): UI =
      div(
        div
          .style(_.row.gap(2.px))(h1("Dashboard"), Widgets.tabs(s.tab, series)),
        overview(s),
        logPane(s),
        span(
          s.paused.map(p =>
            if p then "sampler PAUSED (p resumes)"
            else "sampler running (p pauses)  ←/→ tabs  q quits",
          ),
        )
          .style(_.color(Style.Color.gray)),
      )

    val app: UI < (Async & Scope) =
      for
          s <- init
          _ <- Fiber.init(sampler(s))
          _ <- Fiber.init(
                 logStream.foreach(line =>
                   s.logs.updateAndGet(l =>
                     (l :+ (l.lastOption.fold(1)(_._1 + 1), line)).takeRight(6),
                   ),
                 ),
               )
      yield view(s)
end Dashboard
