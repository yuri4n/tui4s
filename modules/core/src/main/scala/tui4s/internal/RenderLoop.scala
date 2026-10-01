package tui4s.internal

import jatatui.core.terminal.Terminal
import kyo.*
import tui4s.*

/** Watches a run from the render fiber: the handle publishes states and stats
  * through it, the live test kit copies the screen. Calls happen on the render
  * fiber, between frames, so an observer may read the engine and the terminal.
  */
private[tui4s] trait Observer[S]:
    /** After the first frame and after each draw decision. `drawn`: a frame was
      * drawn.
      */
    def published(
        engine: Engine[S],
        terminal: Terminal[CountingBackend],
        drawn: Boolean,
    )(using
        AllowUnsafe,
    ): Unit

    /** The run ended (after the loop, before the terminal release). */
    def ended(engine: Engine[S])(using AllowUnsafe): Unit = ()
end Observer

/** The render loop (SPEC 5.4): take (park) -> drain -> fold -> work -> frame
  * slot -> draw. The first change after an idle period draws at once; later
  * changes within one frame budget wait for the rest of the budget (a sleep,
  * then a drain), so a burst folds into one frame. Never `Async.timeout` around
  * `take`: it can drop a delivered item in Kyo RC7.
  *
  * @param frameNanos
  *   the frame budget; 0 draws after every changed batch (live tests)
  */
private[tui4s] final class RenderLoop[S](
    engine: Engine[S],
    launcher: Launcher[S],
    inbox: Channel[Item],
    terminal: Terminal[CountingBackend],
    control: TerminalControl,
    frameNanos: Long,
    observer: Observer[S] | Null,
):
    private def now(): Long = java.lang.System.nanoTime()

    def run(using Frame): S < (Async & Abort[TuiError]) =
      start.andThen(
        Loop(now()) { drawnAt =>
          takeBatch.map(fold).map {
            case Step.Quit     => Loop.done(engine.state)
            case Step.Failed   => failed
            case Step.Same     => same(drawnAt)
            case Step.WorkOnly => work.andThen(same(drawnAt))
            case Step.Changed  => afterChange(drawnAt)
          }
        },
      )

    /** `init`, `sources(init)`, their start, the first frame. */
    private def start(using Frame): Unit < (Async & Abort[TuiError]) =
      Sync.Unsafe
        .defer(engine.start() && engine.syncWork())
        .map { ok =>
          if !ok then failed
          else
              launcher.launch
                .andThen(Sync.Unsafe.defer {
                  val size = terminal.size()
                  val ok   = engine.firstFrame(size.width, size.height, paint)
                  if ok && observer != null then
                      observer.published(engine, terminal, drawn = true)
                  ok
                })
                .map(ok => if ok then Kyo.unit else failed)
        }

    private def failed(using Frame): Nothing < (Sync & Abort[TuiError]) =
      Sync.Unsafe.defer(engine.error).map { e =>
        Abort.fail(
          if e == null then TuiError.TerminalIO(IllegalStateException("failed"))
          else e,
        )
      }

    private def fold(batch: Chunk[Item])(using Frame): Step < Sync =
      Sync.Unsafe.defer(engine.fold(batch))

    private def work(using Frame): Unit < (Sync & Abort[TuiError]) =
      Sync.Unsafe
        .defer(engine.syncWork())
        .map(ok => if ok then launcher.launch else failed)

    /** The decision for a batch whose state is the same object. */
    private def same(drawnAt: Long)(using
        Frame,
    ): Loop.Outcome[Long, S] < Sync =
      Sync.Unsafe.defer {
        engine.skipSame()
        if observer != null then
            observer.published(engine, terminal, drawn = false)
        engine.completeProbes()
        Loop.continue(drawnAt)
      }

    /** Parks until something arrives, then takes everything that is queued
      * (coalescing). Before it parks, buffered trace lines are written out.
      */
    private def takeBatch(using
        Frame,
    ): Chunk[Item] < (Async & Abort[TuiError]) =
      Sync.Unsafe
        .defer(engine.flushTrace())
        .andThen(
          inbox.take
            .map(first => inbox.drain.map(rest => Chunk(first).concat(rest)))
            .handle(closedIsBug),
        )

    private def closedIsBug[A](v: A < (Async & Abort[Closed]))(using
        Frame,
    ): A < (Async & Abort[TuiError]) =
      Abort.recover[Closed](c =>
        Abort.fail(
          TuiError.TerminalIO(IllegalStateException(s"inbox closed: $c")),
        ),
      )(v)

    private def afterChange(drawnAt: Long)(using
        Frame,
    ): Loop.Outcome[Long, S] < (Async & Abort[TuiError]) =
      work.andThen {
        val wait = drawnAt + frameNanos - now()
        if wait <= 0 then draw(drawnAt)
        else
            Async
              .sleep(Duration.fromNanos(wait))
              .andThen(inbox.drain.handle(closedIsBug))
              .map(rest => if rest.isEmpty then Step.Same else fold(rest))
              .map {
                case Step.Quit   => Loop.done(engine.state)
                case Step.Failed => failed
                case _           => work.andThen(draw(drawnAt))
              }
        end if
      }

    /** Builds the view, applies level 2, draws at most once. The next frame
      * slot starts at the new frame (unchanged when nothing was drawn).
      */
    private def draw(drawnAt: Long)(using
        Frame,
    ): Loop.Outcome[Long, S] < (Sync & Abort[TuiError]) =
      Sync.Unsafe
        .defer {
          val r     = engine.pendingResize
          if r != null then
              engine.pendingResize = null
              control.resize(r.width, r.height)
          val drawn = engine.drawIfNeeded(paint)
          if engine.error == null then
              if observer != null then
                  observer.published(engine, terminal, drawn)
              engine.completeProbes()
          drawn
        }
        .map { drawn =>
          Sync.Unsafe.defer(engine.error).map { err =>
            if err != null then Abort.fail(err)
            else Loop.continue(if drawn then now() else drawnAt)
          }
        }

    /** One frame: jatatui diffs the buffers and writes the changed cells. Its
      * I/O errors are terminal errors.
      */
    private def paint(view: View): Int =
        try discard(terminal.draw(frame => engine.painter.paint(view, frame)))
        catch case e: java.io.IOException => throw TuiError.TerminalIO(e)
        terminal.backend().lastCount
