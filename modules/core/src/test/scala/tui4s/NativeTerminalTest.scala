package tui4s

import java.io.RandomAccessFile
import kyo.*
import kyo.test.*
import scala.util.control.NonFatal
import tui4s.fixtures.*

/** The real crossterm library, where it is safe: a JVM with no controlling
  * terminal (CI, an agent) cannot enter raw mode, so `Tui.run` must fail with
  * `NotATerminal` and change nothing.
  *
  * crossterm opens `/dev/tty` when stdin is not a terminal. When this JVM can
  * open it (a developer runs the tests in a terminal), the test does nothing:
  * it must never put that terminal into raw mode. The real-TTY checklist in
  * `examples/README.md` covers that case.
  */
class NativeTerminalTest extends Test[Any]:
    private def canOpenTty: Boolean =
      try
          RandomAccessFile("/dev/tty", "rw").close()
          true
      catch case NonFatal(_) => false

    private def unix: Boolean =
      !java.lang.System
        .getProperty("os.name", "")
        .nn
        .toLowerCase
        .nn
        .contains("win")

    "without a terminal, Tui.run fails with NotATerminal".timeout(
      30.seconds,
    ) in {
      val check: Unit < Async =
        if !unix || canOpenTty then assert(true, "skipped: a terminal is near")
        else
            Abort.run(Tui.run(CounterApp)).map { r =>
              assert(
                r match
                    case Result.Failure(TuiError.NotATerminal(_)) => true
                    case _                                        => false
                ,
                r.toString,
              )
            }
      check
    }
end NativeTerminalTest
