package tui4s.testkit

// claude 2026-10-09: created (carried from .work/proto/kyoui-runner/src/tui4s/kyoui/Tui.scala lines 127-215; new path and package only)

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.Keyboard
import kyo.UI.KeyboardEvent
import kyo.UI.Modifiers
import tui4s.FrameCause
import tui4s.FrameStats
import tui4s.TuiOptions
import tui4s.internal.*

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
