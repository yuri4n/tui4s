package tui4s.stress

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import tui4s.TuiOptions
import tui4s.internal.*
import tui4s.testkit.Headless

/** The R1 leak probe (RC-30; the IR LeakProbe and its S2 copy are the models):
  * many short runs in one JVM, the used heap after a GC before and after. Two
  * ways to end a run: (a) the run fiber is INTERRUPTED (what a cancel does),
  * (b) a quit, then the scope of the kit ends. Before R1 option b the prototype
  * kept about 1750 KB per interrupted run.
  */
// claude 2026-10-12: created
class LeakProbe extends StressSuite:

    private def ui(n: SignalRef[Int]): UI =
      div(span(n.map(v => s"n=$v")), div(n.render(v => span(s"[$v]"))))

    private def phase(name: String, n: Int)(round: => Unit < Async)(using
        Frame,
    ): Long < Async =
      for
          before <- Sync.defer(usedKb())
          t0     <- now
          _      <- Kyo.foreachDiscard(1 to n)(_ => round)
          t1     <- now
          // two repair intervals: a leaked watcher wakes up in this time
          _      <- Async.sleep(2500.millis)
          after  <- Sync.defer(usedKb())
          delta   = after - before
          _      <-
            Sync.defer(
              report(
                f"leak $name%-28s rounds=$n heapKB before=$before%7d after=$after%7d delta=$delta%7d perRoundKB=${delta.toDouble / n}%8.1f wallMs=${(t1 - t0) / 1_000_000}%6d",
              ),
            )
      yield delta

    probe("R1 leak: interrupted runs, then quit runs; the heap per run") in {
      val n = rounds.getOrElse(150)
      for
          interrupted <- phase("interrupt (run fiber)", n) {
                           for
                               ref    <- Signal.initRef(0)
                               inbox  <- Channel.initUnscoped[Msg](1024)
                               canvas <-
                                 Sync.defer(Canvas(TestBackend(100, 30)))
                               runner  =
                                 Runner(inbox, canvas, TuiOptions(), Absent)
                               fiber  <- Fiber.initUnscoped(runner.run(ui(ref)))
                               _      <- ref.set(1).andThen(Async.sleep(10.millis))
                               _      <- fiber.interrupt
                           yield ()
                         }
          quit        <- phase("quit, then the scope ends", n) {
                           Scope.run(
                             for
                                 ref <- Signal.initRef(0)
                                 h   <- Headless.start(100, 30)(_ => ui(ref))
                                 _   <- ref.set(1)
                                 _   <- h.awaitScreen(_.contains("n=1"), 2.seconds)
                                 _   <- h.quit
                                 _   <- h.awaitEnd(10.seconds)
                             yield (),
                           )
                         }
      yield assert(
        interrupted < 5L * 1024 * n / 150 && quit < 5L * 1024 * n / 150,
        s"heap growth: interrupt $interrupted KB, quit $quit KB for $n rounds",
      )
      end for
    }
end LeakProbe
