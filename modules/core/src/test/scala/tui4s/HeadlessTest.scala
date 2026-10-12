package tui4s

import kyo.*
import kyo.UI.*
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
