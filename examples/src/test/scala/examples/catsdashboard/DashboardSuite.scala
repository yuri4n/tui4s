package examples.catsdashboard

import cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import tui4s.interop.cats.*
import tui4s.interop.cats.testkit.LiveTuiTest
import tui4s.testkit.*

/** The Cats Effect Dashboard: the synchronous tests of the Kyo Dashboard, and a
  * live test with the fs2 stream and the IO poller running.
  */
class DashboardSuite extends CatsEffectSuite:
    override def munitIOTimeout: Duration = 60.seconds

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

    test("live: the poller samples, the stream logs; pause stops the samples") {
      LiveTuiTest[IO](DashboardApp, 72, 16) { t =>
        for
            _      <- t.eventually()(d =>
                        Option.when(d.history.size >= 3 && d.logs.nonEmpty)(()),
                      )
            _      <- t.press('p')
            paused <- t.state
            _      <- IO.sleep(600.millis)
            after  <- t.state
            screen <- t.screen
            labels <- t.sources
        yield
            assert(paused.paused)
            assertEquals(after.history, paused.history)
            assertEquals(labels, Seq("logs"))
            assert(screen.contains("CPU (paused)"), screen.text)
      }
    }
end DashboardSuite
