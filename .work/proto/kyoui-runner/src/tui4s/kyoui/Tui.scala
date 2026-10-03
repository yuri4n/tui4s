package tui4s.kyoui

import jatatui.core.backend.TestBackend
import jatatui.crossterm.CrosstermBackend
import kyo.*
import kyo.UI.Keyboard
import kyo.UI.KeyboardEvent
import kyo.UI.Modifiers
import tui.crossterm.ClearType
import tui.crossterm.Command
import tui.crossterm.CrosstermJni
import tui.crossterm.Duration as CDuration
import tui4s.kyoui.internal.*

final case class TuiOptions(
    /** Ctrl-C ends the run (raw mode turns it into a key). */
    ctrlCQuits: Boolean = true,
    /** Focus the first focusable element (or the one with `focusAuto(true)`) when nothing has the focus. */
    autoFocus: Boolean = true,
    /** How the runner decides that the consequences of an event are all in (see [[Settle]]). */
    settle: Settle = Settle.Quiescent(100.millis),
    /** How long a frame waits for a mounting component's first content. */
    pendingTimeout: Duration = 2.seconds,
    /** Where stdout and stderr go while the terminal UI runs (they would corrupt the screen). */
    logFile: String = "tui4s.log"
)

/** How the runner knows that an event's consequences have all arrived before it flushes a frame. */
enum Settle:
    /** Until Kyo's scheduler has no queued or running task besides the runner itself (bounded by `max`). Reads the
      * scheduler's load; the most reliable choice with kyo's current `Signal` API.
      *
      * `confirm`: how many quiescent observations in a row, one scheduler hop apart, each followed by an empty inbox.
      */
    // claude 2026-10-03: changed: added `confirm` (default 2). The probe's scan is not atomic and a woken task is
    // invisible for a few instructions between `queue.poll()` and `runTask`; a second observation one hop later makes a
    // false "quiescent" much less likely (it cannot make it impossible; see REPORT.md).
    case Quiescent(max: Duration, confirm: Int = 2)

    /** Until `hops` scheduler round trips in a row bring nothing AND `window` has passed since the last news. */
    case Hops(hops: Int, window: Duration)
end Settle

enum FrameCause:
    case Initial, Input, Background

/** What one frame cost. `repainted`: cell writes into the kept buffer; `flushed`: cells sent to the terminal; `nanos`: from
  * the event to the end of the flush; `updates`: region values applied.
  */
final case class FrameStats(
    seq: Long,
    cause: FrameCause,
    repainted: Int,
    flushed: Int,
    nanos: Long,
    updates: Int,
    handleNanos: Long = 0,
    settleNanos: Long = 0,
    renderNanos: Long = 0,
    /** The focused element: its `id`, or its tree position when it has none. */
    focus: String = "",
    // claude 2026-10-03: created the two fields below for the step 6 measurements
    /** `System.nanoTime` at the end of the flush (so a caller can measure from the moment it sent the event). */
    endNanos: Long = 0,
    /** Subtrees painted in this frame (paint roots after nested roots are merged). */
    subtrees: Int = 0
)

/** The terminal runner for kyo-ui. */
object Tui:

    def run(ui: => UI < (Async & Scope))(using Frame): Unit < Async = run(TuiOptions())(ui)

    def run(options: TuiOptions)(ui: => UI < (Async & Scope))(using Frame): Unit < Async =
        Sync.defer(new CrosstermJni()).map { jni =>
            // F8: the restore is a Sync.ensure, so it has run when the run returns, also on an interrupt
            Sync.ensure(Sync.defer(restore(jni))) {
                Sync.defer(enter(jni)).andThen {
                    OutputGuard.during(options.logFile) {
                        Scope.run {
                            for
                                inbox  <- Channel.initUnscoped[Msg](4096)
                                canvas <- Sync.defer(Canvas(CrosstermBackend(jni)))
                                runner = Runner(inbox, canvas, options, Absent)
                                seq <- AtomicLong.init(1L)
                                _   <- Fiber.init(input(jni, inbox, seq))
                                _   <- runner.scoped(ui.map(runner.run))
                            yield ()
                        }
                    }
                }
            }
        }

    /** Ends the run. Call it from a handler (or from a fiber the UI started). */
    def quit(using Frame): Unit < Sync = Tui4sQuit()

    private def enter(jni: CrosstermJni): Unit =
        jni.enableRawMode()
        jni.execute(Command.EnterAlternateScreen(), Command.Hide(), Command.Clear(ClearType.All))

    private def restore(jni: CrosstermJni): Unit =
        def step(f: => Unit): Unit = try f catch case _: Throwable => ()
        step(jni.execute(Command.Show(), Command.LeaveAlternateScreen()))
        step(jni.disableRawMode())
        step(jni.flush())

    /** The input fiber: `poll` with a short timeout, then `read` (crossterm's `read` blocks and cannot be interrupted). */
    private def input(jni: CrosstermJni, inbox: Channel[Msg], seq: AtomicLong)(using Frame): Unit < Async =
        Abort.run[Closed] {
            Loop.forever {
                Sync.defer(jni.poll(CDuration(0L, 20_000_000))).map { ready =>
                    if !ready then Kyo.unit
                    else
                        for
                            ev <- Sync.defer(jni.read())
                            n  <- seq.getAndIncrement
                            _ <- Keys.toMsg(ev, n) match
                                case Some(m) => inbox.put(m)
                                case None    => Kyo.unit
                        yield ()
                }
            }
        }.unit
