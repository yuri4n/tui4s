package tui4s

import java.io.PrintStream
import kyo.*
import tui4s.internal.Crossterm
import tui4s.internal.Hooks
import tui4s.internal.LogSink
import tui4s.internal.Msg
import tui4s.internal.OutputCapture
import tui4s.internal.Runner
import tui4s.internal.Session
import tui4s.internal.TerminalIO
import tui4s.internal.Tui4sQuit
import tui4s.internal.TuiLog

/** The entry points of a tui4s app. In this version it has `quit`; `run` (the
  * real terminal) comes in a later version.
  */
// claude 2026-10-13: created (C3, RC-2 part: the prototype's Tui.quit)
object Tui:

    /** Ends the run that runs the caller. A key handler, the construction of
      * the UI, and a fiber that one of them started can call it: the runner
      * sets the quit of the run around each handler and around the UI
      * construction, and a forked fiber keeps it. Outside a run it does
      * nothing. The run ends after the frame of the current event.
      */
    def quit(using Frame): Unit < Sync = Tui4sQuit()

    /** The run seam of the tests: `ui` on the terminal of `crossterm` (the real
      * one or a fake), with the shutdown hooks `hooks` and the stderr `err` for
      * what is printed after the restore; `restored` runs when the release has
      * restored the terminal. A run has one [[internal.LogSink]] for its Kyo
      * log ([[internal.TuiLog]], bound with `Log.let`) and its captured output.
      */
    // claude 2026-10-13: created (T1, RC-2 part): PR 5 TuiRun.run and the
    // session bracket of its Runner.runOn, on the kyo-ui runner
    private[tui4s] def runWith(
        options: TuiOptions,
        crossterm: Crossterm,
        hooks: Hooks = Hooks.runtime,
        err: () => PrintStream = () => OutputCapture.realErr,
        restored: () => Unit = () => (),
    )(ui: (Unit < Sync) => UI < (Async & Scope))(using
        Frame,
    ): Unit < (Async & Abort[TuiError]) =
      Sync.Unsafe.defer(LogSink(options.logFile)).map { sink =>
        // The release finishes the sink; this also covers a run that failed
        // before it had a terminal.
        Sync.ensure(Sync.Unsafe.defer(sink.finish(err())))(
          Abort
            .run[TuiError](
              TuiLog.let(sink)(
                onTerminal(
                  options,
                  TerminalIO(crossterm, sink, hooks, err, restored),
                )(ui),
              ),
            )
            // The lines that Kyo has not written yet go to the log file (or
            // to stderr) before the run returns.
            .map(r => TuiLog.flush(2.seconds).andThen(Abort.get(r))),
        )
      }

    /** `ui` on the terminal of `control`: the session (terminal modes, canvas,
      * input thread) is the acquire of a bracket, and its synchronous release
      * restores the terminal when the run ends in any way (a quit, a failure,
      * an interrupt). The runner runs on the calling fiber, as `Headless.start`
      * builds it.
      */
    // claude 2026-10-13: created (T1, RC-2 part)
    private def onTerminal(options: TuiOptions, control: TerminalIO)(
        ui: (Unit < Sync) => UI < (Async & Scope),
    )(using Frame): Unit < (Async & Abort[TuiError]) =
      Channel.initUnscoped[Msg](options.inboxCapacity.max(1)).map { inbox =>
        Sync.acquireReleaseWith(Session.open(options, control, None, inbox))(
          opened => Sync.Unsafe.defer(opened.foreach(_.close())),
        ) { opened =>
          Abort.get(opened).map { session =>
            val runner = Runner(inbox, session.canvas, options, Absent)
            Scope
              .run(runner.scoped(ui(runner.quit).map(runner.run)))
              .andThen(inputFailed(session))
          }
        }
      }

    /** Fails with `TerminalIO` when the input thread failed (it then ended the
      * run).
      */
    // claude 2026-10-13: created (T1)
    private def inputFailed(session: Session)(using
        Frame,
    ): Unit < (Sync & Abort[TuiError]) =
      Sync.defer(session.inputFailure).map { f =>
        if f == null then Kyo.unit else Abort.fail(TuiError.TerminalIO(f))
      }
end Tui
