package tui4s

import kyo.*
import kyo.test.*
import tui4s.fixtures.*
import tui4s.testkit.*

/** SPEC 6.3 DashboardTest, the synchronous part. */
class DashboardTest extends Test[Any]:
    "a sample moves the gauge; pause stops the poller" in {
      val t       = TuiTest(DashboardApp, 72, 16).emit("cpu", 0.42)
      assert(t.screen.contains("42%"))
      assert(t.sources == Seq("logs", "cpu"))
      t.press('p')
      assert(t.sources == Seq("logs") && t.screen.contains("CPU (paused)"))
      t.emit("logs", "GET /")
      assert(t.state.logs == Vector("GET /"))
      val stopped = intercept[AssertionError](t.emit("cpu", 0.5))
      assert(stopped.getMessage.contains("known: logs"))
    }

    "the view tree shows the structure for agents" in {
      val t    = TuiTest(DashboardApp, 72, 16)
      val tree = t.viewTree
      assert(tree.startsWith("column\n"))
      assert(tree.contains("[length 3] bordered \" Dashboard \""))
      assert(tree.contains("tabs selected=0 [\"Metrics\",\"Requests\"]"))
      assert(tree.contains("gauge 0.0 \"0%\""))
      assert(tree.contains("[length 1] help \"← prev tab"))
    }
end DashboardTest