end Tui

/** A headless run on a jatatui `TestBackend`, for tests and measurements. */
final class Headless private (
    inbox: Channel[Msg],
    frames: Channel[FrameStats],
    val backend: TestBackend,
    seq: AtomicLong,
    run: Fiber[Unit, Scope]
):
    /** True when the run has ended (a quit). */
    def finished(using Frame): Boolean < Sync = run.done

    /** Waits until the run has ended, at most `timeout`. True when it ended. */
    // claude 2026-10-03: created awaitEnd. The tests slept a fixed 50 ms after the quit key and then read `finished`; on a
    // loaded machine the unmount can take longer. This reads `done` every 5 ms and returns as soon as it is true.
    def awaitEnd(timeout: Duration)(using Frame): Boolean < Async =
        Sync.defer(java.lang.System.nanoTime()).map { t0 =>
            Loop.foreach {
                run.done.map { d =>
                    if d then Loop.done(true)
                    else
                        Sync.defer(java.lang.System.nanoTime()).map { now =>
                            if now - t0 > timeout.toNanos then Loop.done(false) else Async.sleep(5.millis).andThen(Loop.continue)
                        }
                }
            }
        }

    /** The cell at (x, y) of the screen (to check colors). */
    def cell(x: Int, y: Int)(using Frame): jatatui.core.buffer.Cell < Sync = Sync.defer(backend.buffer().cellAt(x, y).copy())


    /** Sends a key and waits for its frame. Background frames that come first are returned too (in order). */
    def press(ev: KeyboardEvent)(using Frame): FrameStats < Async =
        seq.getAndIncrement.map { n =>
            inbox.put(Msg.Key(ev, n)).andThen {
                Loop.foreach {
                    frames.take.map { s =>
                        if s.cause == FrameCause.Input && s.seq == n then Loop.done(s) else Loop.continue
                    }
                }
            }
        }.handle(Abort.run[Closed]).map(_.getOrThrow)

    def key(k: Keyboard, ctrl: Boolean = false, shift: Boolean = false)(using Frame): FrameStats < Async =
        press(KeyboardEvent(k, Modifiers(ctrl = ctrl, shift = shift), Absent))

    def char(c: Char)(using Frame): FrameStats < Async =
        key(if c == ' ' then Keyboard.Space else Keyboard.Char(c))

    def typeText(s: String)(using Frame): Chunk[FrameStats] < Async = Kyo.foreach(Chunk.from(s.toSeq))(char)

    def resize(w: Int, h: Int)(using Frame): FrameStats < Async =
        seq.getAndIncrement.map { n =>
            Sync.defer(backend.resize(w, h)).andThen(inbox.put(Msg.Resize(Size(w, h), n))).andThen {
                Loop.foreach {
                    frames.take.map(s => if s.cause == FrameCause.Input && s.seq == n then Loop.done(s) else Loop.continue)
                }
            }
        }.handle(Abort.run[Closed]).map(_.getOrThrow)

    /** The next frame of any cause. */
    def nextFrame(using Frame): FrameStats < Async = Abort.run[Closed](frames.take).map(_.getOrThrow)

    /** Frames that arrived and were not consumed. */
    def drainFrames(using Frame): Chunk[FrameStats] < Sync = Abort.run[Closed](frames.drain).map(_.getOrElse(Chunk.empty))

    def lines(using Frame): Vector[String] < Sync = Sync.defer {
        val buf = backend.buffer()
        Vector.tabulate(buf.area.height)(y => (0 until buf.area.width).map(x => buf.cellAt(x, y).symbol()).mkString)
    }

    def screen(using Frame): String < Sync = lines.map(_.map(_.replaceAll("\\s+$", "")).mkString("\n"))

    def quit(using Frame): Unit < Async = Abort.run[Closed](inbox.put(Msg.Quit)).unit
end Headless

object Headless:
    def start(width: Int, height: Int, options: TuiOptions = TuiOptions())(ui: => UI < (Async & Scope))(using Frame): Headless < (Async & Scope) =
        for
            inbox   <- Channel.initUnscoped[Msg](4096)
            frames  <- Channel.initUnscoped[FrameStats](1 << 16)
            backend <- Sync.defer(TestBackend(width, height))
            canvas  <- Sync.defer(Canvas(backend))
            runner = Runner(inbox, canvas, options, Present(frames))
            seq <- AtomicLong.init(1L)
            run <- Fiber.init(runner.scoped(ui.map(runner.run)))
            _   <- Abort.run[Closed](frames.take).map(_.getOrThrow)
        yield Headless(inbox, frames, backend, seq, run)
end Headless

/** Redirects stdout and stderr to a file while the terminal UI runs (a second documented mutable place: the JVM's global
  * `System.out` / `System.err`).
  */
object OutputGuard:
    def during[A, S](file: String)(v: => A < S)(using Frame): A < (S & Sync) =
        Sync.defer {
            val out = java.io.PrintStream(java.io.FileOutputStream(file, true), true)
            (java.lang.System.out, java.lang.System.err, out)
        }.map { (oldOut, oldErr, out) =>
            Sync.ensure(Sync.defer {
                java.lang.System.setOut(oldOut)
                java.lang.System.setErr(oldErr)
                out.close()
            }) {
                Sync.defer {
                    java.lang.System.setOut(out)
                    java.lang.System.setErr(out)
                }.andThen(v)
            }
        }
end OutputGuard
