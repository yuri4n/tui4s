package tui4s.bench

import jatatui.core.backend.TestBackend
import kyo.*
import tui4s.Key
import tui4s.spi.Headless

/** B7 (SPEC 7.3): quit latency: from the `q` key to the end of the run (the
  * release joins the input thread, which can be inside one poll). The input
  * port honours its poll timeout (25 ms). Budget: <= 2 x pollMillis + 10 ms (60
  * ms).
  *
  * `sbt "bench/runMain tui4s.bench.QuitProbe [rounds]"`
  */
object QuitProbe:
    def main(args: Array[String]): Unit =
        val rounds       = args.headOption.map(_.toInt).getOrElse(30)
        val app          = examples.counter.CounterApp
        val millis       = (1 to rounds).map { _ =>
          val input = TimedInput()
          Probes.block(30.seconds)(
            Fiber
              .initUnscoped(Headless.run(app, TestBackend(80, 24), input))
              .map { fiber =>
                Async
                  .sleep(100.millis)
                  .andThen(Sync.defer {
                    val t0 = java.lang.System.nanoTime()
                    input.push(Key.Char('q'))
                    t0
                  })
                  .map(t0 =>
                    fiber.get.andThen(
                      Sync.defer((java.lang.System.nanoTime() - t0) / 1e6),
                    ),
                  )
              },
          )
        }
        val timed        = millis.drop(rounds / 3)
        val p50          = Probes.quantile(timed, 0.5)
        val budget       = 2 * 25 + 10.0
        Probes.record(
          "quit",
          "rounds"       -> timed.size,
          "p50ms"        -> p50,
          "maxMs"        -> timed.max,
          "budgetMs"     -> budget,
          "withinBudget" -> (timed.max <= budget),
        )
    end main
end QuitProbe
