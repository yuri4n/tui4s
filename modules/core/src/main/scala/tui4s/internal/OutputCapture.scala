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
    // Every forwarder made so far (an older one can still be in use, for
    // example by scala.Console), newest first.
    private var forwards: List[Forward]         = Nil
    private var out: PrintStream | Null         = null
    private var err: PrintStream | Null         = null
    private var pinned                          = false
    private var active: Capture | Null          = null

    /** The real stderr: `System.err` as it was before tui4s replaced it. */
    def realErr: PrintStream = synchronized {
      val e = originalErr
      if e != null then e else java.lang.System.err.nn
    }

    /** The real stdout: `System.out` as it was before tui4s replaced it. */
    def realOut: PrintStream = synchronized {
      val o = originalOut
      if o != null then o else java.lang.System.out.nn
    }

    /** Installs the forwarding streams for good (transparent while no capture
      * runs), so that `scala.Console` binds to them. Idempotent.
      */
    def install(): Unit = synchronized {
      pinned = true
      installStreams()
    }

    /** Installs the forwarders of the current `System.out` and `System.err`.
      * A forwarder is made again only when the program has set its own stream
      * since: the new forwarder writes to that stream, and a stop restores it.
      * A forwarder never changes its stream, so forwarders cannot write to
      * each other in a loop.
      */
    private def installStreams(): Unit =
        val sysOut = java.lang.System.out.nn
        val sysErr = java.lang.System.err.nn
        if out == null || ((sysOut ne out) && (sysOut ne originalOut)) then
            val f = Forward(sysOut, sysOut.charset.nn)
            forwards = f :: forwards
            out = PrintStream(f, true, sysOut.charset.nn)
            originalOut = sysOut
        if err == null || ((sysErr ne err) && (sysErr ne originalErr)) then
            val f = Forward(sysErr, sysErr.charset.nn)
            forwards = f :: forwards
            err = PrintStream(f, true, sysErr.charset.nn)
            originalErr = sysErr
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
          * `System.err` are again the streams they were at the start of this
          * capture. A last line without its line break goes to the sink.
          */
        def stop(): Unit = OutputCapture.synchronized {
          val s = sink
          if s != null && (active eq this) then
              forwards.foreach(_.drain(s))
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

    /** The longest line the capture keeps, in bytes; a longer one is cut. */
    val MaxLine: Int = 8192

    /** Writes to `original`, or line by line to the sink of the running
      * capture. A '\r' that is not followed by '\n' starts the line again (a
      * progress bar writes its updates this way), and a line is cut at
      * [[MaxLine]] bytes, so a long-running capture keeps little in memory.
      */
    private final class Forward(original: PrintStream, charset: Charset)
        extends OutputStream:
        private val partial = ByteArrayOutputStream()
        // The last byte was a '\r'.
        private var carriage = false

        override def write(b: Int): Unit = OutputCapture.synchronized {
          val s = sinkNow
          if s == null then original.write(b)
          else put(s, b & 0xff)
        }

        override def write(b: Array[Byte], off: Int, len: Int): Unit =
          OutputCapture.synchronized {
            val s = sinkNow
            if s == null then original.write(b, off, len)
            else
                var i = off
                while i < off + len do
                    put(s, b(i) & 0xff)
                    i += 1
          }

        override def flush(): Unit = OutputCapture.synchronized {
          if sinkNow == null then original.flush()
        }

        private def put(s: LogSink, b: Int): Unit =
          if b == '\n' then emit(s)
          else
              if carriage then
                  partial.reset()
                  carriage = false
              if b == '\r' then carriage = true
              else
                  // Cut only before the first byte of a character.
                  if partial.size >= MaxLine && (b & 0xc0) != 0x80 then emit(s)
                  partial.write(b)

        private def emit(s: LogSink): Unit =
            val text = partial.toString(charset)
            partial.reset()
            carriage = false
            s.line(text, important = true)

        /** Sends a line without its line break to `s`. */
        def drain(s: LogSink): Unit =
          if partial.size > 0 then emit(s)
          carriage = false
    end Forward
end OutputCapture
