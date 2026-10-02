package examples.ziodashboard

import munit.FunSuite
import tui4s.interop.zio.*
import tui4s.interop.zio.testkit.*
import tui4s.testkit.*
import zio.Runtime
import zio.Unsafe
import zio.ZIO
import zio.durationInt
import zio.test.TestClock
import zio.test.testEnvironment

/** The ZIO Dashboard: the synchronous tests of the Kyo one (SPEC 6.3), and a
  * live test on zio-test's `TestClock`: the facade's sources sleep on the
  * caller's ZIO clock, so the test moves time itself.
  */
class DashboardSuite extends FunSuite:
    override def munitTimeout = scala.concurrent.duration.Duration(60, "s")

    private def run[A](z: ZIO[Any, Throwable, A]): A =
      Unsafe.unsafe(implicit u =>
        Runtime.default.unsafe.run(z).getOrThrowFiberFailure(),
      )

    /** Repeats `z` on the test's fiber (it reads the TestClock), with short
      * real-time pauses, until it gives true.
      */
    private def until(
        z: ZIO[Any, Nothing, Boolean],
    ): ZIO[Any, Throwable, Unit] =
      z.flatMap(ok =>
        if ok then ZIO.unit
        else ZIO.attemptBlocking(Thread.sleep(5)) *> until(z),
      )

    test("a sample moves the gauge; pause removes the poller") {
      val t = TuiTest(DashboardApp, 72, 16).emit("cpu", 0.42)
      assert(t.screen.contains("42%"), t.screen.text)
      assertEquals(t.sources, Seq("logs", "cpu"))
      t.press('p')
      assertEquals(t.sources, Seq("logs"))
      assert(t.screen.contains("CPU (paused)"))
    }

    test("the tabs switch with the arrow keys") {
      val t = TuiTest(DashboardApp, 72, 16).emit("logs", "GET /a 200")
      t.press(Key.Right)
      assertEquals(t.state.tab, 1)
      assert(t.screen.contains("GET /a 200"))
      t.press(Key.Right)
      assertEquals(t.state.tab, 0)
    }

    test("live on a TestClock: a sample per 250 ms, none while paused") {
      val (samples, logs, paused) = run(
        ZIO.scoped(
          testEnvironment.build *>
            LiveTuiTest(DashboardApp, 72, 16) { t =>
              val history = t.state.map(_.history.size)
              // The poller and the log stream, both asleep on the clock.
              val asleep  = TestClock.sleeps.map(_.size >= 2)
              for
                  _       <- until(history.map(_ == 1)) // poll runs at once
                  _       <- ZIO.foreachDiscard(2 to 5) { k =>
                               until(asleep) *> TestClock.adjust(250.millis) *>
                                 until(history.map(_ == k))
                             }
                  samples <- history
                  logs    <- t.state.map(_.logs.size)
                  _       <- t.press('p')
                  _       <- TestClock.adjust(1.second)
                  _       <- t.settle
                  paused  <- history
              yield (samples, logs, paused)
              end for
            },
        ),
      )
      assertEquals(samples, 5)
      assert(logs >= 1, logs.toString)
      assertEquals(paused, 5)
    }
end DashboardSuite
