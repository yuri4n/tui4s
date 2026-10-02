package tui4s.interop.zio

import _root_.kyo.Present
import _root_.tui4s.internal.TerminalControl
import _root_.tui4s.interop.zio.internal.AmbientZIOs
import _root_.tui4s.interop.zio.internal.ZioRun
import _root_.tui4s.interop.zio.internal.ZioRunner
import _root_.tui4s.spi.InputPort
import _root_.tui4s.spi.SchedulerDefaults
import _root_.zio.IO
import _root_.zio.Runtime
import _root_.zio.Scope
import _root_.zio.Trace
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.stream.ZStream

/** Runs apps in the real terminal, as ZIO effects. [[TuiApp]] calls [[run]] for
  * you; a program with its own `ZIOAppDefault` calls it itself.
  *
  * The JVM needs `--enable-native-access=ALL-UNNAMED` (crossterm is a native
  * library) and a terminal on stdin. The frames go to stdout. It can be a pipe
  * to the terminal (the forked `run` of sbt does this), but do not redirect it
  * to a file.
  *
  * While the UI is up, nothing else may write to the terminal. So the run
  * removes ZIO's default console loggers from the fiber that runs it (and from
  * the sources it starts) until the terminal is restored, and, with
  * `TuiOptions.captureOutput` (the default), `System.out` and `System.err` go
  * to `TuiOptions.logFile` or to memory, printed after the restore. A logger
  * that you add yourself stays: make it write to a file.
  *
  * The app's sources run on the caller's runtime (FiberRefs, executor, clock).
  * tui4s itself runs on the Kyo scheduler, a second runtime next to ZIO's; the
  * run sets the Kyo scheduler settings of `tui4s.spi.SchedulerDefaults` that
  * the user did not set, if Kyo has not started yet (less idle CPU).
  */
object Tui:
    /** Runs `app` in the real terminal until it quits, and returns the last
      * state.
      *
      * The terminal is restored before this effect completes, also when the app
      * fails and when the fiber is interrupted: an interrupt returns only after
      * the restore. Fails with `TuiError.NotATerminal` when stdin is not a
      * terminal, `TuiError.NativeAccessDenied` when the JVM does not allow the
      * native library, `TuiError.AppFailed` when `update`, `view`, `sources` or
      * a fold throws, and `TuiError.SourceFailed` when a source fails and has
      * no `recover`.
      */
    def run[S](app: App[S])(using Trace): IO[TuiError, S] =
      ZIO.suspendSucceed {
        SchedulerDefaults.applyIfUnset()
        quietly(AmbientZIOs.run(_root_.tui4s.Tui.run(app)))
      }

    /** Starts `app` in the real terminal on its own fiber and returns a handle
      * once the first frame is drawn. The end of the scope stops the app and
      * waits until the terminal is restored. ZIO's default loggers are removed
      * from the calling fiber until then.
      *
      * Fails as [[run]] when the app cannot start: no terminal, or `init`,
      * `sources` or the first `view` threw.
      */
    def start[S](app: App[S])(using Trace): ZIO[Scope, TuiError, TuiHandle[S]] =
      ZIO.suspendSucceed {
        SchedulerDefaults.applyIfUnset()
        removeDefaultLoggers *>
          acquire(AmbientZIOs.run(ZioRunner.startReal(app)))
      }

    /** The run seam of the tests: `app` on `control` (the terminal side of SPEC
      * 5.9) with input from `input`, on the caller's runtime.
      */
    private[tui4s] def runWith[S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    )(using Trace): IO[TuiError, S] =
      quietly(AmbientZIOs.run(_root_.tui4s.Tui.runWith(app, control, input)))

    /** The start seam of the tests: [[start]] on `control` and `input`. */
    private[tui4s] def startWith[S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    )(using Trace): ZIO[Scope, TuiError, TuiHandle[S]] =
      removeDefaultLoggers *>
        acquire(AmbientZIOs.run(ZioRunner.startOn(app, control, input)))

    /** The started app as a ZIO resource: the release stops it and waits for
      * the restore (ZIO's own bracket, never a Kyo `Scope`: F8).
      */
    private def acquire[S](start: ZIO[Any, TuiError, ZioRun[S]])(using
        Trace,
    ): ZIO[Scope, TuiError, TuiHandle[S]] =
      ZIO
        .acquireRelease(start)(run => AmbientZIOs.run(run.core.stop))
        .map(run => new TuiHandle(run))

    /** `z` without ZIO's default loggers: they print to stdout, over the UI. */
    private def quietly[E, A](z: ZIO[Any, E, A])(using Trace): ZIO[Any, E, A] =
      ZIO.scoped(removeDefaultLoggers *> z)

    private def removeDefaultLoggers(using Trace): ZIO[Scope, Nothing, Unit] =
      Runtime.removeDefaultLoggers.build.unit
end Tui

/** A running app, seen from a ZIO program: send it changes and events, read its
  * states and counters, stop it, wait for its result. From [[Tui.start]].
  */
final class TuiHandle[S] private[zio] (run: ZioRun[S]):
    /** Folds `f` into the state, as a source would. Waits while the app's inbox
      * is full. Dropped after the end of the run.
      */
    def update(f: S => Next[S])(using Trace): UIO[Unit] =
      AmbientZIOs.run(run.core.update(f))

    /** Sends a synthetic input event (as if typed). Dropped after the end. */
    def send(event: Event)(using Trace): UIO[Unit] =
      AmbientZIOs.run(run.core.send(event))

    /** The current state, then each new state after a batch of events and
      * values (equal states once), up to the state the app ended with; then the
      * stream ends. A slow consumer skips states: it gets the newest one.
      */
    def states(using Trace): ZStream[Any, Nothing, S] =
      ZStream.unfoldZIO(Option.empty[S] -> false) { (last, ended) =>
        if ended then ZIO.none
        else
            AmbientZIOs
              .run(ZioRunner.nextState(run, last))
              .map {
                case Present((s, end)) => Some(s -> (Some(s) -> end))
                case _                 => None
              }
      }

    /** A snapshot of the counters, taken after the last batch. */
    def stats(using Trace): UIO[Stats] = AmbientZIOs.run(run.core.stats)

    /** The result of the run: the last state, or the failure. Interrupting this
      * wait does not stop the app; [[stop]] does.
      */
    def await(using Trace): IO[TuiError, S] =
      AmbientZIOs.run(ZioRunner.await(run))

    /** Stops the app like `Quit`; returns when the terminal is restored. */
    def stop(using Trace): UIO[Unit] = AmbientZIOs.run(run.core.stop)
end TuiHandle
