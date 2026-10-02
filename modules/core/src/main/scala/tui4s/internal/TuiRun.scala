package tui4s.internal

import java.io.PrintStream
import kyo.*
import tui4s.*

/** `Tui.run` and `Tui.start` on any [[Crossterm]]: the real one, or a fake in
  * the tests. A run has one [[LogSink]] for its Kyo log ([[TuiLog]], bound with
  * `Log.let`) and its captured output.
  */
private[tui4s] object TuiRun:
    /** Runs `app` on the terminal of `crossterm` until it quits. `restored` is
      * called when the release has restored the terminal.
      */
    def run[S](
        app: App[S],
        crossterm: Crossterm,
        hooks: Hooks = Hooks.runtime,
        err: () => PrintStream = () => OutputCapture.realErr,
        restored: () => Unit = () => (),
    )(using Frame): S < (Async & Abort[TuiError]) =
      Sync.Unsafe.defer(LogSink(app.options.logFile)).map { sink =>
        // The release finishes the sink; this also covers a run that failed
        // before it had a terminal.
        Sync.ensure(Sync.Unsafe.defer(sink.finish(err())))(
          Abort
            .run[TuiError](
              TuiLog.let(sink)(
                Runner.run(
                  app,
                  TerminalIO(crossterm, sink, hooks, err, restored),
                  None,
                ),
              ),
            )
            // The lines that Kyo has not written yet go to the log file (or
            // to stderr) before the run returns.
            .map(r => TuiLog.flush(2.seconds).andThen(Abort.get(r))),
        )
      }

    /** Starts `app` on the terminal of `crossterm`; the end of the scope stops
      * it.
      */
    def start[S](
        app: App[S],
        crossterm: Crossterm,
        hooks: Hooks = Hooks.runtime,
        err: () => PrintStream = () => OutputCapture.realErr,
    )(using Frame): TuiHandle[S] < (Async & Scope) =
      Sync.Unsafe.defer(LogSink(app.options.logFile)).map { sink =>
        TuiLog.let(sink)(
          Runner.start(app, TerminalIO(crossterm, sink, hooks, err), None),
        )
      }
end TuiRun
