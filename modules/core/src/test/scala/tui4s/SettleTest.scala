package tui4s

import java.time.Duration as JDuration
import kyo.*
import kyo.UI.*
import tui4s.spi.Settle
import tui4s.testkit.Headless

/** The settle canary (D-17 c, RC-11): the public `Scheduler.status()` probe
  * sees a CPU-bound fiber and the frame counts the timeout; a quiet run has no
  * timeout.
  */
// claude 2026-10-12: created
class SettleTest extends RunnerSuite:

    "a quiet run: 50 frames, no settle timeout, no repair" in {
      for
          n   <- Signal.initRef(0)
          h   <- Headless.start(30, 2)(_ => div(span(n.map(v => s"n=$v"))))
          _   <- h.drainFrames
          // the frames of a write are read after the stats of its frame
          // arrived (review R2-3)
          all <- Kyo.foreach(1 to 50)(i =>
                   n.set(i)
                     .andThen(h.awaitScreen(showing(s"n=$i"), 2.seconds))
                     .map(ok =>
                       framesUntil(h, 500.millis)(_.updates > 0).map((ok, _)),
                     ),
                 )
          ok   = all.map(_._1)
          fs   = all.flatMap(_._2)
      yield
          assert(ok.forall(identity) && fs.count(_.updates > 0) >= 50)
          assert(
            fs.forall(_.settleTimeouts == 0),
            s"timeouts: ${fs.filter(_.settleTimeouts > 0)}",
          )
          assert(
            fs.forall(_.repaired == 0),
            s"repairs: ${fs.filter(_.repaired > 0)}",
          )
    }

    "a CPU-bound fiber delays the settle: the probe sees it, the frame counts a timeout" in {
      val options =
        TuiOptions(settle = Settle.Quiescent(JDuration.ofMillis(100)))
      for
          n    <- Signal.initRef(0)
          h    <-
            Headless.start(30, 2, options)(_ => div(span(n.map(v => s"n=$v"))))
          _    <- h.drainFrames
          busy <- Fiber.initUnscoped(Sync.defer(spin(500)))
          _    <- Async.sleep(20.millis)
          _    <- n.set(1)
          f    <- h.nextFrame
          _    <- busy.get
      yield
          assert(f.settleTimeouts == 1, s"frame: $f")
          assert(
            f.settleNanos >= 100_000_000L,
            s"settle took ${f.settleNanos / 1_000_000} ms",
          )
          assert(f.updates == 1)
      end for
    }
end SettleTest
