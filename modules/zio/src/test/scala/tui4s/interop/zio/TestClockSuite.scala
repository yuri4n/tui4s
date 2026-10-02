package tui4s.interop.zio

import _root_.zio.ZIO
import _root_.zio.durationInt
import _root_.zio.test.TestClock
import _root_.zio.test.testEnvironment
import tui4s.testkit.*

/** SPEC 4.2 (UNVERIFIED there): facade sources sleep on the caller's ZIO clock,
  * so zio-test's `TestClock` drives `poll` and `every`.
  *
  * Answer: yes. Real time does not move a poller under a `TestClock`, and each
  * `TestClock.adjust(period)` gives one more sample. One `adjust` of several
  * periods may give fewer samples than periods: the source must be asleep again
  * before the clock passes its next deadline (the same as Kyo's time control,
  * SPEC 6.2). So adjust one period at a time, after `TestClock.sleeps` shows
  * the source asleep.
  *
  * All waits here are real-time blocking waits: under a `TestClock`, a
  * `ZIO.sleep` or `timeout` of the test itself would never end.
  */
class TestClockSuite extends ZioSuite:
    /** Counts the results of a poller (or the ticks of `every`). */
    private def counter(poll: Boolean): App[Int] =
      App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (n, _) => n,
        sources = _ =>
          Seq(
            if poll then Source.For[Int].poll(250.millis)(ZIO.succeed(1))(_ + _)
            else Source.For[Int].every(250.millis)(_ + 1),
          ),
      )

    /** The current state of `h`. */
    private def state(h: TuiHandle[Int]): ZIO[Any, Nothing, Int] =
      h.states.take(1).runHead.map(_.getOrElse(-1))

    /** Repeats `z` on this fiber (it reads the TestClock of this fiber), with
      * real-time pauses, until it gives true; at most 10 s.
      */
    private def until(what: String)(
        z: ZIO[Any, Nothing, Boolean],
    ): ZIO[Any, Throwable, Unit] =
        def loop(left: Int): ZIO[Any, Throwable, Unit] =
          z.flatMap { ok =>
            if ok then ZIO.unit
            else if left <= 0 then ZIO.fail(AssertionError(s"timed out: $what"))
            else ZIO.attemptBlocking(Thread.sleep(5)) *> loop(left - 1)
          }
        loop(2000)
    end until

    private def asleep = TestClock.sleeps.map(_.nonEmpty)

    test("TestClock drives poll: one sample per adjust of the period") {
      val (first, idle, stepped) = run(
        ZIO.scoped(
          testEnvironment.build *>
            Tui
              .startWith(
                counter(poll = true),
                RecordingControl(),
                ScriptedInput.empty,
              )
              .flatMap { h =>
                for
                    _       <- until("the first sample")(state(h).map(_ == 1))
                    _       <- until("the poller asleep")(asleep)
                    first   <- state(h)
                    _       <- ZIO.attemptBlocking(Thread.sleep(600))
                    idle    <- state(h)
                    stepped <- ZIO.foreach(2 to 5) { k =>
                                 until("the poller asleep")(asleep) *>
                                   TestClock.adjust(250.millis) *>
                                   until(s"sample $k")(state(h).map(_ == k)) *>
                                   state(h)
                               }
                yield (first, idle, stepped)
              },
        ),
      )
      assertEquals(first, 1)
      assertEquals(idle, 1, "real time moved a poller under a TestClock")
      assertEquals(stepped.toList, List(2, 3, 4, 5))
    }

    test("TestClock drives every") {
      val (idle, after) = run(
        ZIO.scoped(
          testEnvironment.build *>
            Tui
              .startWith(
                counter(poll = false),
                RecordingControl(),
                ScriptedInput.empty,
              )
              .flatMap { h =>
                for
                    _     <- until("every asleep")(asleep)
                    _     <- ZIO.attemptBlocking(Thread.sleep(600))
                    idle  <- state(h)
                    _     <- TestClock.adjust(250.millis)
                    _     <- until("one tick")(state(h).map(_ == 1))
                    _     <- until("every asleep")(asleep)
                    _     <- TestClock.adjust(250.millis)
                    _     <- until("two ticks")(state(h).map(_ == 2))
                    after <- state(h)
                yield (idle, after)
              },
        ),
      )
      assertEquals(idle, 0)
      assertEquals(after, 2)
    }

    test("one adjust of four periods: between one and four samples") {
      // Recorded, not a contract: how many deadlines one adjust passes
      // depends on how fast the source sleeps again.
      val gained = run(
        ZIO.scoped(
          testEnvironment.build *>
            Tui
              .startWith(
                counter(poll = true),
                RecordingControl(),
                ScriptedInput.empty,
              )
              .flatMap { h =>
                for
                    _      <- until("the first sample")(state(h).map(_ == 1))
                    _      <- until("the poller asleep")(asleep)
                    _      <- TestClock.adjust(1.second)
                    _      <- ZIO.attemptBlocking(Thread.sleep(300))
                    gained <- state(h).map(_ - 1)
                yield gained
              },
        ),
      )
      println(
        s"[TestClockSuite] one adjust(1 s) of a 250 ms poller: $gained samples",
      )
      assert(gained >= 1 && gained <= 4, gained.toString)
    }
end TestClockSuite
