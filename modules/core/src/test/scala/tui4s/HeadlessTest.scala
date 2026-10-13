package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.Knobs
import tui4s.testkit.Headless

/** The headless kit: the quit value, resize, idle, awaitScreen (RC-7, D-15). */
// claude 2026-10-12: created
class HeadlessTest extends RunnerSuite:

    "the quit value ends the run; awaitEnd and finished see it" in {
      for
          captured <- AtomicRef.init(Option.empty[Unit < Sync])
          h        <- Headless.start(20, 3)(quit =>
                        captured.set(Some(quit)).andThen(div("hi")),
                      )
          s0       <- h.screen
          _         = assert(s0.contains("hi"))
          fin0     <- h.finished
          _         = assert(!fin0)
          quit     <- captured.get
          _        <- quit.get // runs the captured quit value
          ended    <- h.awaitEnd(10.seconds)
          _         = assert(ended, "awaitEnd returns true after the quit")
          fin1     <- h.finished
      yield assert(fin1)
    }

    "resize: the frame of the resize lays the content out again" in {
      for
          h  <- Headless.start(6, 3)(_ => div(p("hello world")))
          s0 <- h.screen
          _   = assert(s0.rows == Vector("hello", "world", ""), s0.text)
          f  <- h.resize(20, 2)
          s1 <- h.screen
      yield
          assert(f.cause == FrameCause.Input && f.repainted > 0)
          assert(s1.rows == Vector("hello world", ""), s1.text)
    }

    // claude 2026-10-12: created (review R2-4): in the old kit the TEST fiber
    // resized the backend while the runner fiber could be flushing a frame
    // into it (an IndexOutOfBoundsException ended the run, and `resize` hung)
    "resize while a signal writes fast: the runner resizes the backend, so no frame hits a smaller buffer" in {
      for
          n      <- Signal.initRef(0)
          h      <- Headless.start(60, 8)(_ =>
                      div(span(n.map(v => s"n=$v " + "x" * 50))),
                    )
          writer <- Fiber.initUnscoped(
                      Loop.forever(
                        n.updateAndGet(_ + 1).andThen(Async.sleep(1.millis)),
                      ),
                    )
          rs     <- Kyo.foreach(1 to 40)(i =>
                      Abort.run[Timeout](
                        Async.timeout(3.seconds)(
                          if i % 2 == 0 then h.resize(60, 8) else h.resize(12, 2),
                        ),
                      ),
                    )
          _      <- writer.interrupt
          fin    <- h.finished
          s      <- h.screen
      yield
          assert(
            rs.forall(_.isSuccess),
            s"resizes without their frame: ${rs.count(!_.isSuccess)} of 40 (run ended: $fin)",
          )
          assert(!fin, "the run ended during the resizes")
          assert(s.rows.size == 8 && s.rows.head.startsWith("n="), s.text)
    }

    // claude 2026-10-12: created (review R2-8 a): the old `start` waited for
    // the first frame with no look at the run fiber, so a failed UI hung
    // until the leaf timeout and its error was never shown
    /* claude 2026-10-12: created (PR #11 review: Codex, Headless.scala:88): the
     * runner */
    // flushes a frame cell by cell on its fiber while a test fiber can read the
    // screen. ONE region paints all the rows from one value, so a frame is
    // never torn by itself; the seam widens the flush (a spin between the
    // rows); without the backend's monitor the reader then sees torn frames
    // (rows with two values); with it, never. The natural window is
    // microseconds wide.
    // claude 2026-10-13: changed (C3): 1000 writes, not 300. The reader keeps
    // the scheduler busy, so each settle runs to its max (100 ms): 300 writes
    // (about 350 ms) gave 3 or 4 frames, and the check "more than 3 distinct
    // values" failed in 3 of 5 runs on the C2 code (.work/impl/
    // c3-torn-compare.log). 1000 writes give about 10 frames.
    "screen is never a torn frame: a read during a flush sees one value on every row" in {
      Knobs.let(Knobs(flushSeam = 100.micros)) {
        for
            n      <- Signal.initRef(0)
            h      <-
              Headless.start(24, 12)(_ =>
                div(
                  n.map(v =>
                    div(Vector.fill(12)(span((v % 10).toString * 22))*),
                  ),
                ),
              )
            writer <- Fiber.initUnscoped(
                        Kyo.foreachDiscard(1 to 1000)(i =>
                          n.set(i)
                            .andThen(
                              Clock.let(Clock.live)(Async.sleep(1.millis)),
                            ),
                        ),
                      )
            read   <- Loop(0, 0, Set.empty[String]) { (reads, torn, seen) =>
                        writer.done.map { done =>
                          if done then Loop.done((reads, torn, seen.size))
                          else
                              h.screen.map { s =>
                                val values =
                                  s.rows.map(_.trim).filter(_.nonEmpty).distinct
                                // one scheduler round trip: the writer and the
                                // runner get their workers between two reads
                                Fiber
                                  .initUnscoped(Kyo.unit)
                                  .map(_.get)
                                  .andThen(
                                    Loop.continue(
                                      reads + 1,
                                      if values.size > 1 then torn + 1 else torn,
                                      seen ++ values,
                                    ),
                                  )
                              }
                        }
                      }
            fin    <- h.finished
        yield
            assert(
              read._2 == 0,
              s"${read._2} torn reads of ${read._1} (${read._3} distinct values seen)",
            )
            assert(
              read._1 > 100,
              s"only ${read._1} reads: the reader did not run",
            )
            assert(
              read._3 > 3,
              s"only ${read._3} distinct values: the screen did not change",
            )
            assert(!fin, "the run ended during the writes")
      }
    }

    "start fails fast when the UI fails, with the error" in {
      for
          t0 <- now
          f  <- Fiber.initUnscoped(
                  Scope.run(
                    Headless.start(10, 2)(_ =>
                      Sync.defer(
                        throw new IllegalStateException("the UI failed"),
                      ),
                    ),
                  ),
                )
          r  <- f.getResult
          t1 <- now
      yield
          assert(!r.isSuccess, s"start returned: $r")
          assert(
            r.toString.contains("the UI failed"),
            s"the error of the UI is not in the result: $r",
          )
          assert((t1 - t0) / 1_000_000 < 5000, "start hung")
    }

    "idle: no frame without a change" in {
      for
          n    <- Signal.initRef(0)
          h    <- Headless.start(20, 2)(_ => div(span(n.map(v => s"n=$v"))))
          _    <- n.set(1)
          ok   <- h.awaitScreen(showing("n=1"), 2.seconds)
          _     = assert(ok)
          _    <- Async.sleep(300.millis)
          _    <- h.drainFrames
          _    <- Async.sleep(1500.millis)
          late <- h.drainFrames
      yield assert(late.isEmpty, s"frames while idle: $late")
    }

    "awaitScreen: true before the timeout; false after it when nothing changes" in {
      for
          n  <- Signal.initRef("a")
          h  <- Headless.start(20, 2)(_ => div(span(n)))
          _  <- Fiber.initUnscoped(Async.sleep(100.millis).andThen(n.set("b")))
          t0 <- now
          ok <- h.awaitScreen(showing("b"), 3.seconds)
          t1 <- now
          _   = assert(ok && (t1 - t0) / 1_000_000 < 2500)
          no <- h.awaitScreen(showing("zzz"), 200.millis)
          t2 <- now
      yield assert(!no && (t2 - t1) / 1_000_000 >= 200)
    }
end HeadlessTest
