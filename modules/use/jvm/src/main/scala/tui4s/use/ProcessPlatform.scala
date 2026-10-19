package tui4s.use

import kyo.*

/** The processes of the JVM: kyo-system's `Command`. */
// claude 2026-10-19: created (L2a)
private[use] object ProcessPlatform:

    def start(cmd: Chunk[String])(using
        Frame,
    ): RunningProcess < (Sync & Scope & Abort[ProcessError]) =
      for
          // spawn kills the process when the Scope closes.
          p     <- Abort
                     .run[CommandException](
                       Command(cmd*).stdin("").redirectErrorStream(true).spawn,
                     )
                     .map {
                       case Result.Success(p) => p
                       case Result.Failure(e) =>
                         Abort.fail(ProcessError.NotStarted(e))
                       case Result.Panic(t)   => Abort.panic(t)
                     }
          exit  <- Signal.initRef(Maybe.empty[Process.ExitCode])
          lines <- Channel.init[String](256)
          _     <- Fiber.init(read(p, lines))
          _     <- Fiber.init(p.waitFor.map(c => exit.set(Present(c))))
          // The last finalizer runs first: the kill gives the reader its end.
          _     <- Scope.ensure(p.destroyForcibly)
      yield RunningProcess(p, lines.streamUntilClosed(), exit)

    /** Reads the output of `p` into `lines`, a line at a time; closes `lines`
      * at the end of the output, when all its lines are read.
      */
    private def read(p: Process, lines: Channel[String])(using
        Frame,
    ): Unit < Async =
      AtomicRef
        .init(Chunk.empty[Byte])
        .map(carry =>
          Abort
            .run[Closed](
              Scope
                .run(
                  p.stdout.foreachChunk(bytes =>
                    carry.get.map { c =>
                      val (rest, ls) = Lines.split(c, bytes)
                      carry.set(rest).andThen(lines.putBatch(ls))
                    },
                  ),
                )
                .andThen(
                  carry.get.map(c =>
                    Lines.last(c) match
                        case Present(l) => lines.put(l)
                        case Absent     => Kyo.unit,
                  ),
                ),
            )
            .andThen(lines.closeAwaitEmpty)
            .unit,
        )
end ProcessPlatform