end RenderLoop

/** Runs apps on a [[TerminalControl]] (SPEC 5.9): the inbox, the session with
  * its synchronous release, the supervisor, the render loop.
  */
private[tui4s] object Runner:
    /** Runs `app` until it quits; the terminal is released before the result is
      * visible, also on failure and on interruption.
      */
    def run[S](
        app: App[S],
        control: TerminalControl,
        input: Option[spi.InputPort],
        observer: Observer[S] | Null = null,
    )(using Frame): S < (Async & Abort[TuiError]) =
        val opts = app.options
        Channel
          .initUnscoped[Item](
            opts.inboxCapacity.max(1),
            Access.MultiProducerSingleConsumer,
          )
          .map(inbox => runOn(app, opts, control, input, inbox, observer))
    end run

    /** [[run]] on an inbox that the caller made (a handle puts into it). */
    def runOn[S](
        app: App[S],
        opts: TuiOptions,
        control: TerminalControl,
        input: Option[spi.InputPort],
        inbox: Channel[Item],
        observer: Observer[S] | Null,
    )(using Frame): S < (Async & Abort[TuiError]) =
        val frameNanos = if opts.fps <= 0 then 0L else 1_000_000_000L / opts.fps
        // A synchronous release (F8): it has run when the result is visible,
        // also on interruption. Never a Scope finalizer for the terminal.
        Sync.acquireReleaseWith(Session.open(opts, control, input, inbox))(s =>
          Sync.Unsafe.defer(s.close()),
        ) { session =>
          Sync.Unsafe
            .defer {
              val tracer =
                opts.trace.map(TraceWriter.open).getOrElse(null)
              val sup    = Supervisor[S]()
              val engine = Engine(app, opts, Painter(opts.theme), sup, tracer)
              (engine, Launcher(sup, inbox), tracer)
            }
            .map { (engine, launcher, tracer) =>
              Sync.ensure(
                launcher.stopAll.andThen(Sync.Unsafe.defer {
                  engine.finish("interrupted")
                  if observer != null then observer.ended(engine)
                  Session.closeInbox(inbox)
                  engine.completeAllProbes()
                  if tracer != null then tracer.close()
                }),
              )(
                RenderLoop(
                  engine,
                  launcher,
                  inbox,
                  session.terminal,
                  control,
                  frameNanos,
                  observer,
                ).run,
              )
            }
        }
    end runOn

    /** Starts `app` on its own fiber, without a scope, and returns its handle
      * once the first frame is drawn (or the run ended). The caller stops it:
      * the facades use their own bracket, never a Kyo `Scope` (F8).
      */
    def startUnscoped[S](
        app: App[S],
        control: TerminalControl,
        input: Option[spi.InputPort],
        extra: Observer[S] | Null = null,
    )(using Frame): TuiHandle[S] < Async =
        val opts = app.options
        Channel
          .initUnscoped[Item](
            opts.inboxCapacity.max(1),
            Access.MultiProducerSingleConsumer,
          )
          .map { inbox =>
            Sync.Unsafe.defer(HandleObserver[S](extra)).map { obs =>
              Fiber
                .initUnscoped(runOn(app, opts, control, input, inbox, obs))
                .map { fiber =>
                  Sync.Unsafe
                    .defer(fiber.unsafe.onComplete(_ => obs.release()))
                    .andThen(obs.ready.safe.get)
                    .andThen(TuiHandle[S](inbox, fiber, obs))
                }
            }
          }
    end startUnscoped

    /** [[startUnscoped]]; the end of the scope stops the app and waits for the
      * release.
      */
    def start[S](
        app: App[S],
        control: TerminalControl,
        input: Option[spi.InputPort],
    )(using Frame): TuiHandle[S] < (Async & Scope) =
      startUnscoped(app, control, input).map(h =>
        Scope.ensure(h.stop).andThen(h),
      )
