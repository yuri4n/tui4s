// scalafix:off DisableSyntax
// EDGE: the Kyo Log.Unsafe (AllowUnsafe logger API) and java.time
// claude 2026-10-17: created this header (purity plan, phase P0)
package tui4s.internal

import kyo.*

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The Kyo logger of a run: `Tui.run` binds it with `Log.let`, so a `Log.info`
  * in user code or in a library writes into the run's [[LogSink]] and never
  * onto the screen. Lines at warn or above are printed after the terminal is
  * restored (without a log file).
  *
  * @param sink
  *   where the lines go
  * @param level
  *   the lowest level that is written: the level of the Kyo logger where the
  *   run starts (Kyo's own: `-Dkyo.Log.defaultLevel`, default warn)
  * @param name
  *   the logger name
  * @param also
  *   a logger that gets each line too (the headless kit keeps the logger of its
  *   caller so)
  */
// claude 2026-10-13: changed (T1, review R1-3): the Scaladoc names no SPEC
// section and no source (a term of the old engine)
// claude 2026-10-19: changed: the second logger `also` (L1b)
private[tui4s] final class TuiLog(
    sink: LogSink,
    val level: Log.Level,
    val name: String = "tui4s",
    also: Maybe[Log.Unsafe] = Absent,
) extends Log.Unsafe:
    def withName(name: String): Log.Unsafe =
      TuiLog(sink, level, name, also.map(_.withName(name)))

    private def write(
        at: Log.Level,
        msg: => String,
        t: Throwable | Null,
    )(using frame: Frame): Unit =
      if at.enabled(level) then
          // `a.enabled(b)`: `a` is at or above `b`.
          // claude 2026-10-19: changed (P6): LogSink.Keep, not a Boolean
          val keep =
            if at.enabled(Log.Level.warn) then LogSink.Keep.UntilRestore
            else LogSink.Keep.FileOnly
          val time = LocalTime.now().nn.format(TuiLog.Time)
          sink.line(
            s"$time ${at.toString.toUpperCase} $name [${frame.position.show}] $msg",
            keep,
          )
          if t != null then
              val text = java.io.StringWriter()
              t.printStackTrace(java.io.PrintWriter(text))
              text.toString.linesIterator.foreach(sink.line(_, keep))

    def trace(msg: => String)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.trace, msg, null)
        also.foreach(_.trace(msg))

    def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.trace, msg, t)
        also.foreach(_.trace(msg, t))

    def debug(msg: => String)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.debug, msg, null)
        also.foreach(_.debug(msg))

    def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.debug, msg, t)
        also.foreach(_.debug(msg, t))

    def info(msg: => String)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.info, msg, null)
        also.foreach(_.info(msg))

    def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.info, msg, t)
        also.foreach(_.info(msg, t))

    def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.warn, msg, null)
        also.foreach(_.warn(msg))

    def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.warn, msg, t)
        also.foreach(_.warn(msg, t))

    def error(msg: => String)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.error, msg, null)
        also.foreach(_.error(msg))

    def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
        write(Log.Level.error, msg, t)
        also.foreach(_.error(msg, t))
end TuiLog

private[tui4s] object TuiLog:
    private val Time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").nn

    /** Runs `v` with a [[TuiLog]] into `sink` as the Kyo logger, at the level
      * of the logger that `v` had.
      */
    def let[A, S](sink: LogSink)(v: A < S)(using Frame): A < S =
      Log.use(current => Log.let(Log(TuiLog(sink, current.level)))(v))

    /** Runs `v` with a [[TuiLog]] into `sink` that also gives each line to the
      * logger that `v` had, at its level (the headless kit: a test that binds
      * its own logger still gets the lines).
      */
    // claude 2026-10-19: created (L1b)
    def tee[A, S](sink: LogSink)(v: A < S)(using Frame): A < S =
      Log.use(current =>
        Log.let(
          Log(TuiLog(sink, current.level, also = Present(current.unsafe))),
        )(v),
      )

    /** Waits (at most `max`) until Kyo has written every log line that was sent
      * before: Kyo writes its log on its own fiber.
      */
    def flush(max: Duration)(using Frame): Unit < Async =
      Abort.run[Timeout](Async.timeout(max)(Log.flush)).unit
end TuiLog
