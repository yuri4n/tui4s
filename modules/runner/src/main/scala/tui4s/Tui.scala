package tui4s

import kyo.*
import tui4s.internal.{
  Crossterm,
  DiagnosticRecorder,
  Hooks,
  LogSink,
  Msg,
  NativeCrossterm,
  OutputCapture,
  ReleaseStack,
  RunOptions,
  Runner,
  Session,
  TerminalIO,
  Tui4sQuit,
  Tui4sSize,
  TuiLog,
}

import java.io.PrintStream

/** The entry points of a tui4s app: [[run]] runs a kyo-ui UI in the real
  * terminal, [[quit]] ends it from inside, [[size]] gives the size of its
  * terminal.
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

    /** Runs `ui` in the real terminal until it quits, on a fiber of its own
      * (the run fiber). The calling fiber waits for it; an interrupt of the
      * calling fiber interrupts the run.
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
      * completes, also when the UI fails and when the fiber is interrupted.
      * When the JVM exits during the run, its shutdown hook interrupts the run
      * fiber and restores the terminal (it waits at most 2 s). While the UI is
      * up, the Kyo log goes to `TuiOptions.logFile` or to memory, and (with
      * `TuiOptions.captureOutput`) so do `System.out` and `System.err`; what
      * was kept in memory is printed to stderr after the restore.
      *
      * The JVM needs `--enable-native-access=ALL-UNNAMED` (the terminal library
      * crossterm is native) and a terminal on stdin. Give it the tui4s
      * scheduler values too: `-Dkyo.scheduler.cycleIntervalNs=10000000
      * -Dkyo.scheduler.blockingMonitorIntervalNs=20000000
      * -Dkyo.scheduler.blockingMonitorMinIntervalNs=20000000
      * -Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100` (fewer split
      * frames under load, less CPU when idle, a faster start; see
      * [[tui4s.spi.SchedulerDefaults]]).
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
    // claude 2026-10-19: changed (P3, A2): the Scaladoc names the run fiber
    // and the shutdown hook
    def run(options: TuiOptions)(ui: (Unit < Sync) => UI < (Async & Scope))(
        using Frame,
    ): Unit < (Async & Abort[TuiError]) =
      runWith(options, NativeCrossterm())(ui)

    /** Runs `ui` as the other `run` does, with the kyo-ui stylesheet `sheet`:
      * its custom properties give the colours of `Style.Color.variable(name)`.
      *
      * {{{
      * val colors = Stylesheet.vars("accent" -> "#22c55e")
      * Tui.run(TuiOptions(), colors)(_ =>
      *   span("ok").style(_.color(Style.Color.variable("accent"))))
      * }}}
      *
      * kyo-ui declares a custom property only in a `Stylesheet`
      * (`Stylesheet.vars`, `Stylesheet.scopedVars`), and a page gets it through
      * its head (`UI.PageHead.css`) or `UI.runStylesheet`, not through the
      * element tree. A terminal has no head, so the run takes the stylesheet.
      * The runner reads its `vars` blocks and its `scopedVars` blocks with a
      * class, an id or a data selector; it ignores the other entries, and it
      * logs one warning for each of them.
      */
    // claude 2026-10-19: created (L1b)
    def run(options: TuiOptions, sheet: Stylesheet)(
        ui: (Unit < Sync) => UI < (Async & Scope),
    )(using Frame): Unit < (Async & Abort[TuiError]) =
      runWith(options, NativeCrossterm(), stylesheet = sheet)(ui)

    /** Ends the run that runs the caller. A key handler, the construction of
      * the UI, and a fiber that one of them started can call it: the runner
      * sets the quit of the run around each handler and around the UI
      * construction, and a forked fiber keeps it. Outside a run it does
      * nothing. The run ends after the frame of the current event.
      */
    def quit(using Frame): Unit < Sync = Tui4sQuit()

    /** The size of the terminal of the run that runs the caller, in cells, as a
      * signal. A resize writes it in the step of the resize, so the regions
      * that derive from it are in the frame of the resize, with the new layout.
      * The construction of the UI, a component (hook or class), a handler and a
      * fiber that one of them started can read it. Outside a run it is a signal
      * that stays at 0 x 0.
      *
      * kyo-ui has no signal of the size on the JVM: its `UIWindow.size` is in
      * the Scala.js jar only, and only the DOM writes it. So tui4s adds this
      * one, which `Tui.run` and `Headless.start` feed.
      *
      * {{{
      * def body(sidebar: UI, main: UI): UI < Sync =
      *   Tui.size.map(size =>
      *     div(sidebar, main).style(size.map(s =>
      *       if s.width >= 100 then Style.row else Style.column)))
      * }}}
      */
    // claude 2026-10-19: created (L1b)
    def size(using Frame): Signal[TerminalSize] < Sync = Tui4sSize.get

    /** The run seam of the tests: `ui` on the terminal of `crossterm` (the real
      * one or a fake), with the shutdown hooks `hooks` and the stderr `err` for
      * what is printed after the restore; `trace` gets the name of each release
      * step before it runs. A run has one [[internal.ReleaseStack]] with one
      * shutdown hook ([[internal.ReleaseStack.run]]: the hook is its first
      * step) and one [[internal.LogSink]] for its Kyo log ([[internal.TuiLog]],
      * bound with `Log.let`) and its captured output.
      */
    // claude 2026-10-19: changed (P3, A1, A2): the release stack of the run
    // and its one hook; the sink pushes its finish to the stack; the
    // stylesheet of the run (L1b)
    private[tui4s] def runWith(
        options: TuiOptions,
        crossterm: Crossterm,
        hooks: Hooks = Hooks.runtime,
        err: () => PrintStream = () => OutputCapture.realErr,
        trace: String => Unit = _ => (),
        stylesheet: Stylesheet = Stylesheet.empty,
    )(ui: (Unit < Sync) => UI < (Async & Scope))(using
        Frame,
    ): Unit < (Async & Abort[TuiError]) =
      ReleaseStack.run(hooks, ReleaseStack.printFailure(err), trace) { stack =>
        // The stack finishes the sink; this also covers a run that failed
        // before it had a terminal.
        // claude 2026-10-19: changed (P6): the open of the sink is an effect
        // that pushes its finish
        LogSink
          .open(options.logFile, stack, err)
          .map { sink =>
            Abort
              .run[TuiError](
                TuiLog.let(sink)(
                  onTerminal(
                    RunOptions.of(options, stylesheet),
                    TerminalIO(crossterm, sink, err),
                    stack,
                  )(ui),
                ),
              )
              // The lines that Kyo has not written yet go to the log file (or
              // to stderr) before the run returns. A TuiError that the canvas
              // threw (a write to the terminal failed) is the failure of the
              // run.
              .map { r =>
                TuiLog
                  .flush(2.seconds)
                  .andThen(Abort.get(r match
                      case Result.Panic(e: TuiError) => Result.fail(e)
                      case other                     => other))
              }
          }
      }

    /** `ui` on the terminal of `control`: the session (terminal modes, canvas,
      * input thread) is the acquire of a bracket, and the release runs the
      * stack of the run: it restores the terminal when the run ends in any way
      * (a quit, a failure, an interrupt). The runner runs on the run fiber, as
      * `Headless.start` builds it.
      */
    // claude 2026-10-19: changed (P3, A1): the release is the stack; codex 2026-10-22: changed: share optional diagnostics across input and runner
    private def onTerminal(
        options: RunOptions,
        control: TerminalIO,
        stack: ReleaseStack,
    )(
        ui: (Unit < Sync) => UI < (Async & Scope),
    )(using Frame): Unit < (Async & Abort[TuiError]) =
      DiagnosticRecorder.open(options, stack).map { diagnostics =>
        Channel.initUnscoped[Msg](options.inboxCapacity).map { inbox =>
          Sync.acquireReleaseWith(
            Session
              .openDiagnosed(options, control, None, inbox, stack, diagnostics),
          )(_ => Sync.defer(stack.runAll())) { opened =>
            Abort.get(opened).map { session =>
              Runner
                .initDiagnosed(
                  inbox,
                  session.canvas,
                  options,
                  Absent,
                  diagnostics,
                )
                .map { runner =>
                  Scope
                    .run(runner.scoped(ui(runner.quit).map(runner.run)))
                    .andThen(inputFailed(session))
                }
            }
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
      // claude 2026-10-19: changed: inputFailure gives a Maybe (P1)
      Sync.defer(session.inputFailure).map {
        case Present(f) => Abort.fail(TuiError.TerminalIO(f))
        case Absent     => Kyo.unit
      }
end Tui
