package tui4s.internal

import jatatui.core.terminal.Terminal
import kyo.*
import tui4s.*

/** The render loop (5.4): take -> drain -> fold -> work -> frame slot -> draw. Idiomatic Kyo, no for-comprehension. */
private[tui4s] final class RenderLoop[S](
    engine: Engine[S],
    launcher: Launcher[S],
    inbox: Channel[Item],
    terminal: Terminal[CountingBackend],
    frameNanos: Long,
    states: Signal.SignalRef[S] | Null
):
  private def now(): Long = java.lang.System.nanoTime()

  def run(using Frame): S < (Async & Abort[TuiError]) =
    start.andThen(
      Loop(now()) { drawnAt =>
        takeBatch.map(fold).map {
          case Step.Quit     => Loop.done(engine.state)
          case Step.Failed   => failed
          case Step.Same     => probes.andThen(Loop.continue(drawnAt))
          case Step.WorkOnly => work.andThen(probes).andThen(Loop.continue(drawnAt))
          case Step.Changed  => afterChange(drawnAt)
        }
      }
    )

  private def failed(using Frame): Nothing < (Sync & Abort[TuiError]) =
    Sync.defer(engine.error).map { e =>
      Abort.fail(if e == null then TuiError.TerminalIO(new IllegalStateException("failed")) else e)
    }

  /** sources(init), first frame. */
  private def start(using Frame): Unit < (Sync & Abort[TuiError]) =
    Sync.Unsafe.defer(engine.start() && engine.planWork()).map { ok =>
      if !ok then failed
      else
        launcher.launch.andThen(Sync.Unsafe.defer {
          discard(drawNow())
          if states != null then states.unsafe.set(engine.state) // the first state, before any batch
        })
    }

  private def fold(batch: Chunk[Item])(using Frame): Step < Sync = Sync.Unsafe.defer(engine.fold(batch))

  private def work(using Frame): Unit < (Sync & Abort[TuiError]) =
    Sync.Unsafe.defer(engine.planWork()).map(ok => if ok then launcher.launch else failed)

  private def probes(using Frame): Unit < Sync = Sync.Unsafe.defer(engine.completeProbes())

  /** Park until something arrives, then take everything that is queued (coalescing). */
  private def takeBatch(using Frame): Chunk[Item] < (Async & Abort[TuiError]) =
    inbox.take.map(first => inbox.drain.map(rest => Chunk(first).concat(rest))).handle(closedIsBug)

  private def closedIsBug[A](v: A < (Async & Abort[Closed]))(using Frame): A < (Async & Abort[TuiError]) =
    Abort.recover[Closed](c => Abort.fail(TuiError.TerminalIO(new IllegalStateException(s"inbox closed: $c"))))(v)

  private def afterChange(drawnAt: Long)(using Frame): Loop.Outcome[Long, S] < (Async & Abort[TuiError]) =
    work.andThen {
      val wait = drawnAt + frameNanos - now()
      if wait <= 0 then draw(drawnAt) // first change after idle: no added latency
      else
        Async
          .sleep(Duration.fromNanos(wait)) // sleep the remainder of the frame budget, then drain.
          .andThen(inbox.drain.handle(closedIsBug)) // NEVER Async.timeout around take (1.4)
          .map(fold)
          .map {
            case Step.Quit   => Loop.done(engine.state)
            case Step.Failed => failed
            case _           => work.andThen(draw(drawnAt)) // the earlier change still needs its frame
          }
      end if
    }

  /** Builds the view, applies level 2, draws at most once. Returns the new drawnAt (unchanged when skipped). */
  private def draw(drawnAt: Long)(using Frame): Loop.Outcome[Long, S] < (Sync & Abort[TuiError]) =
    Sync.Unsafe.defer {
      val drawn = drawNow()
      if states != null then states.unsafe.set(engine.state)
      engine.completeProbes()
      (drawn, engine.error)
    }.map { (drawn, err) =>
      if err != null then Abort.fail(err)
      else Loop.continue(if drawn then now() else drawnAt)
    }

  private def drawNow()(using AllowUnsafe): Boolean =
    engine.drawIfNeeded { view =>
      discard(terminal.draw(frame => engine.painter.paint(view, frame)))
      terminal.backend().lastCount
    }
end RenderLoop

private[tui4s] object Runner:
  def run[S](app: App[S], control: TerminalControl)(using Frame): S < (Async & Abort[TuiError]) =
    val opts = app.options
    Channel.initUnscoped[Item](opts.inboxCapacity, Access.MultiProducerSingleConsumer).map { inbox =>
      runWith(app, control, inbox, null, null)
    }

  def runWith[S](
      app: App[S],
      control: TerminalControl,
      inbox: Channel[Item],
      states: Signal.SignalRef[S] | Null,
      engineRef: java.util.concurrent.atomic.AtomicReference[Engine[S] | Null] | Null
  )(using Frame): S < (Async & Abort[TuiError]) =
    val opts       = app.options
    val frameNanos = if opts.fps <= 0 then 0L else 1_000_000_000L / opts.fps
    Sync.acquireReleaseWith(control.open(opts, inbox))(_.close()) { session => // synchronous release (F8)
      Sync.defer {
        val sup    = Supervisor[S]()
        val engine = Engine(app, opts, Painter(opts.theme), sup)
        if engineRef != null then engineRef.set(engine)
        (engine, Launcher(sup, inbox))
      }.map { (engine, launcher) =>
        Sync.ensure(launcher.stopAll)(
          RenderLoop(engine, launcher, inbox, session.terminal, frameNanos, states).run
        )
      }
    }
  end runWith

  def start[S](app: App[S], control: TerminalControl)(using Frame): TuiHandle[S] < (Async & Scope) =
    startUnscoped(app, control).map(handle => Scope.ensure(handle.stop).andThen(handle))

  /** The app on its own fiber, no Scope: the facades release it with their own bracket (ZIO / CE), never a core
    * `Scope.ensure` (F8).
    */
  def startUnscoped[S](app: App[S], control: TerminalControl)(using Frame): TuiHandle[S] < Sync =
    val opts = app.options
    Channel.initUnscoped[Item](opts.inboxCapacity, Access.MultiProducerSingleConsumer).map { inbox =>
      Sync.Unsafe.defer {
        given CanEqual[S, S] = CanEqual.derived
        Signal.SignalRef.Unsafe.init[S](null.asInstanceOf[S]).safe
      }.map { ref =>
        val engineRef = java.util.concurrent.atomic.AtomicReference[Engine[S] | Null](null)
        def statsNow(): Stats =
          val e = engineRef.get()
          if e == null then Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) else e.stats
        Fiber.initUnscoped(runWith(app, control, inbox, ref, engineRef)).map { fiber =>
          new TuiHandle[S](inbox, fiber, ref, () => statsNow())
        }
      }
    }
  end startUnscoped
end Runner
