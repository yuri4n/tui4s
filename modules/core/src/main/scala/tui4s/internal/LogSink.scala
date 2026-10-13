package tui4s.internal

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import scala.util.control.NonFatal

/** Where the log lines and the captured output of one run go while the UI is up
  * (SPEC 5.10): the `logFile`, or a memory ring that is printed to the real
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
          else if important then done.println(text)
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
            err.println(s"tui4s: writing the log file failed: $e")
            if important then err.println(text)

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
              err.println(s"tui4s: $dropped earlier output lines are not shown")
          ring.foreach(err.println)
          ring.clear()
          val p = problem
          if p != null then err.println(p)
          err.flush()
    }

    /** The lines that wait for the restore (for tests). */
    def kept: Seq[String] = synchronized(ring.toVector)
end LogSink
