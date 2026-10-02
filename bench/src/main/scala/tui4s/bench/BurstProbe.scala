package tui4s.bench

import jatatui.core.backend.TestBackend
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import tui4s.App
import tui4s.Key
import tui4s.spi.Headless
import tui4s.testkit.ScriptedInput

/** B1 (SPEC 7.3): the runtime cost per input event in a burst. 10 000 keys are
  * queued at once for the Counter on `spi.Headless` (fps 60, inbox 1024), then
  * `q`. The time from the start to the fold of `q`, divided by the keys. The
  * release after `q` (stopping the sources, joining the input thread, which can
  * wait one poll timeout) is not counted: B7 (`QuitProbe`) measures it. Budget:
  * <= 10 us/event amortized.
  *
  * `sbt "bench/runMain tui4s.bench.BurstProbe [rounds]"`
  */
object BurstProbe:
    def main(args: Array[String]): Unit =
        val rounds              = args.headOption.map(_.toInt).getOrElse(8)
        val keys                = 10_000
        val counter             = examples.counter.CounterApp
        val runs                = (1 to rounds).map { _ =>
          val input   =
            ScriptedInput((Seq.fill(keys)(Key.Char('+')) :+ Key.Char('q'))*)
          val backend = TestBackend(120, 40)
          // The Counter, plus the time when `update` gets `q`.
          val quitAt  = AtomicLong(0L)
          val app     = App[Int](
            counter.init,
            counter.view,
            (n, e) =>
                if e == Key.Char('q') then
                    quitAt.set(java.lang.System.nanoTime())
                counter.update(n, e)
            ,
            options = counter.options,
          )
          val t0      = java.lang.System.nanoTime()
          val (n, st) = Probes.block(60.seconds)(
            Scope.run(
              Headless
                .start(app, backend, input)
                .map(h => h.await.map(n => h.stats.map(st => (n, st)))),
            ),
          )
          val micros  = (quitAt.get - t0) / 1000.0
          val total   = (java.lang.System.nanoTime() - t0) / 1000.0
          assert(n == keys, s"lost keys: $n of $keys")
          (micros / keys, st.frames, st.batches, (total - micros) / 1000.0)
        }
        // The first rounds warm the JVM up.
        val timed               = runs.drop(rounds / 2)
        val perKey              = timed.map(_._1)
        Probes.record(
          "burst",
          "keys"             -> keys,
          "rounds"           -> timed.size,
          "usPerEventMedian" -> Probes.quantile(perKey, 0.5),
          "usPerEventBest"   -> perKey.min,
          "framesMedian"     -> Probes.quantile(timed.map(_._2.toDouble), 0.5),
          "batchesMedian"    -> Probes.quantile(timed.map(_._3.toDouble), 0.5),
          "releaseMsMedian"  -> Probes.quantile(timed.map(_._4), 0.5),
          "budgetUsPerEvent" -> 10.0,
          "withinBudget"     -> (Probes.quantile(perKey, 0.5) <= 10.0),
        )
    end main
end BurstProbe
