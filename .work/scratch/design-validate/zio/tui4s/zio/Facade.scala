package tui4s.zio

import _root_.zio.Duration
import _root_.zio.IO
import _root_.zio.Runtime
import _root_.zio.Scope
import _root_.zio.Trace
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.ZIOAppArgs
import _root_.zio.ZIOAppDefault
import _root_.zio.ZLayer
import _root_.zio.stream.ZStream
import tui4s.spi.Emitter
import tui4s.spi.Sources
import tui4s.zio.internal.AmbientZIOs

object Source:
  def every[S](period: Duration)(f: S => Next[S])(using CallSite): Source[S] = For[S].every(period)(f)

  /** Runs `task` now, then again `period` after each result (ZIO clock), and folds each result. */
  def poll[S, A](period: Duration)(task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S] =
    For[S].poll(period)(task)(f)

  def task[S, A](task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S] =
    For[S].task(task)(f)

  /** One state change per chunk. Inbox backpressure reaches the stream. */
  def stream[S, A](stream: ZStream[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S] =
    For[S].stream(stream)(f)

  final class For[S] private[zio] ():
    def every(period: Duration)(f: S => Next[S])(using cs: CallSite): Source[S] =
      Sources.makePeriodic[S, Unit](cs.show)(emit =>
        kyo.Loop.forever(kyo.Async.sleep(kyo.Duration.fromJava(period)).andThen(emit(())))
      )((s, _) => f(s))

    def poll[A](period: Duration)(task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using
        cs: CallSite,
        t: Trace
    ): Source[S] =
      Sources.makePeriodic[S, A](cs.show)(emit =>
        AmbientZIOs.get((task.flatMap(a => put(emit, a)) *> ZIO.sleep(period)).forever)
      )(f)

    def task[A](task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using cs: CallSite, t: Trace): Source[S] =
      Sources.makeTask[S, A](cs.show)(emit => AmbientZIOs.get(task.flatMap(a => put(emit, a))))(f)

    def stream[A](stream: ZStream[Any, Throwable, A])(f: (S, A) => Next[S])(using cs: CallSite, t: Trace): Source[S] =
      Sources.makeChunked[S, A](cs.show)(emit => AmbientZIOs.get(stream.runForeachChunk(c => put(emit, c))))(f)
  end For

  object For:
    private val shared   = new For[Any]
    def apply[S]: For[S] = shared.asInstanceOf[For[S]]

  /** Inbox backpressure reaches the ZIO / ZStream: the callback runs once the value is in the inbox. */
  private def put[A](emit: Emitter[A], a: A)(using Trace): UIO[Unit] =
    ZIO.async[Any, Nothing, Unit] { cb =>
      import kyo.AllowUnsafe.embrace.danger
      emit.unsafe.put(a, () => cb(ZIO.unit))
    }
end Source

object Tui:
  /** Runs the app in the real terminal on the caller's runtime. Interruption restores the terminal before the
    * interrupt completes.
    */
  def run[S](app: App[S])(using Trace): IO[TuiError, S] =
    ZIO.succeed(tui4s.spi.SchedulerDefaults.applyIfUnset()) *> AmbientZIOs.run(tui4s.Tui.run(app))

  /** The app on its own fiber; the scope's release stops it and waits for the restore. */
  def start[S](app: App[S])(using Trace): ZIO[Scope, Nothing, TuiHandle[S]] =
    startWith(app, tui4s.internal.CrosstermControl())

  /** The seam of SPEC 6.3 (`Tui.runWith`): the same run on any jatatui Backend and InputPort. */
  private[tui4s] def runWith[S](app: App[S], backend: jatatui.core.backend.Backend, input: tui4s.spi.InputPort)(using
      Trace
  ): IO[TuiError, S] =
    AmbientZIOs.run(tui4s.spi.Headless.run(app, backend, input))

  private[tui4s] def startWith[S](app: App[S], control: tui4s.internal.TerminalControl)(using
      Trace
  ): ZIO[Scope, Nothing, TuiHandle[S]] =
    ZIO.acquireRelease(AmbientZIOs.run(tui4s.internal.Runner.startUnscoped(app, control)).map(TuiHandle(_)))(h =>
      h.stop
    )
end Tui

final class TuiHandle[S] private[zio] (core: tui4s.TuiHandle[S]):
  def update(f: S => Next[S])(using Trace): UIO[Unit] = AmbientZIOs.run(core.update(f))
  def send(event: Event)(using Trace): UIO[Unit]      = AmbientZIOs.run(core.send(event))
  def states(using Trace): ZStream[Any, Nothing, S] =
    ZStream.repeatZIO(AmbientZIOs.run(core.states.next))
  def stats(using Trace): UIO[Stats]  = AmbientZIOs.run(core.stats)
  def await(using Trace): IO[TuiError, S] = AmbientZIOs.run(core.await)
  def stop(using Trace): UIO[Unit]    = AmbientZIOs.run(core.stop)
end TuiHandle

abstract class TuiApp[S] extends ZIOAppDefault, tui4s.App[S]:
  tui4s.spi.SchedulerDefaults.applyIfUnset() // constructor body, first statement

  /** ZIO's default console logger writes to stdout and would draw over the UI. */
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers

  protected final def source: Source.For[S] = Source.For[S]

  final def run: ZIO[ZIOAppArgs & Scope, Any, Any] = Tui.run(this).flatMap(onExit)

  protected def onExit(@annotation.unused state: S): ZIO[Any, Throwable, Unit] = ZIO.unit
end TuiApp
