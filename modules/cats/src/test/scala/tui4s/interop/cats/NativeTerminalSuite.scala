package tui4s.interop.cats

import _root_.cats.effect.IO
import java.io.RandomAccessFile
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import tui4s.interop.cats.fixtures.CounterApp

/** The real runners on the real crossterm library, where it is safe: with no
  * terminal on stdin, `Tui.run` and `Tui.resource` fail with `NotATerminal`,
  * and `System.out` and `System.err` are the streams they were before.
  *
  * As core's NativeTerminalTest, it runs only when stdin is not a terminal (sbt
  * does not connect the stdin of a forked test JVM) or when there is no
  * terminal at all. It never puts a real terminal into raw mode.
  */
class NativeTerminalSuite extends CatsEffectSuite:
    override def munitIOTimeout: Duration = 60.seconds

    private def canOpenTty: Boolean =
      try
          RandomAccessFile("/dev/tty", "rw").close()
          true
      catch case NonFatal(_) => false

    private def noTerminalOnStdin: Boolean =
      !java.lang.System
        .getProperty("os.name", "")
        .nn
        .toLowerCase
        .nn
        .contains("win") &&
        (!canOpenTty || tui4s.internal.Tty.isatty(0).contains(false))

    private def notATerminal(r: Either[Throwable, Any]): Unit = r match
        case Left(_: TuiError.NotATerminal) => ()
        case other                          => fail(s"not NotATerminal: $other")

    /** Runs `io` and checks that the standard streams did not change. */
    private def sameStreams[A](io: IO[A]): IO[A] =
      IO((CounterApp.init, java.lang.System.out, java.lang.System.err))
        .flatMap { (_, out, err) =>
          io.flatTap(_ =>
            IO {
              assert(java.lang.System.out eq out, "System.out changed")
              assert(java.lang.System.err eq err, "System.err changed")
            },
          )
        }

    test("without a terminal on stdin, Tui.run[IO] fails with NotATerminal") {
      assume(noTerminalOnStdin, "a terminal is on stdin")
      sameStreams(Tui.run[IO, Int](CounterApp).attempt).map(notATerminal)
    }

    test("without a terminal on stdin, Tui.resource fails its acquire") {
      assume(noTerminalOnStdin, "a terminal is on stdin")
      sameStreams(Tui.resource[IO, Int](CounterApp).use(_ => IO.unit).attempt)
        .map(notATerminal)
    }
end NativeTerminalSuite
