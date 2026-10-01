package tui4s.bench

import kyo.*
import tui4s.Key
import tui4s.spi.Headless

/** B4 (SPEC 7.3): key-to-flush latency of an idle app: the time from
  * `InputPort.read` of a key to the `Backend.flush` of its frame. One key every
  * 20 ms, so each key arrives at an idle app. Budget: p50 <= 1 ms, p99 <= 3 ms
  * plus one paint.
  *
  * `sbt "bench/runMain tui4s.bench.LatencyProbe [keys]"`
  */
object LatencyProbe:
    def main(args: Array[String]): Unit =
        val keys            = args.headOption.map(_.toInt).getOrElse(400)
        val app             = examples.counter.CounterApp
        val input           = TimedInput()
        val backend         = FlushTimed(120, 40)
        val samples         = Probes.block(5.minutes)(
          Scope.run(
            Headless.start(app, backend, input).map { h =>
              Sync
                .defer {
                  val out = Vector.newBuilder[Double]
                  var i   = 0
                  while i < keys do
                      Thread.sleep(20)
                      val before = backend.flushes
                      input.push(Key.Char(if i % 2 == 0 then '+' else '-'))
                      val until  = java.lang.System.nanoTime() + 1_000_000_000L
                      while backend.flushes == before &&
                          java.lang.System.nanoTime() < until
                      do Thread.onSpinWait()
                      if backend.flushes > before then
                          out += (backend.flushedAt - input.readAt) / 1000.0
                      i += 1
                  end while
                  out.result()
                }
                .map(s => h.stop.andThen(s))
            },
          ),
        )
        // The first quarter warms the JVM up.
        val timed           = samples.drop(samples.size / 4)
        val p50             = Probes.quantile(timed, 0.5)
        val p99             = Probes.quantile(timed, 0.99)
        Probes.record(
          "latency",
          "keys"         -> timed.size,
          "p50us"        -> p50,
          "p99us"        -> p99,
          "maxUs"        -> timed.max,
          "budgetP50us"  -> 1000.0,
          "budgetP99us"  -> 3000.0,
          "withinBudget" -> (p50 <= 1000.0 && p99 <= 3000.0),
        )
    end main
end LatencyProbe