end Runner

/** What a [[TuiHandle]] reads: the states signal and a stats snapshot, written
  * by the render fiber.
  */
private[tui4s] final class HandleObserver[S](extra: Observer[S] | Null)(using
    AllowUnsafe,
) extends Observer[S]:
    /** Completed after the first frame, or when the run ended without one. */
    val ready: Promise.Unsafe[Unit, Any] = Promise.Unsafe.init[Unit, Any]()

    @volatile private var ref: Signal.SignalRef[S] | Null = null
    @volatile var stats: Stats                            = Session.noStats

    /** The states signal; `null` before the first frame. */
    def states: Signal.SignalRef[S] | Null = ref

    def published(
        engine: Engine[S],
        terminal: Terminal[CountingBackend],
        drawn: Boolean,
    )(using AllowUnsafe): Unit =
        stats = engine.stats
        val r = ref
        if r == null then
            given CanEqual[S, S] = CanEqual.derived
            ref = Signal.SignalRef.Unsafe.init[S](engine.state).safe
            ready.completeDiscard(Result.succeed(()))
        else r.unsafe.set(engine.state)
        if extra != null then extra.published(engine, terminal, drawn)
    end published

    override def ended(engine: Engine[S])(using AllowUnsafe): Unit =
        stats = engine.stats
        if extra != null then extra.ended(engine)

    /** The run ended: release a caller that waits for the first frame. */
    def release()(using AllowUnsafe): Unit =
      ready.completeDiscard(Result.succeed(()))
end HandleObserver
