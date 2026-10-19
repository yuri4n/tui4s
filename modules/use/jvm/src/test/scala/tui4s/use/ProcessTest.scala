package tui4s.use

import kyo.*
import kyo.test.*

/** `useProcess` on the JVM: the output lines in order with the standard error,
  * the exit code, a program that does not exist, and the kill at the end of the
  * `Scope`.
  */
// claude 2026-10-19: created (L2a)
class ProcessTest extends Test[Any]:
    import Controlled.eventually

    "the lines (with the standard error) and the exit code" in {
      Scope.run {
        for
            run   <-
              useProcess(
                Chunk("sh", "-c", "printf 'a\\nb\\n'; printf 'err' >&2; exit 3"),
              )
            out   <- useStreamFold(run.lines, Chunk.empty[String])(_ :+ _)
            lines <- eventually(out)(_ == Chunk("a", "b", "err"))
            exit  <-
              eventually(run.exit)(_ == Present(Process.ExitCode.Failure(3)))
        yield assert(lines && exit)
      }
    }

    "a program that does not exist does not start: a typed error" in {
      Scope.run {
        Abort
          .run[ProcessError](useProcess(Chunk("tui4s-no-such-program")))
          .map(r =>
            assert(r match
                case Result.Failure(ProcessError.NotStarted(_)) => true
                case _                                          => false),
          )
      }
    }

    "the end of the Scope kills the process" in {
      for
          run  <- Scope.run(useProcess(Chunk("sleep", "30")))
          code <- run.process.waitFor(5.seconds)
      yield assert(code.exists(_ != Process.ExitCode.Success))
    }
end ProcessTest
