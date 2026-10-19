// scalafix:off DisableSyntax
// EDGE: System.out, System.err (setOut, setErr) and scala.Console
// claude 2026-10-17: created this header (purity plan, phase P0)
package tui4s.internal

import java.io.{OutputStream, PrintStream}
import java.nio.charset.Charset

/** Captures `System.out` and `System.err` while the UI is up
  * (`TuiOptions.captureOutput`): a stray `println` in user code or in a library
  * goes to the run's [[LogSink]], not over the screen. JVM-global.
  *
  * It installs two forwarding streams as `System.out` and `System.err`. While
  * no capture runs, they write to the original streams unchanged; during a
  * capture, each line goes to the sink. Scala's `println` writes to
  * `scala.Console.out`, which keeps the stream that `System.out` was when
  * `scala.Console` first loaded, so the installation also loads
  * `scala.Console`. A main that calls [[install]] before its code uses
  * `scala.Console` gets `println` always captured (`TuiApp` does this in its
  * constructor); with `Tui.run` alone it is captured when nothing used
  * `scala.Console` before the first run.
  *
  * The state is one value ([[OutputCapture.State]]), the only `var` of the
  * object, under its monitor. Each forwarder keeps the line that it has so far
  * as a [[LineSplit]] value (the buffer of the capture), under the same
  * monitor. The release step 'output capture' of a run stops the capture.
  */
