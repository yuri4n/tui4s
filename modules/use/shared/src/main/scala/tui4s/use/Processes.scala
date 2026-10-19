package tui4s.use

import kyo.*

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
  * When the caller's `Scope` closes, the process and the processes that it
  * started (its descendants at that time) are killed, and the fibers end; the
  * close does not wait for the end of the output. A program that the child
  * started and left (its parent ended before) is not a descendant: it keeps
  * running, and a Kyo thread reads the output that it holds open until it ends.
  * On Scala.js it fails with [[ProcessError.Unsupported]].
  *
  * {{{
  * for
  *     run    <- useProcess(Chunk("sbt", "test"))
  *     output <- useStreamFold(run.lines, Chunk.empty[String])((ls, l) => (ls :+ l).takeRight(500))
  * yield div(
  *   pre(output.value.map(_.mkString("\n"))),
  *   span(run.exit.map(_.fold("running")(c => s"exit ${c.toInt}"))),
  * )
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useProcess(cmd: Chunk[String])(using
    Frame,
): RunningProcess < (Sync & Scope & Abort[ProcessError]) =
  ProcessPlatform.start(cmd)
