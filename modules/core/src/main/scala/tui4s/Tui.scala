package tui4s

import kyo.*
import tui4s.internal.{
  Crossterm,
  Hooks,
  LogSink,
  Msg,
  NativeCrossterm,
  OutputCapture,
  Runner,
  Session,
  TerminalIO,
  Tui4sQuit,
  TuiLog,
}

import java.io.PrintStream

/** The entry points of a tui4s app: [[run]] runs a kyo-ui UI in the real
  * terminal, [[quit]] ends it from inside.
  */
// claude 2026-10-13: created (C3, RC-2 part: the prototype's Tui.quit)
// claude 2026-10-13: changed (T1, RC-2): Tui.run
object Tui:

    /** Runs `ui` in the real terminal with the default [[TuiOptions]] until it
      * quits. See the other `run`.
      */
    // claude 2026-10-13: created (T1, RC-2)
    def run(ui: (Unit < Sync) => UI < (Async & Scope))(using
        Frame,
    ): Unit < (Async & Abort[TuiError]) =
      run(TuiOptions())(ui)

    /** Runs `ui` in the real terminal until it quits, on the calling fiber.
      *
      * `ui` gets the quit value: running it ends the run (the same as [[quit]],
      * which works inside the construction of the UI, inside a handler, and
      * inside a fiber that they start). The key Ctrl-C ends the run too
      * (`TuiOptions.ctrlCQuits`). The run ends after the frame of the event
      * that quit it, and the computation completes with `()`.
      *
      * The run puts the terminal in raw mode, enters the alternate screen,
      * hides the cursor and turns on the bracketed paste mode (with
      * `TuiOptions.paste`). The terminal is restored before this computation
      * completes, also when the UI fails and when the fiber is interrupted, and
      * a JVM shutdown hook restores it when the JVM exits during the run. While
      * the UI is up, the Kyo log goes to `TuiOptions.logFile` or to memory, and
      * (with `TuiOptions.captureOutput`) so do `System.out` and `System.err`;
      * what was kept in memory is printed to stderr after the restore.
      *
      * The JVM needs `--enable-native-access=ALL-UNNAMED` (the terminal library
      * crossterm is native) and a terminal on stdin. Give it the tui4s
      * scheduler values too: `-Dkyo.scheduler.cycleIntervalNs=10000000
      * -Dkyo.scheduler.blockingMonitorIntervalNs=20000000
      * -Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100` (fewer split
      * frames under load, less CPU when idle).
      *
      * After a quit, the run first closes the `Scope` of the UI: it interrupts
      * the fibers that the UI started with `Fiber.init` and waits for them to
      * end. Then it restores the terminal. A fiber that does not stop on the
      * interrupt (for example a blocking call in `Sync.defer`) keeps the
      * terminal in raw mode until it ends. When the run itself is interrupted,
      * the terminal is restored at once.
      *
      * Fails with `TuiError.NotATerminal` when stdin is not a terminal, with
      * `TuiError.NativeAccessDenied` when the JVM does not allow the native
      * library, with `TuiError.NativeLoadFailed` when the native library cannot
      * be loaded for another reason (these three before the terminal changes),
      * and with `TuiError.TerminalIO` when the terminal cannot be set up, read
      * or written. A failure of the UI itself comes out as it is, after the
      * restore.
      */
    // claude 2026-10-13: created (T1, RC-2): the shape of Headless.start (the
    // quit value, D-15; Tui.quit as the second way)
    // claude 2026-10-13: changed (T1, reviews R1-2, R2-2, R2-3): the Scaladoc
    // names NativeLoadFailed and the order of the scope close and the restore
    def run(options: TuiOptions)(ui: (Unit < Sync) => UI < (Async & Scope))(
        using Frame,
    ): Unit < (Async & Abort[TuiError]) =
      runWith(options, NativeCrossterm())(ui)

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
      Sync.Unsafe.defer(LogSink(options.logFile)).map { sink => // scalafix:ok DisableSyntax.unsafe; P6: LogSink.open: LogSink < Sync
        // The release finishes the sink; this also covers a run that failed
        // before it had a terminal.
        Sync.ensure(Sync.Unsafe.defer(sink.finish(err())))(     // scalafix:ok DisableSyntax.unsafe; P3: the release stack owns sink.finish (A1)
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
            // claude 2026-10-13: changed (T1): a TuiError that the canvas threw
            // (a write to the terminal failed) is the failure of the run
            .map { r =>
              TuiLog
                .flush(2.seconds)
                .andThen(Abort.get(r match
                    case Result.Panic(e: TuiError) => Result.fail(e)
                    case other                     => other))
            },
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
          opened => Sync.Unsafe.defer(opened.foreach(_.close())), // scalafix:ok DisableSyntax.unsafe; P3: the release stack (A1)
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
        if f == null then Kyo.unit else Abort.fail(TuiError.TerminalIO(f)) // scalafix:ok DisableSyntax.null; P1: inputFailure gives a Maybe
      }
end Tui