// claude 2026-10-13: changed (T1): a 'tui4s: allowed' marker at each use of
// System.out and System.err: the forwarders replace and restore them, by
// design (the rule "no stderr and no println in core main" of the pull
// request T1)
// claude 2026-10-13: changed (T1, review R1-3): the Scaladoc names install,
// not TuiApp (it comes later), and no SPEC section
// claude 2026-10-13: changed (T2): TuiApp calls install
// claude 2026-10-19: changed (P6): one state value; the splitter is a value
private[tui4s] object OutputCapture:
    /** One forwarding stream: `stream` (installed as `System.out` or
      * `System.err`) writes through `forward` to `original`, the stream that
      * the program had before.
      */
    private final case class Fwd(
        stream: PrintStream,
        original: PrintStream,
        forward: Forward,
    )

    /** The forwarders of `System.out` and `System.err` now; every forwarder
      * made so far, newest first (an older one can still be in use, for
      * example by `scala.Console`); true when [[install]] installed them for
      * good; the running capture.
      */
    private final case class State(
        out: Option[Fwd],
        err: Option[Fwd],
        all: List[Fwd],
        pinned: Boolean,
        active: Option[Capture],
    )

    // The only mutable part of the object; read and written under its
    // monitor.
    private var state = State(None, None, Nil, pinned = false, active = None)

    /** The real stderr: `System.err` as it was before tui4s replaced it. */
    def realErr: PrintStream = synchronized {
      state.err.fold(java.lang.System.err.nn)(_.original) // tui4s: allowed
    }

    /** The real stdout: `System.out` as it was before tui4s replaced it. */
    def realOut: PrintStream = synchronized {
      state.out.fold(java.lang.System.out.nn)(_.original) // tui4s: allowed
    }

    /** Installs the forwarding streams for good (transparent while no capture
      * runs), so that `scala.Console` binds to them. Idempotent.
      */
    def install(): Unit = synchronized {
      state = state.copy(pinned = true)
      installStreams()
    }

    /** Installs the forwarders of the current `System.out` and `System.err`
      * ([[forwarder]]).
      */
    private def installStreams(): Unit =
        val out  = forwarder(java.lang.System.out.nn, state.out) // tui4s: allowed
        val err  = forwarder(java.lang.System.err.nn, state.err) // tui4s: allowed
        val made = List(err, out).filterNot(f => state.all.exists(_ eq f))
        state = state.copy(out = Some(out), err = Some(err), all = made ++ state.all)
        java.lang.System.setOut(out.stream)
        java.lang.System.setErr(err.stream)
        // Loads scala.Console now, bound to the forwarders, if nothing used it
        // before.
        discard(scala.Console.out)
    end installStreams

    /** The forwarder for the system stream `sys`: the current one, or a new
      * one when there is none or the program has set its own stream since.
      * The new forwarder writes to that stream, and a stop restores it. A
      * forwarder never changes its stream, so forwarders cannot write to each
      * other in a loop.
      */
    private def forwarder(sys: PrintStream, current: Option[Fwd]): Fwd =
      current
        .filter(f => (sys eq f.stream) || (sys eq f.original))
        .getOrElse {
          val charset = sys.charset.nn
          val forward = Forward(sys, charset)
          Fwd(PrintStream(forward, true, charset), sys, forward)
        }

    /** True when `scala.Console.out` and `scala.Console.err` (of the calling
      * thread) are forwarders of tui4s, so that a capture also takes Scala's
      * `println`. False when `scala.Console` was first used before the
      * forwarders were installed: it keeps the streams of that time (RC-10, IR
      * R3).
      */
    // claude 2026-10-13: created (T1, RC-10 part: the start-time check of R3)
    def capturesConsole: Boolean = synchronized {
      val o = scala.Console.out
      val e = scala.Console.err
      state.all.exists(_.stream eq o) && state.all.exists(_.stream eq e)
    }

    /** Starts capturing into `sink`; the result stops it. A second capture
      * while one runs captures nothing (its stop does nothing).
      */
    def start(sink: LogSink): Capture = synchronized {
      val c = Capture(sink)
      if state.active.isEmpty then
          installStreams()
          state = state.copy(active = Some(c))
      c
    }

    /** One capture. [[stop]] is idempotent. */
    final class Capture private[OutputCapture] (
        private[OutputCapture] val sink: LogSink,
    ):
        /** True while this capture takes the output. */
        def capturing: Boolean =
          OutputCapture.synchronized(state.active.exists(_ eq this))

        /** Stops capturing: the forwarders write to the original streams again
          * and, unless [[install]] installed them for good, `System.out` and
          * `System.err` are again the streams they were at the start of this
          * capture. A last line without its line break goes to the sink.
          */
        def stop(): Unit = OutputCapture.synchronized {
          if state.active.exists(_ eq this) then
              state.all.foreach(_.forward.drain(sink))
              state = state.copy(active = None)
              if !state.pinned then
                  state.out.foreach { f =>
                    if java.lang.System.out eq f.stream then // tui4s: allowed
                        java.lang.System.setOut(f.original)
                  }
                  state.err.foreach { f =>
                    if java.lang.System.err eq f.stream then // tui4s: allowed
                        java.lang.System.setErr(f.original)
                  }
              end if
          end if
        }
    end Capture

    /** The sink of the running capture. */
    private def sinkNow: Option[LogSink] = state.active.map(_.sink)

    /** The longest line the capture keeps, in bytes; a longer one is cut. */
    val MaxLine: Int = LineSplit.MaxLine

    /** Writes to `original`, or line by line ([[LineSplit]]) to the sink of
      * the running capture.
      */
    private final class Forward(original: PrintStream, charset: Charset)
        extends OutputStream:
        // The line so far (the buffer of the capture), under the monitor of
        // OutputCapture.
        private var split = LineSplit.empty

        override def write(b: Int): Unit = OutputCapture.synchronized {
          sinkNow.fold(original.write(b))(s => feed(s, Array(b.toByte), 0, 1))
        }

        override def write(b: Array[Byte], off: Int, len: Int): Unit =
          OutputCapture.synchronized {
            sinkNow.fold(original.write(b, off, len))(s => feed(s, b, off, len))
          }

        override def flush(): Unit = OutputCapture.synchronized {
          if sinkNow.isEmpty then original.flush()
        }

        private def feed(s: LogSink, b: Array[Byte], off: Int, len: Int): Unit =
            val (next, lines) = split.feed(b, off, len)
            split = next
            lines.foreach(emit(s, _))

        private def emit(s: LogSink, line: Vector[Byte]): Unit =
          s.line(String(line.toArray, charset), LogSink.Keep.UntilRestore)

        /** Sends a line without its line break to `s`. */
        def drain(s: LogSink): Unit =
            val (next, last) = split.drain
            split = next
            last.foreach(emit(s, _))
    end Forward
end OutputCapture
