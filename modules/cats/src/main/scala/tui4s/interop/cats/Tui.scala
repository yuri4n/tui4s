package tui4s.interop.cats

import _root_.cats.effect.Resource
import _root_.cats.effect.kernel.Async
import _root_.cats.effect.std.Dispatcher
import _root_.fs2.Pull
import _root_.kyo.<
import _root_.tui4s.internal.Hooks
import _root_.tui4s.internal.LogSink
import _root_.tui4s.internal.NativeCrossterm
import _root_.tui4s.internal.OutputCapture
import _root_.tui4s.internal.Runner
import _root_.tui4s.internal.TerminalControl
import _root_.tui4s.internal.TerminalIO
import _root_.tui4s.internal.TuiLog
import _root_.tui4s.internal.TuiRun
import _root_.tui4s.interop.cats.internal.AmbientDispatcher
import _root_.tui4s.interop.cats.internal.CatsBridge
import _root_.tui4s.spi.InputPort
import _root_.tui4s.spi.SchedulerDefaults

/** Runs apps in the real terminal, in any `F` with `Async[F]`. A [[TuiApp]]
  * calls [[run]] for you.
  *
  * The JVM needs `--enable-native-access=ALL-UNNAMED` (crossterm is a native
  * library) and a terminal on stdin.
  *
  * While the UI is up, `System.out` and `System.err` go to memory (or to
  * `TuiOptions.logFile`) and are printed after the restore
  * (`TuiOptions.captureOutput`); a logger that writes to the console would
  * otherwise draw over the screen. In your own `IOApp`, also turn off the CPU
  * starvation checker, as [[TuiApp]] does: its warnings are console output.
  */
object Tui:
    /** Runs `app` in the real terminal until it quits, and returns the last
      * state. Fails with a [[TuiError]]: `NotATerminal` when stdin is not a
      * terminal, `AppFailed` when user code threw, `SourceFailed` when a source
      * failed with no `recover`.
      *
      * The terminal is restored before the result is visible, also on failure.
      * A cancel restores the terminal before the cancel completes
      * (cancel-and-await). The `F` effects of the app's sources run on the
      * runtime that runs this `F`.
      */
    def run[F[_], S](app: App[S])(using F: Async[F]): F[S] =
      F.productR(F.delay(SchedulerDefaults.applyIfUnset()))(
        F.defer(Tui.runKyo[F, S](TuiRun.run(app, NativeCrossterm()))),
      )

    /** The app on its own fiber in the real terminal, as a resource. The handle
      * comes after the first frame. The release stops the app and returns after
      * the terminal is restored, also when the use was cancelled. When the app
      * fails before its first frame, the acquire fails with that [[TuiError]].
      */
    def resource[F[_], S](app: App[S])(using
        F: Async[F],
    ): Resource[F, TuiHandle[F, S]] =
      Resource
        .eval(F.delay(SchedulerDefaults.applyIfUnset()))
        .flatMap(_ =>
          Tui.resourceKyo[F, S](
            kyo.Sync.Unsafe
              .defer(LogSink(app.options.logFile))
              .map(sink =>
                TuiLog.let(sink)(
                  Runner.startUnscoped(
                    app,
                    TerminalIO(
                      NativeCrossterm(),
                      sink,
                      Hooks.runtime,
                      () => OutputCapture.realErr,
                    ),
                    None,
                  ),
                ),
              ),
          ),
        )

    /** The run seam of the tests (SPEC 6.3): `app` on `control` (a test
      * terminal) with input from `input`.
      */
    private[tui4s] def runWith[F[_]: Async, S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    ): F[S] =
      runKyo[F, S](Runner.run(app, control, Some(input)))

    /** The resource seam of the tests: [[resource]] on `control` and `input`. */
    private[tui4s] def resourceWith[F[_]: Async, S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    ): Resource[F, TuiHandle[F, S]] =
      resourceKyo[F, S](Runner.startUnscoped(app, control, Some(input)))

    /** Runs a Kyo run in `F` with a Dispatcher of the caller's runtime as the
      * ambient Dispatcher (`await = false`: see CatsBridge).
      */
    private[tui4s] def runKyo[F[_]: Async, S](
        run: S < (kyo.Async & kyo.Abort[TuiError]),
    ): F[S] =
      CatsBridge.runWithDispatcher[F, S](
        AmbientDispatcher.let(summon[Dispatcher[F]])(run),
      )

    /** A started run as a resource: the release stops the run and waits for its
      * end (CE's own bracket, never a Kyo `Scope`: F8). The Dispatcher outlives
      * the run.
      */
    private[tui4s] def resourceKyo[F[_], S](
        start: tui4s.TuiHandle[S] < kyo.Async,
    )(using F: Async[F]): Resource[F, TuiHandle[F, S]] =
      Dispatcher.parallel[F](await = false).flatMap { d =>
        val started = AmbientDispatcher
          .let(d)(start)
          .map(h =>
            kyo.Fiber.initUnscoped(h.result).map(end => TuiHandle[F, S](h, end)),
          )
        Resource.make(CatsBridge.runF(started))(_.stop)
      }
