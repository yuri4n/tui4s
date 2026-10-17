package tui4s.internal

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.control.NonFatal

/** Where the log lines and the captured output of one run go while the UI is
  * up: the `TuiOptions.logFile`, or a memory ring that is printed to the real
  * stderr after the terminal is restored. Thread-safe.
  *
  * The ring keeps only the lines to print after the restore (captured output,
  * log lines at warn or above): the newest [[capacity]], and a count of the
  * older ones.
  *
  * Kyo writes its log lines later, on its own fiber, so lines can come after
  * [[finish]]. Such a line is still appended to the log file. Without a log
  * file, an important line goes to the real stderr at once and the others are
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
// claude 2026-10-13: changed (T1, review R1-3): the Scaladoc names no SPEC
// section
private[tui4s] final class LogSink(file: Option[Path], val capacity: Int = 200):
    private val ring                         = scala.collection.mutable.ArrayDeque.empty[String]
    private var dropped                      = 0L
    private var out: java.io.Writer | Null   = null
    private var finished: PrintStream | Null = null
    private var problem: String | Null       = null
    // The log file for the lines that come after `finish`.
    private var lateFile: Path | Null        = null

    file.foreach { path =>
      try
          out = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
          )
      catch
          case NonFatal(e) =>
            problem = s"tui4s: the log file $path cannot be written: $e"
    }

    /** True when the lines go to a file. */
    def toFile: Boolean = synchronized(out != null)

    /** Adds one line (without the line break). `important`: captured output or
      * a log line at warn or above, printed after the restore when there is no
      * log file.
      */
    def line(text: String, important: Boolean): Unit = synchronized {
      val done = finished
      if done != null then
          val late = lateFile
          if late != null then appendLate(late, text, important, done)
          else if important then done.println(text) // tui4s: allowed
      else
          val w = out
          if w != null then
              try
                  w.write(text)
                  w.write('\n')
                  w.flush()
              catch
                  case NonFatal(e) =>
                    out = null
                    problem = s"tui4s: writing the log file failed: $e"
                    keep(text, important)
          else keep(text, important)
          end if
      end if
    }

    /** Appends a line that came after [[finish]] to the log file. */
    private def appendLate(
        path: Path,
        text: String,
        important: Boolean,
        err: PrintStream,
    ): Unit =
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
      catch
          case NonFatal(e) =>
            lateFile = null
            val problem = s"tui4s: writing the log file failed: $e"
            err.println(problem) // tui4s: allowed
            if important then err.println(text) // tui4s: allowed

    private def keep(text: String, important: Boolean): Unit =
      if important then
          if ring.size >= capacity then
              discard(ring.removeHead())
              dropped += 1
          ring += text

    /** The terminal is restored: closes the file, prints the kept lines to
      * `err`. Later lines go to the file, or (important ones) to `err` at once.
      * Idempotent.
      */
    def finish(err: PrintStream): Unit = synchronized {
      if finished == null then
          finished = err
          val w = out
          if w != null then
              try w.close()
              catch case NonFatal(_) => ()
              out = null
              lateFile = file.orNull
          if dropped > 0 then
              val note = s"tui4s: $dropped earlier output lines are not shown"
              err.println(note) // tui4s: allowed
          ring.foreach(err.println) // tui4s: allowed
          ring.clear()
          val p = problem
          if p != null then err.println(p) // tui4s: allowed
          err.flush()
    }

    /** The lines that wait for the restore (for tests). */
    def kept: Seq[String] = synchronized(ring.toVector)
end LogSink
