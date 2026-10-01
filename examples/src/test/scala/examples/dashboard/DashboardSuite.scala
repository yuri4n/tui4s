package examples.dashboard

import kyo.*
import munit.FunSuite
import tui4s.*
import tui4s.testkit.*

class DashboardSuite extends FunSuite:
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

    test("live: five samples in one second, none while paused") {
      val (samples, paused) =
        TuiTest.liveBlocking(DashboardApp, 72, 16) { t =>
          t.advance(1.second)
            .andThen(t.state)
            .map { first =>
              t.press('p')
                .andThen(t.advance(1.second))
                .andThen(t.state)
                .map(after => (first.history.size, after.history.size))
            }
        }
      assertEquals(samples, 5)
      assertEquals(paused, 5)
    }
end DashboardSuite
