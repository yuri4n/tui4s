package tui4s

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import tui4s.internal.*
import tui4s.spi.Settle
import tui4s.testkit.Headless

import java.time.Duration as JDuration

/** The region watchers: R1 (no fiber outlives the run) and, later, the own
  * watcher loop and the repair tick.
  */
// claude 2026-10-12: created (RC-8; the S2 r1-live test is the model)
class WatcherTest extends RunnerSuite:

    /** The refs of the test app: all region kinds, and a region whose content
      * holds a nested region.
      */
    final case class Refs(
        a: SignalRef[Int],
        t: SignalRef[String],
        l: SignalRef[Chunk[Int]],
    )

    def refs(using Frame): Refs < Sync =
      for
          a <- Signal.initRef(0)
          t <- Signal.initRef("t0;")
          l <- Signal.initRef(Chunk(0))
      yield Refs(a, t, l)

    /** A Reactive span, a bound input, a Foreach, and a nested region. */
    def app(r: Refs)(using Frame): UI =
      div(
        span(r.a.map(n => s"[a=$n]")),
        input.value(r.t).style(_.width(20.px)),
        div(r.l.foreach(n => span(s"[f=$n]"))),
        div(r.a.render(n => span(r.t.map(s => s"[n=$n:$s]")))),
      )

    /** The value `i` into every ref. */
    def write(r: Refs, i: Int)(using Frame): Unit < Sync =
      r.a.set(i).andThen(r.t.set(s"t$i;")).andThen(r.l.set(Chunk(i))).unit

    /** Polls `cond` every 2 ms: the ms until it was true, or -1 after
      * `timeout`.
      */
    def waitFor(timeout: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Long < Async =
      now.map { t0 =>
        Loop.foreach {
          cond.map { ok =>
            now.map { t =>
              if ok then Loop.done((t - t0) / 1_000_000)
              else if t - t0 > timeout.toNanos then Loop.done(-1L)
              else Async.sleep(2.millis).andThen(Loop.continue)
            }
          }
        }
      }

    "R1: 0 live fibers after an INTERRUPTED run" in {
      for
          r      <- refs
          inbox  <- Channel.initUnscoped[Msg](1024)
          canvas <- Sync.defer(Canvas(TestBackend(60, 12)))
          // claude 2026-10-19: changed: Runner.init with RunOptions (A10, P2)
          runner <-
            Runner.init(inbox, canvas, RunOptions.of(TuiOptions()), Absent)
          fiber  <- Fiber.initUnscoped(runner.run(app(r)))
          // the mount is done when its 5 watchers (and the tick) are live
          _      <- waitFor(5.seconds)(runner.liveFibers.map(_ >= 6))
          _      <- Kyo.foreachDiscard(1 to 5)(i =>
                      write(r, i).andThen(Async.sleep(20.millis)),
                    )
          live0  <- runner.liveFibers
          _      <- fiber.interrupt
          ms     <- waitFor(2.seconds)(runner.liveFibers.map(_ == 0))
      yield
          assert(live0 >= 4, s"watchers before the interrupt: $live0")
          assert(ms >= 0L, "live fibers stay after the interrupt")
    }

    // claude 2026-10-12: created (review R2-1): the interrupt lands inside a
    // settle, after a write absorbed there remounted a region
    "R1: 0 live fibers after an interrupt INSIDE A SETTLE (a remount in flight)" in {
      // A CPU-bound fiber keeps the probe busy, so the settle of the first
      // write runs to its `max` (2 s). The second write is absorbed inside
      // that settle: it remounts the nested region and starts a new watcher,
      // which is in the step's state only (not in `last`) when the interrupt
      // lands.
      val options =
        TuiOptions(settle = Settle.Quiescent(JDuration.ofSeconds(2)))
      for
          r      <- refs
          inbox  <- Channel.initUnscoped[Msg](1024)
          canvas <- Sync.defer(Canvas(TestBackend(60, 12)))
          // claude 2026-10-19: changed: Runner.init with RunOptions (A10, P2)
          runner <- Runner.init(inbox, canvas, RunOptions.of(options), Absent)
          fiber  <- Fiber.initUnscoped(runner.run(app(r)))
          _      <- waitFor(5.seconds)(runner.liveFibers.map(_ >= 6))
          _      <- Async.sleep(100.millis) // the arm steps of the start pass
          busy   <- Fiber.initUnscoped(Sync.defer(spin(1000)))
          _      <- Async.sleep(20.millis)
          _      <- r.a.set(1)              // a step; its settle runs while `busy` spins
          _      <- Async.sleep(100.millis)
          _      <- r.a.set(2)              // absorbed inside that settle: a remount
          _      <- Async.sleep(100.millis)
          live0  <- runner.liveFibers
          _      <- fiber.interrupt
          ms     <- waitFor(2.seconds)(runner.liveFibers.map(_ == 0))
          _      <- busy.get
      yield
          assert(live0 >= 6, s"fibers before the interrupt: $live0")
          assert(
            ms >= 0L,
            "live fibers stay after an interrupt inside a settle (the watcher of the remount)",
          )
      end for
    }

    "R1: 0 live fibers after a quit, and after the scope of the kit ends" in {
      for
          r  <- refs
          h  <- Scope.run(
                  Headless.start(60, 12)(_ => app(r)).map { h =>
                    Kyo
                      .foreachDiscard(1 to 5)(i => write(r, i))
                      .andThen(awaitAll(h, 2.seconds)("[a=5]", "t5;", "[f=5]"))
                      .andThen(h.quit)
                      .andThen(h.awaitEnd(10.seconds))
                      .map(ended => assert(ended))
                      .andThen(h)
                  },
                )
          ms <- waitFor(2.seconds)(h.liveFibers.map(_ == 0))
      yield assert(ms >= 0L, "live fibers stay after the quit")
    }

    // ---- K29: the own watcher loop and the repair tick ----

    "K29: 200 writes to every region kind all reach the screen" in {
      for
          r  <- refs
          h  <- Headless.start(60, 12)(_ => app(r))
          ok <- Kyo.foreach(1 to 200)(i =>
                  write(r, i).andThen(
                    awaitAll(h, 2.seconds)(
                      s"[a=$i]",
                      s"t$i;",
                      s"[f=$i]",
                      s"[n=$i:t$i;]",
                    ),
                  ),
                )
      yield assert(ok.forall(identity), s"writes not seen: ${ok.count(!_)}")
    }

    "K29: a write in the window is lost by the loop and repaired by the tick within 2 ticks" in {
      // the seam: a watcher sleeps 60 ms after each post (n > 0), so a write
      // in that time lands between `current` and `nextWith`
      val seam                                            = (n: Int) => if n > 0 then 60.millis else Duration.Zero
      def lostThenRepaired(repair: Duration)(using Frame) =
        Knobs.let(Knobs(seam = seam, repairInterval = repair)) {
          for
              a   <- Signal.initRef(0)
              h   <- Headless.start(30, 2)(_ => div(span(a.map(n => s"[a=$n]"))))
              _   <- a.set(1)
              ok1 <- h.awaitScreen(showing("[a=1]"), 2.seconds)
              _   <- h.drainFrames
              _   <- a.set(2)                // in the window: the watcher sleeps
              ok2 <- h.awaitScreen(showing("[a=2]"), 300.millis)
              // the stats of the repair frame can be in flight (review R2-3)
              fs  <-
                if ok2 then framesUntil(h, 500.millis)(_.repaired > 0)
                else h.drainFrames
              _   <- Async.sleep(100.millis) // the window closes
              _   <- a.set(3)                // after the window: the loop wakes up
              ok3 <- h.awaitScreen(showing("[a=3]"), 2.seconds)
          yield (ok1, ok2, fs.count(_.repaired > 0), ok3)
        }
      for
          (ok1, lost, repairedFrames, ok3) <- lostThenRepaired(1.hour)
          _                                 = assert(ok1 && ok3)
          _                                 = assert(!lost, "the loop saw the write in the window")
          _                                 = assert(repairedFrames == 0)
          (ok1b, fixed, repaired, ok3b)    <- lostThenRepaired(100.millis)
      yield
          assert(ok1b && ok3b)
          assert(fixed, "the tick did not repair the lost write within 2 ticks")
          assert(
            repaired >= 1,
            "the repair frame counts the value in `repaired`",
          )
      end for
    }
end WatcherTest
