// scalafix:off DisableSyntax
// EDGE: the log file (java.nio.file) and the stderr after the restore
// claude 2026-10-17: created this header (purity plan, phase P0)
package tui4s.internal

import kyo.*

import java.io.{PrintStream, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.immutable.Queue
import scala.util.control.NonFatal

/** Where the log lines and the captured output of one run go while the UI is
  * up: the `TuiOptions.logFile`, or a memory ring that is printed to the real
  * stderr after the terminal is restored. Thread-safe.
  *
  * A run opens its sink with [[LogSink.open]], an effect that also pushes the
  * release step 'log sink' ([[finish]]) to the release stack of the run. The
  * state of the sink is one value, [[LogSink.State]]: the lines go to the file
  * (`ToFile`), into the ring (`ToRing`), or, after the finish, to the file or
  * to stderr at once (`Done`). It is the only mutable part, under the monitor
  * of the sink; the ring is a pure value ([[LogSink.Ring]]).
  *
  * The ring keeps only the lines to print after the restore
  * ([[LogSink.Keep.UntilRestore]]: captured output, log lines at warn or
  * above): the newest [[capacity]], and a count of the older ones.
  *
  * Kyo writes its log lines later, on its own fiber, so lines can come after
  * [[finish]]. Such a line is still appended to the log file. Without a log
  * file, a line to keep goes to the real stderr at once and the others are
  * dropped.
  *
  * @param file
  *   the log file (appended to), or `None` for the memory ring
  * @param capacity
  *   the lines that the ring keeps
  */
// claude 2026-10-13: changed (T1): a 'tui4s: allowed' marker at each print:
// each one runs after the terminal is restored, by design (the rule "no
// stderr and no println in core main" of the pull request T1)
// claude 2026-10-19: changed (P6): one state value; the open is an effect
private[tui4s] final class LogSink private (
    file: Option[Path],
    val capacity: Int,
    start: LogSink.State,
):
    import LogSink.*

    // The only mutable part of the sink; read and written under its monitor.
    private var state: State = start

    /** Adds one line (without the line break). `keep`: whether the line is
      * printed after the restore when there is no log file.
      */
    // claude 2026-10-19: changed (P6): Keep, not a Boolean; one state
    def line(text: String, keep: Keep): Unit = synchronized {
      state = state match
          case State.ToFile(w)       =>
            try
                w.write(text)
                w.write('\n')
                w.flush()
                state
            catch
                case NonFatal(e) =>
                  val problem = s"tui4s: writing the log file failed: $e"
                  State.ToRing(
                    Ring(Queue.empty, 0L, Present(problem))
                      .add(text, keep, capacity),
                  )
          case State.ToRing(ring)    =>
            State.ToRing(ring.add(text, keep, capacity))
          case State.Done(err, late) =>
            late.fold {
              if keep == Keep.UntilRestore then err.println(text) // tui4s: allowed
              state
            }(path => appendLate(path, text, keep, err))
    }

    /** Appends a line that came after [[finish]] to the log file. The state
      * after it: `Done` with no file when the write failed.
      */
    private def appendLate(
        path: Path,
        text: String,
        keep: Keep,
        err: PrintStream,
    ): State =
      try
          discard(
            Files.writeString(
              path,
              text + "\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            ),
          )
          state
      catch
          case NonFatal(e) =>
            val problem = s"tui4s: writing the log file failed: $e"
            err.println(problem)                                // tui4s: allowed
            if keep == Keep.UntilRestore then err.println(text) // tui4s: allowed
            State.Done(err, Absent)

    /** The terminal is restored: closes the file, prints the kept lines to
      * `err`. Later lines go to the file, or (the lines to keep) to `err` at
      * once. Idempotent. The release step 'log sink' of the run calls it.
      */
    // claude 2026-10-19: changed (P6): one state
    def finish(err: PrintStream): Unit = synchronized {
      state match
          case State.Done(_, _)   => ()
          case State.ToFile(w)    =>
            try w.close()
            catch case NonFatal(_) => ()
            state = State.Done(err, Maybe.fromOption(file))
            err.flush()
          case State.ToRing(ring) =>
            state = State.Done(err, Absent)
            if ring.dropped > 0 then
                val note =
                  s"tui4s: ${ring.dropped} earlier output lines are not shown"
                err.println(note)             // tui4s: allowed
            ring.lines.foreach(err.println)   // tui4s: allowed
            ring.problem.foreach(err.println) // tui4s: allowed
            err.flush()
    }

    /** The lines that wait for the restore (for tests). */
    def kept: Seq[String] = synchronized {
      state match
          case State.ToRing(ring) => ring.lines.toVector
          case _                  => Vector.empty
    }
end LogSink

// claude 2026-10-19: created (P6)
private[tui4s] object LogSink:
    /** What happens to a line when there is no log file. */
    enum Keep derives CanEqual:
        /** Kept for the print after the restore: captured output, a log line at
          * warn or above.
          */
        case UntilRestore

        /** Only for the log file: dropped when there is none. */
        case FileOnly
    end Keep

    /** The lines that wait for the restore: the newest ones, the count of the
      * older ones that were dropped, and the problem with the log file.
      */
    final case class Ring(
        lines: Queue[String],
        dropped: Long,
        problem: Maybe[String],
    ):
        /** The ring after `text`: unchanged for a line that is only for the
          * file; else `text` is the newest line, and above `capacity` the
          * oldest line goes (it is counted in `dropped`).
          */
        def add(text: String, keep: Keep, capacity: Int): Ring = keep match
            case Keep.FileOnly     => this
            case Keep.UntilRestore =>
              if lines.size >= capacity then
                  Ring(lines.drop(1).enqueue(text), dropped + 1, problem)
              else copy(lines = lines.enqueue(text))
    end Ring

    object Ring:
        val empty: Ring = Ring(Queue.empty, 0L, Absent)

    /** The state of a sink: where the next line goes. */
    enum State:
        /** To the log file. */
        case ToFile(writer: Writer)

        /** Into the ring (no log file, or the file failed). */
        case ToRing(ring: Ring)

        /** After the finish: to the log file `late` (when the run had one), or
          * to `err` at once.
          */
        case Done(err: PrintStream, late: Maybe[Path])
    end State

    /** Opens the sink of a run and pushes its finish (the release step 'log
      * sink', which prints the kept lines to `err()`) to `stack`, in one step.
      * A log file that cannot be opened is a problem that the finish prints;
      * the lines then go to the ring.
      */
    def open(
        file: Option[Path],
        stack: ReleaseStack,
        err: () => PrintStream,
    )(using Frame): LogSink < Sync =
      Sync.defer {
        val sink = openNow(file)
        stack.add("log sink")(() => sink.finish(err()))
        sink
      }

    /** The open of a sink outside an effect, with no release step (for the
      * tests and the headless runs).
      */
    def openNow(file: Option[Path], capacity: Int = 200): LogSink =
        val start = file match
            case None       => State.ToRing(Ring.empty)
            case Some(path) =>
              try
                  State.ToFile(
                    Files.newBufferedWriter(
                      path,
                      StandardCharsets.UTF_8,
                      StandardOpenOption.CREATE,
                      StandardOpenOption.APPEND,
                    ),
                  )
              catch
                  case NonFatal(e) =>
                    val problem =
                      s"tui4s: the log file $path cannot be written: $e"
                    State.ToRing(Ring(Queue.empty, 0L, Present(problem)))
        new LogSink(file, capacity.max(1), start)
    end openNow
end LogSink
