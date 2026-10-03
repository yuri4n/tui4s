package tui4s

import examples.dashboard.Dash
import examples.dashboard.DashboardApp
import jatatui.core.backend.TestBackend
import kyo.*
import munit.FunSuite
import tui4s.testkit.*

/** Probe for SPEC 6.2 / Appendix A 16: does Clock.withTimeControl drive the sources of a real loop, and how many
  * ticks does one `advance` give? Records numbers; asserts only that virtual time reaches the source fibers.
  */
class TimeControlProbe extends FunSuite:
  override def munitTimeout = scala.concurrent.duration.Duration(60, "s")

  test("virtual time drives poll + stream sources; one advance(1 s) vs 250 ms steps") {
    import AllowUnsafe.embrace.danger
    val app = App[Dash](
      DashboardApp.init,
      DashboardApp.view,
      DashboardApp.update,
      DashboardApp.sources,
      DashboardApp.options.copy(fps = 0)
    )
    val control = tui4s.internal.HeadlessControl(TestBackend(72, 16), ScriptedInput())
    def realWait(ms: Long)(using Frame): Unit < Sync = Sync.defer(Thread.sleep(ms))
    val out = KyoApp.Unsafe
      .runAndBlock(40.seconds)(
        Clock.withTimeControl { tc =>
          tui4s.internal.Runner.startUnscoped(app, control).map { h =>
            def hist(using Frame): (Int, Int) < Sync = h.states.current.map(d => (d.history.size, d.logs.size))
            realWait(300).andThen(hist).map { atStart =>
              tc.advance(1.second, Duration.Zero).andThen(realWait(300)).andThen(hist).map { afterOneJump =>
                Kyo
                  .foreach(1 to 4)(_ => tc.advance(250.millis, Duration.Zero).andThen(realWait(150)))
                  .andThen(hist)
                  .map(afterSteps => h.stop.andThen((atStart, afterOneJump, afterSteps)))
              }
            }
          }
        }
      )
      .getOrThrow
    println(s"[time] (history, logs) at start=${out._1} after advance(1 s)=${out._2} after 4 x 250 ms=${out._3}")
    assert(out._2._1 > out._1._1, out) // virtual time reached the poller fiber
  }
end TimeControlProbe
