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
import tui4s.internal.Session

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

    /** Types `text`, one batch per grapheme (as `TuiTest.typeText`: "\n" is
      * Enter, "\t" is Tab), settling after each.
      */
    def typeText(text: String)(using Frame): Unit < Async

    /** Returns when every item put before the call is folded and drawn, and no
      * `task` body runs, except a body that waits on the virtual clock (only
      * [[advance]] lets it go on). A task that waits for anything else makes it
      * wait: give the test a timeout. It parks the calling fiber; it never
      * blocks a thread.
      *
      * The kit sees "waits on the clock" as a count: at least as many parked
      * sleepers as running `every`, `poll` and `stream` sources plus running
      * tasks. Another body that sleeps on the clock (a general source, a task
      * that sleeps on two fibers at a time) can make it return before a busy
      * task ends; inside [[advance]], its `sleepers` count is used instead.
      */
    def settle(using Frame): Unit < Async

    /** Moves virtual time forward by `d` and fires every sleeper due on the
      * way, in deadline order, in 1 ms steps. Before each step, every running
      * source and task is parked again; after each step, it settles.
      *
      * @param sleepers
      *   how many sleepers are parked when the app is idle and no task runs;
      *   the default (-1) is the number of running `every`, `poll` and `stream`
      *   sources. Pass it for sources that park in other ways (a channel, a
      *   general source that sleeps). A running task that sleeps is counted by
      *   the kit.
      */
    def advance(d: Duration, sleepers: Int = -1)(using Frame): Unit < Async

    /** The state after the last batch (after the end: the state the app stopped
      * with).
      */
    def state(using Frame): S < Sync

    /** The last drawn frame. */
    def screen(using Frame): Screen < Sync

    /** The counters after the last batch. */
    def stats(using Frame): Stats < Sync

    /** The labels of the declared standing sources after the last batch. */
    def sources(using Frame): Seq[String] < Sync

    /** True after the app stopped. */
    def quit(using Frame): Boolean < Sync

    /** The failure that ended the app, if it ended with one: an
      * [[TuiError.AppFailed]] (user code threw) or a [[TuiError.SourceFailed]].
      * `TuiTest.live` also fails with it after the test.
      */
    def error(using Frame): Option[TuiError] < Sync
end LiveTest

/** What the live kit reads of one draw decision, written at once. */
private[testkit] final case class LiveCounts(
    batches: Long,
    sleepers: Int,
    busy: Int,
    ended: Boolean,
)

