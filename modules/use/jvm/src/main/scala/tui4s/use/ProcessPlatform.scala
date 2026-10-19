package tui4s.use

import kyo.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The processes of the JVM: kyo-system's `Command`. */
// claude 2026-10-19: created (L2a)
private[use] object ProcessPlatform:

    def start(cmd: Chunk[String])(using
        Frame,
    ): RunningProcess < (Sync & Scope & Abort[ProcessError]) =
      for
          // spawn kills the process when the Scope closes.
          p      <- Abort
                      .run[CommandException](
                        Command(cmd*).stdin("").redirectErrorStream(true).spawn,
                      )
                      .map {
                        case Result.Success(p) => p
                        case Result.Failure(e) =>
                          Abort.fail(ProcessError.NotStarted(e))
                        case Result.Panic(t)   => Abort.panic(t)
                      }
          exit   <- Signal.initRef(Maybe.empty[Process.ExitCode])
          lines  <- Channel.init[String](256)
          // claude 2026-10-19: changed: the reader is not a fiber of the
          // Scope, so its close does not wait for the end of the output; the
          // kill takes the descendants too (L2a review)
          // The read of the output blocks, and an interrupt does not stop it:
          // a program that the child left can hold the output open. So the
          // close interrupts the reader and does not wait for it.
          reader <- Fiber.initUnscoped(read(p, lines))
          _      <- Scope.ensure(reader.interrupt.unit)
          _      <- Fiber.init(p.waitFor.map(c => exit.set(Present(c))))
          // The last finalizer runs first: the kill gives the reader its end.
          _      <- Scope.ensure(killTree(p))
      yield RunningProcess(p, lines.streamUntilClosed(), exit)

    /** Kills the descendants of `p` (the processes that it started and that
      * still run under it), then `p`.
      */
    // claude 2026-10-19: created (L2a review)
    private def killTree(p: Process)(using Frame): Unit < Sync =
      p.pid
        .map(pid =>
          Sync.defer(
            ProcessHandle
              .of(pid)
              .nn
              .toScala
              .foreach(h =>
                h.descendants()
                  .nn
                  .toList
                  .nn
                  .asScala
                  .foreach(_.destroyForcibly()),
              ),
          ),
        )
        .andThen(p.destroyForcibly)

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

/** The pure line split of the output of a process. */
// claude 2026-10-19: created (L2a); changed: on the JVM only, the one
// user (L2a review)
private[use] object Lines:
    /** The complete lines of `carry ++ bytes` (UTF-8, without the line break
      * and a "\r" before it), and the bytes after the last line break. A line
      * break is never inside a UTF-8 character, so a cut of the bytes at any
      * place gives the same lines.
      */
    def split(
        carry: Chunk[Byte],
        bytes: Chunk[Byte],
    ): (Chunk[Byte], Chunk[String]) =
        val all = carry ++ bytes
        val cut = all.lastIndexOf('\n'.toByte)
        if cut < 0 then (all, Chunk.empty)
        else
            val text = String(all.take(cut).toArray, UTF_8)
            (all.drop(cut + 1), Chunk.from(text.split("\n", -1)).map(line))
    end split

    /** The last line, when the output does not end with a line break. */
    def last(carry: Chunk[Byte]): Maybe[String] =
      if carry.isEmpty then Absent
      else Present(line(String(carry.toArray, UTF_8)))

    private def line(s: String): String = s.stripSuffix("\r")
end Lines
