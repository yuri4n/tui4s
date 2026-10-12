package tui4s

import java.time.Duration as JDuration
import kyo.*
import kyo.UI.*
import tui4s.internal.Knobs
import tui4s.spi.Settle
import tui4s.testkit.Headless

/** The settle canary (D-17 c, RC-11): the public `Scheduler.status()` probe
  * sees a CPU-bound fiber and the frame counts the timeout; a quiet run has no
  * timeout. The pull check: a bound or `Foreach` write that the watcher loop
  * lost reaches the frame of the next event.
  */
// claude 2026-10-12: created; changed (review R1-8): the pull-check leaf
class SettleTest extends RunnerSuite:

    // claude 2026-10-12: created (review R1-8): the pull check and the re-check
    // of D-17 c had no test that fails without them
    "the pull check: a bound and a Foreach write lost by the loop reach the frame of the NEXT event (the tick is off)" in {
      // The seam: a watcher sleeps 300 ms after each post (n > 0), so a write
      // in that time lands between its `current` and its `nextWith`, and the
      // loop misses it. The repair tick is off, so only the pull check of the
      // verify pass (every bound and Foreach region, in every step) can carry
      // the lost writes: the frame of the next event (a write to `a`) shows
      // them and counts them in `repaired`; the re-check settles their
      // consequences before that frame, so the event has one frame.
      val knobs = Knobs(
        seam = n => if n > 0 then 300.millis else Duration.Zero,
        repairInterval = 1.hour,
      )
      Knobs.let(knobs) {
        for
            a   <- Signal.initRef(0)
            t   <- Signal.initRef("t0;")
            l   <- Signal.initRef(Chunk(0))
            h   <- Headless.start(60, 6)(_ =>
                     div(
                       span(a.map(n => s"[a=$n]")),
                       input.value(t).style(_.width(20.px)),
                       div(l.foreach(n => span(s"[f=$n]"))),
                     ),
                   )
            // the arm steps of the start pass: the regions are not young any
            // more, so the young-region verify cannot carry a write
            _   <- Async.sleep(100.millis)
            _   <- t.set("t1;").andThen(l.set(Chunk(1)))
            ok1 <- awaitAll(h, 2.seconds)("t1;", "[f=1]")
            _   <- framesUntil(h, 500.millis)(_.updates > 0)
            _   <- Async.sleep(50.millis)                  // a second step of these writes ends
            _   <- t.set("t2;").andThen(l.set(Chunk(2)))   // in the windows
            no  <- awaitAll(h, 400.millis)("t2;", "[f=2]") // lost: not shown
            _   <- h.drainFrames
            _   <- a.set(1)                                // the next event
            ok2 <- awaitAll(h, 2.seconds)("[a=1]", "t2;", "[f=2]")
            fs  <- framesUntil(h, 500.millis)(_.updates > 0)
        yield
            assert(ok1)
            assert(
              !no,
              "the loop saw the writes in the window (the seam did not hold)",
            )
            assert(
              ok2,
              "the lost writes did not reach the screen with the next event",
            )
            val frames = fs.filter(_.updates > 0)
            assert(frames.size == 1, s"frames of the event: $frames")
            assert(
              frames.head.repaired == 2 && frames.head.updates == 3,
              s"the frame of the event: ${frames.head}",
            )
        end for
      }
    }

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
