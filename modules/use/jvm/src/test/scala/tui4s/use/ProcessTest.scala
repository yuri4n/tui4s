package tui4s.use

import kyo.*
import kyo.test.*

import scala.jdk.OptionConverters.*

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
            lines <- eventually(out.value)(_ == Chunk("a", "b", "err"))
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

    /** Waits until the process `pid` does not run, at most 5 s. */
    private def dead(pid: Long)(using Frame): Boolean < Async =
      Loop(0) { i =>
        Sync
          .defer(ProcessHandle.of(pid).nn.toScala.forall(h => !h.isAlive))
          .map(d =>
            if d || i >= 500 then Loop.done(d)
            else Async.sleep(10.millis).andThen(Loop.continue(i + 1)),
          )
      }

    /** The first line of `cmd`, in a `Scope` that closes after it. */
    private def firstLine(cmd: String)(using Frame): Maybe[String] < Async =
      Scope.run {
        for
            run  <- Abort.run[ProcessError](useProcess(Chunk("sh", "-c", cmd)))
            line <- (run match
                        case Result.Success(r) =>
                          useStreamFold(r.lines, Maybe.empty[String])(
                            (first, l) => first.orElse(Present(l)),
                          ).map(out =>
                            eventually(out.value)(_.nonEmpty)
                              .andThen(out.value.current),
                          )
                        case _                 => Maybe.empty[String]
                    ): Maybe[String] < (Async & Scope)
        yield line
      }

    // claude 2026-10-19: created (L2a review)
    "the end of the Scope kills the descendants, and the close does not wait for the output" in {
      Abort
        .run[Timeout](Async.timeout(10.seconds) {
          for
              // `sleep` is a descendant of the shell: the kill takes it.
              pid   <- firstLine("sleep 30 & echo $!; wait")
              gone  <- (pid.toOption.flatMap(_.toLongOption) match
                           case Some(p) => dead(p)
                           case None    => false
                       ): Boolean < Async
              // `sleep` leaves the shell (a subshell starts it): it holds the
              // output open for 20 s, and the close does not wait for it.
              other <- firstLine("(sleep 20 &); echo y")
          yield (pid.nonEmpty, gone, other)
        })
        .map(r => assert(r == Result.succeed((true, true, Present("y")))))
    }
end ProcessTest
