package tui4s.bench

import jatatui.core.backend.TestBackend
import kyo.*
import tui4s.Key
import tui4s.spi.Headless
import tui4s.testkit.ScriptedInput

/** B1 (SPEC 7.3): the runtime cost per input event in a burst. 10 000 keys are
  * queued at once for the Counter on `spi.Headless` (fps 60, inbox 1024); the
  * time from the start to the end of the run, divided by the keys. Budget: <=
  * 10 us/event amortized.
  *
  * `sbt "bench/runMain tui4s.bench.BurstProbe [rounds]"`
  */
object BurstProbe:
    def main(args: Array[String]): Unit =
        val rounds             = args.headOption.map(_.toInt).getOrElse(8)
        val keys               = 10_000
        val app                = examples.counter.CounterApp
        val runs               = (1 to rounds).map { _ =>
          val input   =
            ScriptedInput((Seq.fill(keys)(Key.Char('+')) :+ Key.Char('q'))*)
          val backend = TestBackend(120, 40)
          val t0      = java.lang.System.nanoTime()
          val (n, st) = Probes.block(60.seconds)(
            Scope.run(
              Headless
                .start(app, backend, input)
                .map(h => h.await.map(n => h.stats.map(st => (n, st)))),
            ),
          )
          val micros  = (java.lang.System.nanoTime() - t0) / 1000.0
          assert(n == keys, s"lost keys: $n of $keys")
          (micros / keys, st.frames, st.batches)
        }
        // The first rounds warm the JVM up.
        val timed              = runs.drop(rounds / 2)
        val perKey             = timed.map(_._1)
        Probes.record(
          "burst",
          "keys"             -> keys,
          "rounds"           -> timed.size,
          "usPerEventMedian" -> Probes.quantile(perKey, 0.5),
          "usPerEventBest"   -> perKey.min,
          "framesMedian"     -> Probes.quantile(timed.map(_._2.toDouble), 0.5),
          "batchesMedian"    -> Probes.quantile(timed.map(_._3.toDouble), 0.5),
          "budgetUsPerEvent" -> 10.0,
          "withinBudget"     -> (Probes.quantile(perKey, 0.5) <= 10.0),
        )
    end main
end BurstProbe
