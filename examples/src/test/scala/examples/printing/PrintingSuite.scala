package examples.printing

import kyo.*
import munit.FunSuite
import tui4s.TuiOptions
import tui4s.testkit.Headless

import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The printing app on the headless test kit, with a print that records the
  * lines (the kit has no terminal to protect; the capture of the real terminal
  * is the job of the core tests and of the PTY kit).
  */
// claude 2026-10-13: created (T2)
class PrintingSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    test(
      "the UI prints when it is built and at each +; the screen shows the count",
    ) {
      val lines = ConcurrentLinkedQueue[String]()
      val s     = run {
        for
            h <- Headless.start(50, 6)(quit =>
                   Printing.ui(quit, l => Sync.defer(discard(lines.add(l)))),
                 )
            _ <- h.char('+')
            _ <- h.char('+')
            s <- h.screen
        yield s
      }
      assert(s.contains("Count: 2"), s.text)
      assertEquals(
        lines.asScala.toSeq,
        Seq(
          "printing: the UI is built",
          "printing: + gives 1",
          "printing: + gives 2",
        ),
      )
    }

    test("--log <file> sends the captured lines to the file") {
      assertEquals(
        Printing.options(Seq("--log", "out.log")),
        TuiOptions(logFile = Some(Paths.get("out.log"))),
      )
      assertEquals(Printing.options(Nil), TuiOptions())
    }

    private def discard(b: Boolean): Unit =
        val _ = b
end PrintingSuite
