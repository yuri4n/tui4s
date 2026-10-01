package tui4s.internal

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset

/** Captures `System.out` and `System.err` while the UI is up (SPEC 5.10,
  * `TuiOptions.captureOutput`): a stray `println` in user code or in a library
  * goes to the run's [[LogSink]], not over the screen. JVM-global.
  *
  * It installs two forwarding streams as `System.out` and `System.err`. While
  * no capture runs, they write to the original streams unchanged; during a
  * capture, each line goes to the sink. Scala's `println` writes to
  * `scala.Console.out`, which keeps the stream that `System.out` was when
  * `scala.Console` first loaded, so the installation also loads
  * `scala.Console`. `TuiApp` installs the forwarders in its constructor, before
  * user code runs, so `println` is always captured there; with `Tui.run` it is
  * captured when nothing used `scala.Console` before the first run.
  */
private[tui4s] object OutputCapture:
    private var originalOut: PrintStream | Null = null
    private var originalErr: PrintStream | Null = null
    private var outForward: Forward | Null      = null
    private var errForward: Forward | Null      = null
    private var out: PrintStream | Null         = null
    private var err: PrintStream | Null         = null
    private var pinned                          = false
    private var active: Capture | Null          = null

    /** The real stderr: `System.err` as it was before tui4s replaced it. */
    def realErr: PrintStream = synchronized {
      val e = originalErr
      if e != null then e else java.lang.System.err.nn
    }

    /** Installs the forwarding streams for good (transparent while no capture
      * runs), so that `scala.Console` binds to them. Idempotent.
      */
    def install(): Unit = synchronized {
      pinned = true
      installStreams()
    }

    private def installStreams(): Unit =
        if out == null then
            val sysOut = java.lang.System.out.nn
            val sysErr = java.lang.System.err.nn
            val of     = Forward(sysOut, sysOut.charset.nn)
            val ef     = Forward(sysErr, sysErr.charset.nn)
            originalOut = sysOut
            originalErr = sysErr
            outForward = of
            errForward = ef
            out = PrintStream(of, true, sysOut.charset.nn)
            err = PrintStream(ef, true, sysErr.charset.nn)
        end if
        java.lang.System.setOut(out)
        java.lang.System.setErr(err)
        // Loads scala.Console now, bound to the forwarders, if nothing used it
        // before.
        discard(scala.Console.out)
    end installStreams

    /** Starts capturing into `sink`; the result stops it. A second capture
      * while one runs captures nothing (its stop does nothing).
      */
    def start(sink: LogSink): Capture = synchronized {
      if active != null then Capture(null)
      else
          installStreams()
          val c = Capture(sink)
          active = c
          c
    }

    /** One capture. [[stop]] is idempotent. */
    final class Capture private[OutputCapture] (
        private[OutputCapture] val sink: LogSink | Null,
    ):
        /** True while this capture takes the output. */
        def capturing: Boolean = OutputCapture.synchronized(active eq this)

        /** Stops capturing: the forwarders write to the original streams again
          * and, unless `TuiApp` installed them for good, `System.out` and
          * `System.err` are the original streams again. A last line without its
          * line break goes to the sink.
          */
        def stop(): Unit = OutputCapture.synchronized {
          val s = sink
          if s != null && (active eq this) then
              Seq(outForward, errForward).foreach(f =>
                if f != null then f.drain(s),
              )
              active = null
              if !pinned then
                  if java.lang.System.out eq out then
                      java.lang.System.setOut(originalOut)
                  if java.lang.System.err eq err then
                      java.lang.System.setErr(originalErr)
          end if
        }
    end Capture

    /** The sink of the running capture; `null` when none runs. */
    private def sinkNow: LogSink | Null =
        val c = active
        if c == null then null else c.sink

    /** Writes to `original`, or line by line to the sink of the running
      * capture.
      */
    private final class Forward(original: PrintStream, charset: Charset)
        extends OutputStream:
        private val partial = ByteArrayOutputStream()

        override def write(b: Int): Unit = OutputCapture.synchronized {
          val s = sinkNow
          if s == null then original.write(b)
          else if b == '\n' then emit(s)
          else partial.write(b)
        }

        override def write(b: Array[Byte], off: Int, len: Int): Unit =
          OutputCapture.synchronized {
            val s = sinkNow
            if s == null then original.write(b, off, len)
            else
                var i = off
                while i < off + len do
                    if b(i) == '\n' then emit(s)
                    else partial.write(b(i).toInt)
                    i += 1
          }

        override def flush(): Unit = OutputCapture.synchronized {
          if sinkNow == null then original.flush()
        }

        private def emit(s: LogSink): Unit =
            var text = partial.toString(charset)
            if text.endsWith("\r") then text = text.dropRight(1)
            partial.reset()
            s.line(text, important = true)

        /** Sends a line without its line break to `s`. */
        def drain(s: LogSink): Unit =
          if partial.size > 0 then emit(s)
    end Forward
end OutputCapture
