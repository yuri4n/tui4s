package tui4s.interop.zio.internal

import _root_.kyo.*
import _root_.tui4s.App
import _root_.tui4s.TuiError
import _root_.tui4s.TuiHandle
import _root_.tui4s.internal.LogSink
import _root_.tui4s.internal.NativeCrossterm
import _root_.tui4s.internal.Runner
import _root_.tui4s.internal.TerminalControl
import _root_.tui4s.internal.TerminalIO
import _root_.tui4s.internal.TuiLog
import _root_.tui4s.spi.InputPort

/** A running app as the facade's handle sees it: the core handle, and the
  * result of the run in a promise that no waiter can interrupt.
  *
  * In Kyo, a fiber that waits on another fiber and is interrupted interrupts
  * what it waits on. So a ZIO `await` that times out would stop the app. The
  * facade waits on `done` instead: one watcher fiber per handle waits on the
  * run and completes it.
  */
private[tui4s] final class ZioRun[S](
    val core: TuiHandle[S],
    val done: Promise.Unsafe[Result[TuiError, S], Any],
)

/** The Kyo side of the facade's runner and handle. */
private[tui4s] object ZioRunner:
    /** `app` in the real terminal on its own fiber, without a Kyo scope: the
      * facade releases it with its own bracket (never a Kyo `Scope`, F8). The
      * handle comes after the first frame; a run that ends before (no terminal,
      * `init` threw) fails with its [[TuiError]]. As `Tui.start` of core: one
      * log sink for the run's Kyo log and captured output.
      */
    def startReal[S](app: App[S])(using
        Frame,
    ): ZioRun[S] < (Async & Abort[TuiError]) =
      Sync.Unsafe.defer(LogSink(app.options.logFile)).map { sink =>
        TuiLog
          .let(sink)(
            Runner.startOrFail(app, TerminalIO(NativeCrossterm(), sink), None),
          )
          .map(watch)
      }

    /** [[startReal]] on any terminal control and input (the test seam). */
    def startOn[S](app: App[S], control: TerminalControl, input: InputPort)(
        using Frame,
    ): ZioRun[S] < (Async & Abort[TuiError]) =
      Runner.startOrFail(app, control, Some(input)).map(watch)

    /** Starts the watcher of `core`. */
    def watch[S](core: TuiHandle[S])(using Frame): ZioRun[S] < Sync =
      Sync.Unsafe
        .defer(Promise.Unsafe.initUninterruptible[Result[TuiError, S], Any]())
        .map { done =>
          Fiber
            .initUnscoped(
              core.result.map(r =>
                Sync.Unsafe.defer(done.completeDiscard(Result.succeed(r))),
              ),
            )
            .andThen(ZioRun(core, done))
        }

    /** The result of the run. Interrupting the wait does not stop the app. */
    def await[S](run: ZioRun[S])(using Frame): S < (Async & Abort[TuiError]) =
      run.done.safe.get.map(r => Abort.get(r))

    /** The next state of the run that differs from `last` (`None`: none seen
      * yet), and whether the run has ended with it. `Absent` when the run ended
      * with `last`.
      *
      * The states signal is a `SignalRef`: a changing write sets the value,
      * then completes the promise of the next change. So: check the end first,
      * then register for the next change, then read the value. A write after
      * the registration completes the promise; a write before it is read. The
      * run sets its final state before its fiber ends, so a run that has ended
      * at the first check shows its final state at the read. Both waits are on
      * promises that no waiter can interrupt.
      */
    def nextState[S](run: ZioRun[S], last: Option[S])(using
        Frame,
    ): Maybe[(S, Boolean)] < Async =
        def fresh(now: S): Boolean = !last.exists(_.asInstanceOf[Any] == now)
        run.core.states match
            case ref: Signal.SignalRef[S] @unchecked =>
              Loop.foreach {
                Sync.Unsafe
                  .defer {
                    val ended = run.done.poll().nonEmpty
                    (ended, ref.unsafe.next(), ref.unsafe.get())
                  }
                  .map { (ended, next, now) =>
                    if ended then
                        Loop.done(
                          if fresh(now) then Present((now, true)) else Absent,
                        )
                    else if fresh(now) then Loop.done(Present((now, false)))
                    else
                        // Wake at the next change or at the end of the run.
                        Async
                          .race(next.safe.get.unit, run.done.safe.get.unit)
                          .andThen(Loop.continue)
                  }
              }
            case other                               =>
              // Not reached: the runner publishes a SignalRef.
              other.current.map(now =>
                Present((now, false)).filter((s, _) => fresh(s)),
              )
        end match
    end nextState
end ZioRunner