end Tui

/** A running app, seen from outside (from [[Tui.resource]]): send it changes
  * and events, read its states and counters, stop it, wait for its result.
  *
  * Cancelling `await` or a `states` stream only stops the wait: the app runs
  * on. Cancelling `stop` stops the app as well.
  *
  * @param end
  *   a fiber that waits for the end of the run; nothing interrupts it (an
  *   interrupt of a fiber that joins the run would reach the run)
  */
final class TuiHandle[F[_], S] private[cats] (
    core: tui4s.TuiHandle[S],
    end: kyo.Fiber[kyo.Result[TuiError, S], Any],
)(using F: Async[F]):
    /** Folds `f` into the state, like a source would. Dropped after the end. */
    def update(f: S => Next[S]): F[Unit] = CatsBridge.runF(core.update(f))

    /** Sends a synthetic input event. Dropped after the end. */
    def send(event: Event): F[Unit] = CatsBridge.runF(core.send(event))

    /** The state after each batch: first the current state, then each change
      * (equal states once). Ends after the app ended, with the state it ended
      * with. A slow consumer skips states; the latest one always arrives.
      */
    def states: _root_.fs2.Stream[F, S] =
        val ref = core.states.asInstanceOf[kyo.Signal.SignalRef[S]]

        // Register for the next change FIRST, then read the current value:
        // a change after the registration completes it, a change before it is
        // read (no state is lost).
        def look: F[(kyo.Fiber.Unsafe[S, Any], S)] =
          F.delay {
            import kyo.AllowUnsafe.embrace.danger
            (ref.unsafe.next(), ref.unsafe.get())
          }

        def changed(next: kyo.Fiber.Unsafe[S, Any]): F[Unit] =
          F.async[Unit] { cb =>
            F.delay {
              import kyo.AllowUnsafe.embrace.danger
              next.onComplete(_ => cb(Right(())))
              Some(F.unit)
            }
          }

        def same(last: Option[S], now: S): Boolean =
          last.exists(_.asInstanceOf[Any] == now)

        def loop(last: Option[S], over: F[Unit]): Pull[F, S, Unit] =
          Pull.eval(look).flatMap { (next, now) =>
            val out = if same(last, now) then Pull.done else Pull.output1(now)
            out >> Pull.eval(F.race(changed(next), over)).flatMap {
              case Left(_)  => loop(Some(now), over)
              case Right(_) =>
                // The app ended: its last state is in the signal.
                Pull
                  .eval(F.delay {
                    import kyo.AllowUnsafe.embrace.danger
                    ref.unsafe.get()
                  })
                  .flatMap(fin =>
                    if same(Some(now), fin) then Pull.done
                    else Pull.output1(fin),
                  )
            }
          }

        // One wait for the end per stream, not one per state.
        _root_.fs2.Stream.eval(F.deferred[Unit]).flatMap { over =>
          _root_.fs2.Stream
            .resource(F.background(F.flatMap(ended)(_ => over.complete(()))))
            .flatMap(_ => loop(None, over.get).stream)
        }
    end states

    /** A snapshot of the counters, taken after the last batch. */
    def stats: F[Stats] = CatsBridge.runF(core.stats)

    /** The result of the run: the last state, or the [[TuiError]]. */
    def await: F[S] = F.flatMap(ended)(r => F.fromEither(r.toEither))

    /** Stops the app like `Quit`; returns when the terminal is restored. */
    def stop: F[Unit] = CatsBridge.runF(core.stop)

    /** The result of the run, once it ended. A cancel leaves `end` alone. */
    private def ended: F[kyo.Result[TuiError, S]] =
      F.async { cb =>
        F.delay {
          import kyo.AllowUnsafe.embrace.danger
          end.unsafe.onComplete { r =>
            r.map(_.eval) match
                case kyo.Result.Success(v) => cb(Right(v))
                case kyo.Result.Panic(t)   => cb(Left(t))
                case other                 =>
                  cb(Left(IllegalStateException(s"the run ended with $other")))
          }
          Some(F.unit)
        }
      }
end TuiHandle
