package tui4s.bench

import examples.dashboard.Dash
import examples.dashboard.DashboardApp
import jatatui.core.backend.TestBackend
import kyo.*
import tui4s.*
import tui4s.spi.Headless
import tui4s.testkit.ScriptedInput

/** The Dashboard plus one one-shot task every 100 ms (SPEC 7.3 B8). */
final case class Soak(d: Dash, done: Long = 0L)

object SoakApp extends App[Soak]:
    private val source = Source.For[Soak]

    def init: Soak = Soak(DashboardApp.init)

    def update(s: Soak, event: Event): Next[Soak] = s

    def view(s: Soak): View = DashboardApp.view(s.d)

    override def sources(s: Soak): Seq[Source[Soak]] = Seq(
      source
        .stream(DashboardApp.requests)((s, l) => s.copy(d = s.d.log(l)))
        .id("logs"),
      source
        .poll(250.millis)(Random.nextDouble)((s, v) =>
          s.copy(d = s.d.sample(v)),
        )
        .id("cpu"),
      source
        .every(100.millis)(s =>
          s.withTask(
            source.task(Sync.defer(1L))((s, n) => s.copy(done = s.done + n)),
          ),
        )
        .id("tasks"),
    )
end SoakApp

/** B8 (SPEC 7.3): soak. The Dashboard and one task every 100 ms for `seconds`
  * (default 600). Every 10 s: the heap after a full GC, the thread count, and
  * the sources and tasks of the run. Budget: the heap at the end within +-5% of
  * the heap at two thirds of the run; sources and tasks bounded; the thread
  * count constant (here: it varies by at most 4, as Kyo adds and removes
  * workers).
  *
  * `sbt "bench/runMain tui4s.bench.SoakProbe [seconds]"`
  */
object SoakProbe:
    def main(args: Array[String]): Unit =
        val seconds      = args.headOption.map(_.toInt).getOrElse(600)
        val samples      = Probes.block((seconds + 120).seconds)(
          Scope.run(
            Headless
              .start(SoakApp, TestBackend(120, 40), ScriptedInput.never)
              .map { h =>
                Loop(0, Vector.empty[(Int, Long, Int, Stats, Long)]) {
                  (t, acc) =>
                    if t > seconds then Loop.done(acc)
                    else
                        Async
                          .sleep(10.seconds)
                          .andThen(h.stats)
                          .map(st =>
                            h.states.current.map { s =>
                              val heap = Probes.heapAfterGc()
                              val row  =
                                (t + 10, heap, Probes.threadCount, st, s.done)
                              println(
                                f"[soak] t=${t + 10}%4d s heap=${heap / 1e6}%.1f MB " +
                                  f"threads=${Probes.threadCount} sources=${st.sources} " +
                                  f"tasks=${st.tasks} done=${s.done} dropped=${st.droppedFolds}",
                              )
                              Loop.continue(t + 10, acc :+ row)
                            },
                          )
                }.map(rows => h.stop.andThen(rows))
              },
          ),
        )
        val mid          = samples((samples.size * 2) / 3)
        val last         = samples.last
        val drift        = 100.0 * (last._2 - mid._2) / mid._2
        // Kyo's scheduler adds and removes workers; a leak grows without end.
        val threads      = samples.drop(1).map(_._3)
        val maxSrc       = samples.map(_._4.sources).max
        val maxTask      = samples.map(_._4.tasks).max
        Probes.record(
          "soak",
          "seconds"      -> seconds,
          "heapMidMB"    -> mid._2 / 1e6,
          "heapEndMB"    -> last._2 / 1e6,
          "heapDriftPct" -> drift,
          "threadsMin"   -> threads.min,
          "threadsMax"   -> threads.max,
          "maxSources"   -> maxSrc,
          "maxTasks"     -> maxTask,
          "tasksDone"    -> last._5,
          "withinBudget" -> (drift.abs <= 5.0 && maxSrc <= 3 && maxTask <= 5 &&
            threads.max - threads.min <= 4),
        )
    end main
end SoakProbe
