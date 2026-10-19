package tui4s.use

import kyo.*

import java.nio.charset.StandardCharsets.UTF_8

/** A process that runs: Kyo's `Process` (for `pid`, `destroy`, ...), its output
  * lines (the standard output and the standard error together, in order) as a
  * stream, and its exit code as a signal (Absent while it runs).
  *
  * Run `lines` once (for example with [[useStreamFold]]). It holds 256 lines
  * that nobody reads; then the process waits until a line is read.
  */
// claude 2026-10-19: created (L2a)
final case class RunningProcess(
    process: Process,
    lines: Stream[String, Async],
    exit: Signal[Maybe[Process.ExitCode]],
)

/** Why [[useProcess]] did not start a process. */
// claude 2026-10-19: created (L2a)
enum ProcessError derives CanEqual:
    /** The command did not start: kyo-system's reason (the program is not
      * found, the permission is denied).
      */
    case NotStarted(reason: CommandException)

    /** This platform has no processes: Scala.js (a browser has none). */
    case Unsupported
end ProcessError

/** Starts `cmd` (the program, then its arguments, with no shell) on the JVM,
  * with kyo-system's `Command`. The child gets an empty standard input, never
  * the terminal of the app, and its standard error goes into its output lines.
  * When the caller's `Scope` closes, the process is killed and its fibers end.
  * On Scala.js it fails with [[ProcessError.Unsupported]].
  *
  * {{{
  * for
  *     run    <- useProcess(Chunk("sbt", "test"))
  *     output <- useStreamFold(run.lines, Chunk.empty[String])((ls, l) => (ls :+ l).takeRight(500))
  * yield div(
  *   pre(output.map(_.mkString("\n"))),
  *   span(run.exit.map(_.fold("running")(c => s"exit ${c.toInt}"))),
  * )
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useProcess(cmd: Chunk[String])(using
    Frame,
): RunningProcess < (Sync & Scope & Abort[ProcessError]) =
  ProcessPlatform.start(cmd)

/** The pure line split of the output of a process. */
// claude 2026-10-19: created (L2a)
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
