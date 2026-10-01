package tui4s.testkit

import jatatui.core.terminal.Terminal
import kyo.*
import tui4s.*
import tui4s.internal.CountingBackend
import tui4s.internal.Engine
import tui4s.internal.Item
import tui4s.internal.Observer
import tui4s.internal.Probe
import tui4s.internal.Runner

/** A live app under test (`TuiTest.live`): the real loop with real source and
  * task fibers on a test terminal, under virtual time. Each input method waits
  * until its effect is folded and drawn ([[settle]]).
  */
trait LiveTest[S]:
    /** Presses each key in its own batch, like a person typing, and settles
      * after each.
      */
    def press(keys: (Key | Char)*)(using Frame): Unit < Async

    /** Sends `events` in order (usually in one batch), then settles. */
    def send(events: Event*)(using Frame): Unit < Async

    /** Types `text`, one batch per grapheme (as `TuiTest.typeText`), settling
      * after each.
      */
    def typeText(text: String)(using Frame): Unit < Async

    /** Returns when every item put before the call is folded and drawn, and no
      * `task` body runs. A task that never ends makes it wait: give the test a
      * timeout. It parks the calling fiber; it never blocks a thread.
      */
    def settle(using Frame): Unit < Async

    /** Moves virtual time forward by `d` and fires every sleeper due on the
      * way, in deadline order, settling after each 1 ms step.
      *
      * @param sleepers
      *   how many sleepers are parked when the app is idle; the default (-1) is
      *   the number of running `every`, `poll` and `stream` sources. Pass it
      *   for sources that park in other ways (a channel, a task that sleeps).
      */
    def advance(d: Duration, sleepers: Int = -1)(using Frame): Unit < Async

    /** The state after the last batch. */
    def state(using Frame): S < Sync

    /** The last drawn frame. */
    def screen(using Frame): Screen < Sync

    /** The counters after the last batch. */
    def stats(using Frame): Stats < Sync

    /** The labels of the declared standing sources after the last batch. */
    def sources(using Frame): Seq[String] < Sync

    /** True after the app stopped. */
    def quit(using Frame): Boolean < Sync
end LiveTest

/** What the live kit reads: copies made by the render fiber after each batch. */
private[testkit] final class LiveView[S](control: RecordingControl)
    extends Observer[S]:
    @volatile var state: S              = null.asInstanceOf[S]
    @volatile var stats: Stats          = tui4s.internal.Session.noStats
    @volatile var sources: Seq[String]  = Nil
    @volatile var sleepers: Int         = 0
    @volatile var screen: Screen | Null = null
    @volatile var ended: Boolean        = false

    def published(
        engine: Engine[S],
        terminal: Terminal[CountingBackend],
        drawn: Boolean,
    )(using AllowUnsafe): Unit =
        state = engine.state
        stats = engine.stats
        sources = engine.sup.declared
        sleepers = engine.sup.sleepers
        if drawn || screen == null then
            screen = Screen.of(control.backend.buffer(), engine.painter.cursor)
    end published

    override def ended(engine: Engine[S])(using AllowUnsafe): Unit =
        stats = engine.stats
        sleepers = 0
        ended = true
end LiveView

/** The live kit: the app on its own fiber, under time control. */
private[testkit] final class LiveRun[S](
    options: TuiOptions,
    handle: TuiHandle[S],
    control: Clock.TimeControl,
    view: LiveView[S],
) extends LiveTest[S]:

    private def put(item: Item)(using Frame): Unit < Async =
      Abort.run[Closed](handle.inbox.put(item)).unit

    def press(keys: (Key | Char)*)(using Frame): Unit < Async =
      Kyo.foreachDiscard(keys)(k =>
        put(Key.sent(Bind.toKey(k))).andThen(settle),
      )

    def send(events: Event*)(using Frame): Unit < Async =
        events.foreach(TuiTest.reported(options, _))
        val sent: Seq[Item] = events.map {
          case k: Key => Key.sent(k)
          case other  => other
        }
        Abort.run[Closed](handle.inbox.putBatch(sent)).andThen(settle)

    def typeText(text: String)(using Frame): Unit < Async =
      Kyo.foreachDiscard(TuiTest.typed(text))(e => put(e).andThen(settle))

    def settle(using Frame): Unit < Async =
      Sync.Unsafe
        .defer(Promise.Unsafe.init[Stats, Any]())
        .map { p =>
          Abort.run[Closed](handle.inbox.put(Probe(p, untilIdle = true))).map {
            case Result.Success(_) => p.safe.get.unit
            case _                 => Kyo.unit
          }
        }

    def advance(d: Duration, sleepers: Int = -1)(using Frame): Unit < Async =
        def parked: Unit < Async =
          Sync
            .defer(if view.ended then 0
            else if sleepers >= 0 then sleepers
            else view.sleepers)
            .map(n =>
              if n > 0 then control.awaitPendingSleepers(n) else Kyo.unit,
            )
        Loop(d) { remaining =>
          if remaining <= Duration.Zero then
              parked.andThen(settle).andThen(Loop.done(()))
          else
              val step = remaining.min(LiveRun.Step)
              parked
                .andThen(control.advance(step, Duration.Zero))
                .andThen(settle)
                .andThen(Loop.continue(remaining.minusOrZero(step)))
        }
    end advance

    def state(using Frame): S < Sync = Sync.defer(view.state)

    def screen(using Frame): Screen < Sync =
      Sync.defer {
        val s = view.screen
        if s == null then Screen(Vector.empty, Vector.empty, None) else s
      }

    def stats(using Frame): Stats < Sync = Sync.defer(view.stats)

    def sources(using Frame): Seq[String] < Sync = Sync.defer(view.sources)

    def quit(using Frame): Boolean < Sync = Sync.defer(view.ended)
end LiveRun

private[testkit] object LiveRun:
    private val Step: Duration = 1.millis

    /** Runs `test` against `app` on a fresh live run; stops the app after. */
    def apply[S, A](app: App[S], width: Int, height: Int)(
        test: LiveTest[S] => A < (Async & Abort[Throwable]),
    )(using Frame): A < (Async & Abort[Throwable]) =
        val fixed = app.options.copy(fps = 0)
        val live  = new App[S]:
            def init: S                                    = app.init
            def view(state: S): View                       = app.view(state)
            def update(state: S, event: Event): Next[S]    =
              app.update(state, event)
            override def sources(state: S): Seq[Source[S]] = app.sources(state)
            override def options: TuiOptions               = fixed
        Random.withSeed(0) {
          Clock.withTimeControl { control =>
            val terminal = RecordingControl(width, height)
            val view     = LiveView[S](terminal)
            Runner.startUnscoped(live, terminal, None, view).map { handle =>
              val run = new LiveRun(fixed, handle, control, view)
              Sync.ensure(handle.kill) {
                run.settle
                  .andThen(Abort.run[Throwable](test(run)))
                  .map(result => handle.stop.andThen(Abort.get(result)))
              }
            }
          }
        }
    end apply
end LiveRun
