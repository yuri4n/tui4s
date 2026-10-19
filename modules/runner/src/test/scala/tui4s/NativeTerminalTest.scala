package tui4s

import kyo.*
import kyo.test.*
import tui4s.fixtures.*
import tui4s.internal.Tty

import java.io.RandomAccessFile
import java.lang.foreign.{FunctionDescriptor, Linker, ValueLayout}
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

/** The real crossterm library, where it is safe: `Tui.run` must fail with
  * `NotATerminal` and change nothing when stdin is not a terminal.
  *
  * crossterm opens `/dev/tty` when stdin is not a terminal, so only the stdin
  * check of the acquire keeps such a run out of raw mode. The test runs when
  * stdin is not a terminal (sbt does not connect the stdin of a forked test
  * JVM) or when there is no terminal at all (CI, an agent). It never runs with
  * a terminal on stdin: it must never put that terminal into raw mode. The
  * real-TTY checklist in `examples/README.md` covers that case.
  */
// claude 2026-10-13: changed (T1): the kyo-ui Counter of the fixtures
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

    /** `name(int)` of the C library, for the test of [[Tty]]. */
    private def libc(name: String): Int => Int =
        val linker = Linker.nativeLinker().nn
        val int    = ValueLayout.JAVA_INT
        val handle = linker.defaultLookup().nn.find(name).nn.toScala.map { at =>
          linker.downcallHandle(at, FunctionDescriptor.of(int, int)).nn
        }
        arg =>
          handle.fold(-1) { h =>
            h.invokeWithArguments(Integer.valueOf(arg)) match
                case n: Integer => n.intValue
                case _          => -1
          }
    end libc

    "without a terminal on stdin, Tui.run fails with NotATerminal".timeout(
      30.seconds,
    ) in {
      val check: Unit < Async =
        if !unix || (canOpenTty && !Tty.isatty(0).contains(false)) then
            assert(true, "skipped: a terminal is on stdin")
        else
            // claude 2026-10-13: changed (T1): the public Tui.run
            Abort
              .run(Tui.run(_ => Counter.app))
              .map { r =>
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

    "isatty: true for a pseudo-terminal, false for a closed descriptor" in {
      if !unix then assert(true, "skipped: not a Unix")
      else
          // posix_openpt(O_RDWR): the master side of a new pseudo-terminal.
          val master = libc("posix_openpt")(2)
          val onPty  =
            try if master >= 0 then Tty.isatty(master) else Some(true)
            finally if master >= 0 then discard(libc("close")(master))
          assert(onPty.contains(true), onPty.toString)
          assert(Tty.isatty(-1).contains(false))
      end if
    }

    private def discard(a: Any): Unit =
        val _ = a
end NativeTerminalTest
