package tui4s.testkit

import jatatui.core.backend.TestBackend
import kyo.*
import tui4s.FrameCause
import tui4s.FrameStats
import tui4s.TuiOptions
import tui4s.internal.*

/** A headless run on a jatatui `TestBackend`, for tests and measurements.
  *
  * The kit is strict: `screen` and `lines` return what the screen shows NOW,
  * and nothing waits unless its name says so (`nextFrame`, `awaitEnd`,
  * `awaitScreen`). A test that writes a `SignalRef` and wants to see the result
  * waits with `awaitScreen` (or `nextFrame`, when it knows that one frame
  * follows).
  *
  * The run ends with the enclosing `Scope`: `start` registers a finalizer that
  * posts the quit message and waits (at most 5 s) until the run fiber has
  * ended, so no watcher outlives the test.
  */
// claude 2026-10-12: changed: the final API (RC-7, D-15): the quit value, Screen,
// awaitScreen, Vector[FrameStats], no TestBackend and no jatatui Cell in the
// API; the key methods are C3
final class Headless private (
    inbox: Channel[Msg],
    frames: Channel[FrameStats],
    backend: TestBackend,
    seq: AtomicLong,
    run: Fiber[Unit, Scope],
    runner: Runner,
):
    /** Ends the run. The same action as the quit value that `start` gave the
      * UI.
      */
    // claude 2026-10-12: changed (review R2-8 b): the runner's quit (a flag
    // and the message), so a full inbox cannot drop it
    def quit(using Frame): Unit < Sync = runner.quit

    /** The fibers of the run that have not ended: the R1 gauge for tests. */
    // claude 2026-10-12: created (RC-8)
    private[tui4s] def liveFibers(using Frame): Int < Sync = runner.liveFibers

    /** True when the run has ended. */
    def finished(using Frame): Boolean < Sync = run.done

    /** Waits until the run has ended, at most `timeout` of wall-clock time.
      * True when it ended.
      */
    def awaitEnd(timeout: Duration)(using Frame): Boolean < Async =
      // OS-34: never join the run fiber (`run.get`) from an interruptible
      // wait. A join makes the caller's interrupt wait for the run, and a
      // run that waits for the caller is a deadlock. This polls `done`.
      poll(timeout)(run.done)

    /** Waits until `predicate` holds for the screen, at most `timeout` of
      * wall-clock time; reads the screen every 5 ms. True when it held before
      * the timeout.
      */
    def awaitScreen(predicate: Screen => Boolean, timeout: Duration)(using
        Frame,
    ): Boolean < Async =
      poll(timeout)(screen.map(predicate))

    /** Polls `cond` every 5 ms on the live clock until it is true or `timeout`
      * of wall-clock time has passed.
      */
    private def poll(timeout: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Boolean < Async =
      Sync.defer(java.lang.System.nanoTime()).map { t0 =>
        Loop.foreach {
          cond.map { ok =>
            if ok then Loop.done(true)
            else
                Sync.defer(java.lang.System.nanoTime()).map { now =>
                  if now - t0 > timeout.toNanos then Loop.done(false)
                  else
                      Clock
                        .let(Clock.live)(Async.sleep(5.millis))
                        .andThen(Loop.continue)
                }
          }
        }
      }

    /** The screen as it is now: rows, cell styles and the cursor. */
    def screen(using Frame): Screen < Sync = Sync.defer(Screen.of(backend))

    /** The rows of the screen as it is now, with their trailing spaces (one
      * string per row, one place per cell).
      */
    def lines(using Frame): Vector[String] < Sync = Sync.defer {
      val buf = backend.buffer()
      Vector.tabulate(buf.area.height)(y =>
        (0 until buf.area.width).map(x => buf.cellAt(x, y).symbol()).mkString,
      )
    }

    /** Resizes the screen and waits for the frame of the resize. Background
      * frames that come first are consumed. The runner fiber resizes the
      * backend when it handles the message (one fiber touches the backend).
      */
    // claude 2026-10-12: changed (review R2-4): only the message; the runner
    // resizes the backend
    def resize(w: Int, h: Int)(using Frame): FrameStats < Async =
      seq.getAndIncrement
        .map { n =>
          inbox
            .put(Msg.Resize(Size(w, h), n))
            .andThen {
              Loop.foreach {
                frames.take.map(s =>
                  if s.cause == FrameCause.Input && s.seq == n then Loop.done(s)
                  else Loop.continue,
                )
              }
            }
        }
        .handle(Abort.run[Closed])
        .map(_.getOrThrow)

    /** The next frame of any cause (waits for it). */
    def nextFrame(using Frame): FrameStats < Async =
      Abort.run[Closed](frames.take).map(_.getOrThrow)

    /** The frames that arrived and were not consumed. */
    def drainFrames(using Frame): Vector[FrameStats] < Sync =
      Abort.run[Closed](frames.drain).map(_.getOrElse(Chunk.empty).toVector)
end Headless

object Headless:
    /** Starts a headless run of `ui` on a `width` x `height` screen and returns
      * after its first frame. `ui` gets the quit value: running it ends the run
      * (D-15). The run ends with the enclosing `Scope` (quit, then a wait for
      * the run fiber).
      */
    def start(width: Int, height: Int, options: TuiOptions = TuiOptions())(
        ui: (Unit < Sync) => UI < (Async & Scope),
    )(using Frame): Headless < (Async & Scope) =
      for
          knobs   <- Knobs.get
          inbox   <- Channel.initUnscoped[Msg](4096)
          frames  <- Channel.initUnscoped[FrameStats](1 << 16)
          backend <- Sync.defer(TestBackend(width, height))
          canvas  <- Sync.defer(Canvas(backend))
          runner   = Runner(inbox, canvas, options, Present(frames), knobs)
          seq     <- AtomicLong.init(1L)
          run     <- Fiber.init(ui(runner.quit).map(runner.run))
          h        = Headless(inbox, frames, backend, seq, run, runner)
          // it quits and waits before its scope ends (RC-7)
          _       <- Scope.ensure(h.quit.andThen(h.awaitEnd(5.seconds)))
          _       <- firstFrame(frames, run)
      yield h

    /** Waits for the first frame. When the run ends before it (the UI or the
      * initial mount failed), the run's result surfaces here instead of a hang
      * until the test timeout. A join of a DONE fiber is not a wait (OS-34).
      */
    // claude 2026-10-12: created (review R2-8 a)
    private def firstFrame(
        frames: Channel[FrameStats],
        run: Fiber[Unit, Scope],
    )(using Frame): Unit < (Async & Scope) =
      Loop.foreach {
        Abort.run[Closed](frames.poll).map {
          case Result.Success(Present(_)) => Loop.done
          case _                          =>
            run.done.map { ended =>
              if ended then run.get.andThen(Loop.done)
              else
                  Clock
                    .let(Clock.live)(Async.sleep(5.millis))
                    .andThen(Loop.continue)
            }
        }
      }
end Headless