/** What the live kit reads: copies made by the render fiber after each batch. */
private[testkit] final class LiveView[S](control: RecordingControl)
    extends Observer[S]:
    @volatile var state: S               = null.asInstanceOf[S]
    @volatile var stats: Stats           = Session.noStats
    @volatile var sources: Seq[String]   = Nil
    @volatile var counts: LiveCounts     = LiveCounts(0, 0, 0, ended = false)
    @volatile var screen: Screen | Null  = null
    @volatile var error: TuiError | Null = null

    def published(
        engine: Engine[S],
        terminal: Terminal[CountingBackend],
        drawn: Boolean,
    )(using AllowUnsafe): Unit =
        state = engine.state
        stats = engine.stats
        sources = engine.sup.declared
        counts = LiveCounts(
          stats.batches,
          engine.sup.sleepers,
          engine.sup.busyTasks,
          ended = false,
        )
        if drawn || screen == null then
            screen = Screen.of(control.backend.buffer(), engine.painter.cursor)
    end published

    /** The quitting batch's state is the last state; nothing runs any more. */
    override def ended(engine: Engine[S])(using AllowUnsafe): Unit =
        if engine.state != null then state = engine.state
        stats = engine.stats
        error = engine.error
        counts = LiveCounts(stats.batches, 0, 0, ended = true)

    def ended: Boolean = counts.ended
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
        Abort
          .run[Closed](handle.inbox.putBatch(events.map(LiveRun.sent)))
          .andThen(settle)

    def typeText(text: String)(using Frame): Unit < Async =
      Kyo.foreachDiscard(TuiTest.typed(text))(e =>
        put(LiveRun.sent(e)).andThen(settle),
      )

    /** Puts a probe that completes after the decision of more than `after`
      * batches; None when the app has ended (the inbox is closed).
      */
    private def probe(after: Long)(using
        Frame,
    ): Maybe[Promise.Unsafe[Stats, Any]] < Async =
      Sync.Unsafe
        .defer(Promise.Unsafe.init[Stats, Any]())
        .map { p =>
          Abort.run[Closed](handle.inbox.put(Probe(p, after))).map {
            case Result.Success(_) => Present(p)
            case _                 => Absent
          }
        }

    /** Returns when everything put before the call is folded and drawn. */
    private def drained(using Frame): Unit < Async =
      probe(-1).map {
        case Present(p) => p.safe.get.unit
        case Absent     => Kyo.unit
      }

    /** Waits until at least `n` sleepers are parked on the virtual clock
      * (true), or until the app folded a batch after the first `seen` (false).
      * Either way the caller reads the counts again: they may have changed.
      */
    private def parkedOrNews(n: Int, seen: Long)(using Frame): Boolean < Async =
      probe(seen).map {
        case Absent     => false
        case Present(p) =>
          Async
            .race(
              control.awaitPendingSleepers(n).andThen(true),
              p.safe.get.andThen(false),
            )
            .map { parked =>
              // Complete the probe: if it lost the race, the render fiber
              // then drops it instead of keeping it until the next news.
              Sync.Unsafe
                .defer(p.completeDiscard(Result.succeed(Session.noStats)))
                .andThen(parked)
            }
      }

    def settle(using Frame): Unit < Async = settleWith(-1)

    /** [[settle]], with `sleepers` as in [[advance]]. */
    private def settleWith(sleepers: Int)(using Frame): Unit < Async =
      // `quiet`: the batch count at which every running body was seen parked
      // on the clock. One more drain with no new batch means it is still so.
      Loop(-1L) { quiet =>
        drained.andThen(Sync.defer(view.counts)).map { c =>
          if c.ended || c.busy == 0 || c.batches == quiet then Loop.done(())
          else
              val idle = if sleepers >= 0 then sleepers else c.sleepers
              parkedOrNews(idle + c.busy, c.batches)
                .map(parked => Loop.continue(if parked then c.batches else -1L))
        }
      }

    def advance(d: Duration, sleepers: Int = -1)(using Frame): Unit < Async =
        // Every running source and task is parked on the clock again. The
        // counts are read again on news: a source that ended no longer counts.
        def parked: Unit < Async =
          Loop.foreach {
            Sync.defer(view.counts).map { c =>
              val n =
                if c.ended then 0
                else (if sleepers >= 0 then sleepers else c.sleepers) + c.busy
              if n <= 0 then Loop.done
              else
                  parkedOrNews(n, c.batches)
                    .map(p => if p then Loop.done else Loop.continue)
            }
          }
        parked
          .andThen(Loop(d) { remaining =>
            if remaining <= Duration.Zero then Loop.done(())
            else
                val step = remaining.min(LiveRun.Step)
                control
                  .advance(step, Duration.Zero)
                  .andThen(parked)
                  .andThen(settleWith(sleepers))
                  .andThen(Loop.continue(remaining.minusOrZero(step)))
          })
          .andThen(settleWith(sleepers))
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

    def error(using Frame): Option[TuiError] < Sync =
      Sync.defer(Option(view.error))
end LiveRun

private[testkit] object LiveRun:
    private val Step: Duration = 1.millis

    /** An event as the terminal sends it (as `TuiTest` and `ScriptedInput`). */
    private def sent(e: Event): Item = e match
        case k: Key => Key.sent(k)
        case other  => other

    /** Runs `test` against `app` on a fresh live run; stops the app after. The
      * result is the test's, unless the app failed: then the app's failure
      * (added as suppressed to a failure of the test).
      */
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
            Runner.startOrFail(live, terminal, None, view).map { handle =>
              val run = new LiveRun(fixed, handle, control, view)
              Sync.ensure(handle.kill) {
                run.settle
                  .andThen(Abort.run[Throwable](test(run)))
                  .map { result =>
                    handle.stop
                      .andThen(handle.result)
                      .map(ended => outcome(result, ended))
                  }
              }
            }
          }
        }
    end apply

    private def outcome[A](
        test: Result[Throwable, A],
        app: Result[TuiError, Any],
    )(using Frame): A < Abort[Throwable] =
      app.failureOrPanic match
          case Absent     => Abort.get(test)
          case Present(e) =>
            test.failureOrPanic match
                case Absent     => Abort.fail(e)
                case Present(t) =>
                  if t ne e then t.addSuppressed(e)
                  Abort.get(test)
    end outcome
end LiveRun
