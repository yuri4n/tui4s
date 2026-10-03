package tui4s.cats

import _root_.cats.effect.IO
import _root_.cats.effect.IOApp
import _root_.cats.effect.Resource
import _root_.cats.effect.kernel.Async
import _root_.cats.effect.std.Dispatcher
import _root_.cats.effect.unsafe.IORuntimeConfig
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import tui4s.cats.internal.AmbientDispatcher
import tui4s.cats.internal.CatsBridge
import tui4s.spi.Emitter
import tui4s.spi.Sources

object Source:
  def every[S](period: FiniteDuration)(f: S => Next[S])(using CallSite): Source[S] =
    Sources.makePeriodic[S, Unit](summon[CallSite].show)(emit =>
      kyo.Loop.forever(kyo.Async.sleep(kyo.Duration.fromScala(period)).andThen(emit(())))
    )((s, _) => f(s))
  def poll[F[_]: Async, S, A](period: FiniteDuration)(task: F[A])(f: (S, A) => Next[S])(using CallSite): Source[S] =
    For[F, S].poll(period)(task)(f)
  def task[F[_]: Async, S, A](task: F[A])(f: (S, A) => Next[S])(using CallSite): Source[S] =
    For[F, S].task(task)(f)
  def stream[F[_]: Async, S, A](stream: fs2.Stream[F, A])(f: (S, A) => Next[S])(using CallSite): Source[S] =
    For[F, S].stream(stream)(f)

  final class For[F[_], S] private[cats] ()(using F: Async[F]):
    def every(period: FiniteDuration)(f: S => Next[S])(using CallSite): Source[S] = Source.every(period)(f)

    def poll[A](period: FiniteDuration)(task: F[A])(f: (S, A) => Next[S])(using cs: CallSite): Source[S] =
      Sources.makePeriodic[S, A](cs.show)(emit =>
        AmbientDispatcher.use[F, Nothing](d =>
          CatsBridge.getF(F.foreverM(F.productR(F.flatMap(task)(a => put(emit, a)))(F.sleep(period))))(using d)
        )
      )(f)

    def task[A](task: F[A])(f: (S, A) => Next[S])(using cs: CallSite): Source[S] =
      Sources.makeTask[S, A](cs.show)(emit =>
        AmbientDispatcher.use[F, Unit](d => CatsBridge.getF(F.flatMap(task)(a => put(emit, a)))(using d))
      )(f)

    def stream[A](stream: fs2.Stream[F, A])(f: (S, A) => Next[S])(using cs: CallSite): Source[S] =
      Sources.makeChunked[S, A](cs.show)(emit =>
        AmbientDispatcher.use[F, Unit](d =>
          CatsBridge.getF(stream.chunks.evalMap(c => put(emit, c.toVector)).compile.drain)(using d)
        )
      )(f)
  end For

  object For:
    def apply[F[_]: Async, S]: For[F, S] = new For[F, S]()

  /** Backpressure reaches fs2: the callback runs once the value is in the inbox. */
  private def put[F[_], A](emit: Emitter[A], a: A)(using F: Async[F]): F[Unit] =
    F.async_[Unit] { cb =>
      import kyo.AllowUnsafe.embrace.danger
      emit.unsafe.put(a, () => cb(Right(())))
    }
end Source

object Tui:
  /** Fails with TuiError. Cancel restores the terminal before the cancel completes (cancel-and-await). */
  def run[F[_]: Async, S](app: App[S]): F[S] =
    Async[F].productR(Async[F].delay(tui4s.spi.SchedulerDefaults.applyIfUnset()))(
      runControl(app, tui4s.internal.CrosstermControl())
    )

  /** The app as a resource; the release stops it and waits for the restore (CE's own bracket). */
  def resource[F[_]: Async, S](app: App[S]): Resource[F, TuiHandle[F, S]] =
    resourceWith(app, tui4s.internal.CrosstermControl())

  /** The seam of SPEC 6.3 (`Tui.runWith`): the same run on any jatatui Backend and InputPort. */
  private[tui4s] def runWith[F[_]: Async, S](
      app: App[S],
      backend: jatatui.core.backend.Backend,
      input: tui4s.spi.InputPort
  ): F[S] = runControl(app, tui4s.internal.HeadlessControl(backend, input))

  private[tui4s] def runControl[F[_]: Async, S](app: App[S], control: tui4s.internal.TerminalControl): F[S] =
    CatsBridge.runWithDispatcher[F, S](AmbientDispatcher.let(summon[Dispatcher[F]])(tui4s.internal.Runner.run(app, control)))

  private[tui4s] def resourceWith[F[_]: Async, S](
      app: App[S],
      control: tui4s.internal.TerminalControl
  ): Resource[F, TuiHandle[F, S]] =
    Dispatcher.parallel[F](await = false).flatMap { d =>
      Resource.make(
        Async[F].map(CatsBridge.runF(AmbientDispatcher.let(d)(tui4s.internal.Runner.startUnscoped(app, control))))(
          h => new TuiHandle[F, S](h)
        )
      )(h => h.stop)
    }
end Tui

final class TuiHandle[F[_], S] private[cats] (core: tui4s.TuiHandle[S])(using F: Async[F]):
  def update(f: S => Next[S]): F[Unit] = CatsBridge.runF(core.update(f))
  def send(event: Event): F[Unit]      = CatsBridge.runF(core.send(event))
  def states: fs2.Stream[F, S]         = fs2.Stream.repeatEval(CatsBridge.runF(core.states.next))
  def stats: F[Stats]                  = CatsBridge.runF(core.stats)
  def await: F[S]                      = CatsBridge.runF(core.await)
  def stop: F[Unit]                    = CatsBridge.runF(core.stop)
end TuiHandle

abstract class TuiApp[S] extends IOApp.Simple, tui4s.App[S]:
  tui4s.spi.SchedulerDefaults.applyIfUnset() // constructor body, first statement

  /** CE's CPU-starvation checker prints to stderr and would draw over the UI: off (Inf = never starts). */
  override protected def runtimeConfig: IORuntimeConfig =
    super.runtimeConfig.copy(cpuStarvationCheckInitialDelay = Duration.Inf)

  protected final def source: Source.For[IO, S] = Source.For[IO, S]

  final def run: IO[Unit] = Tui.run[IO, S](this).flatMap(onExit)

  protected def onExit(@annotation.unused state: S): IO[Unit] = IO.unit
end TuiApp
