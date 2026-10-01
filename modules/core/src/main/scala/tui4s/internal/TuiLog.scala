package tui4s.internal

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kyo.*

/** The Kyo logger of a run (SPEC 5.10): `Tui.run` binds it with `Log.let`, so a
  * `Log.info` in user code or in a source writes into the run's [[LogSink]] and
  * never onto the screen. Lines at warn or above are printed after the terminal
  * is restored (without a log file).
  *
  * @param sink
  *   where the lines go
  * @param level
  *   the lowest level that is written: the level of Kyo's own logger
  *   (`-Dkyo.Log.defaultLevel`, default warn)
  * @param name
  *   the logger name
  */
private[tui4s] final class TuiLog(
    sink: LogSink,
    val level: Log.Level,
    val name: String = "tui4s",
) extends Log.Unsafe:
    def withName(name: String): Log.Unsafe = TuiLog(sink, level, name)

    private def write(
        at: Log.Level,
        msg: => String,
        t: Throwable | Null,
    )(using frame: Frame): Unit =
      if at.enabled(level) then
          // `a.enabled(b)`: `a` is at or above `b`.
          val important = at.enabled(Log.Level.warn)
          val time      = LocalTime.now().nn.format(TuiLog.Time)
          sink.line(
            s"$time ${at.toString.toUpperCase} $name [${frame.position.show}] $msg",
            important,
          )
          if t != null then
              val text = java.io.StringWriter()
              t.printStackTrace(java.io.PrintWriter(text))
              text.toString.linesIterator.foreach(sink.line(_, important))

    def trace(msg: => String)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.trace, msg, null)

    def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.trace, msg, t)

    def debug(msg: => String)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.debug, msg, null)

    def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.debug, msg, t)

    def info(msg: => String)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.info, msg, null)

    def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.info, msg, t)

    def warn(msg: => String)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.warn, msg, null)

    def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.warn, msg, t)

    def error(msg: => String)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.error, msg, null)

    def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit =
      write(Log.Level.error, msg, t)
end TuiLog

private[tui4s] object TuiLog:
    private val Time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").nn

    /** Runs `v` with a [[TuiLog]] into `sink` as the Kyo logger. */
    def let[A, S](sink: LogSink)(v: A < S)(using Frame): A < S =
      Log.let(Log(TuiLog(sink, Log.live.level)))(v)
