package tui4s

// `_root_`: see Source.scala (package tui4s and its exported dsl).
import _root_.kyo.*
import _root_.tui4s.internal.Fold
import _root_.tui4s.internal.HandleObserver
import _root_.tui4s.internal.Item
import _root_.tui4s.internal.NativeCrossterm
import _root_.tui4s.internal.Runner
import _root_.tui4s.internal.Stop
import _root_.tui4s.internal.TerminalControl
import _root_.tui4s.internal.TuiRun
import _root_.tui4s.spi.InputPort

/** Runs apps in the real terminal. A Kyo app with its own `main` uses these;
  * [[TuiApp]] calls [[run]] for you.
  *
  * The JVM needs `--enable-native-access=ALL-UNNAMED` (crossterm is a native
  * library) and a terminal on stdin. The frames go to stdout. It can be a pipe
  * to the terminal (the forked `run` of sbt does this), but do not redirect it
  * to a file: nothing checks stdout.
  */
object Tui:
    /** Runs `app` in the real terminal until it quits, and returns the last
      * state.
      *
      * The terminal is restored before this computation completes, also when
      * the app fails and when the fiber is interrupted. While the UI is up, the
      * Kyo log goes to `TuiOptions.logFile` or to memory, and (with
      * `TuiOptions.captureOutput`) so does `System.out` and `System.err`; what
      * was kept in memory is printed to stderr after the restore.
      *
      * Fails with `TuiError.NotATerminal` when stdin is not a terminal or the
      * process has no terminal, and with `TuiError.NativeAccessDenied` when the
      * JVM does not allow the native library. Stdout is not checked.
      */
    def run[S](app: App[S])(using Frame): S < (Async & Abort[TuiError]) =
      TuiRun.run(app, NativeCrossterm())

    /** Starts `app` in the real terminal on its own fiber and returns a handle
      * once the first frame is drawn. The end of the scope stops the app.
      *
      * On an interrupted scope the restore can finish shortly after the scope
      * ends (Kyo runs `Scope` finalizers of an interrupted fiber on another
      * fiber); `handle.stop` and `handle.await` wait for it. When the app fails
      * before its first frame, the start fails with that [[TuiError]] as a
      * panic.
      */
    def start[S](app: App[S])(using Frame): TuiHandle[S] < (Async & Scope) =
      TuiRun.start(app, NativeCrossterm())

    /** The run seam of the facades and the tests: `app` on `control` (the
      * terminal side of SPEC 5.9) with input from `input`. The terminal is
      * released before the result is visible, also on interruption.
      */
    private[tui4s] def runWith[S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    )(using Frame): S < (Async & Abort[TuiError]) =
      Runner.run(app, control, Some(input))

    /** The start seam: `app` on its own fiber; the handle comes after the first
      * frame. The caller stops it. A caller interrupted before it has the
      * handle stops the app. When the app fails before its first frame, the
      * start fails with that [[TuiError]] as a panic.
      */
    private[tui4s] def startWith[S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    )(using Frame): TuiHandle[S] < Async =
      Runner.startUnscoped(app, control, Some(input))
end Tui

/** A running app, seen from outside: send it changes and events, read its
  * states and counters, stop it, wait for its result.
  */
final class TuiHandle[S] private[tui4s] (
    private[tui4s] val inbox: Channel[Item],
    fiber: Fiber[S, Abort[TuiError]],
    observer: HandleObserver[S],
    signal: Signal.SignalRef[S],
):
    /** Folds `f` into the state, like a source would. Dropped after the end. */
    def update(f: S => Next[S])(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(Fold(Fold.Direct, f))).unit

    /** Sends a synthetic input event. Dropped after the end. */
    def send(event: Event)(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(event)).unit

    /** The state after each batch, from `init` on (equal states once), up to
      * the state the app ended with.
      */
    def states: Signal[S] = signal

    /** A snapshot of the counters, taken after the last batch. */
    def stats(using Frame): Stats < Sync = Sync.defer(observer.stats)

    /** The result of the run: the last state, or the failure. */
    def await(using Frame): S < (Async & Abort[TuiError]) = fiber.get

    /** Stops the app like `Quit`; returns when the terminal is released. */
    def stop(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(Stop)).andThen(fiber.getResult).unit

    /** The result of the run, failure included, once it ended. */
    private[tui4s] def result(using Frame): Result[TuiError, S] < Async =
      fiber.getResult

    /** True after the run ended. */
    private[tui4s] def ended(using Frame): Boolean < Sync = fiber.done

    /** Interrupts the run (signalled, not awaited): a safety net for tests. */
    private[tui4s] def kill(using Frame): Unit < Sync = fiber.interrupt.unit
end TuiHandle
