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
