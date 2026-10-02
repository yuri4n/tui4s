package tui4s.interop.zio.internal

import _root_.jatatui.core.terminal.Terminal
import _root_.kyo.*
import _root_.tui4s.App
import _root_.tui4s.Bind
import _root_.tui4s.Event
import _root_.tui4s.FocusGained
import _root_.tui4s.FocusLost
import _root_.tui4s.Key
import _root_.tui4s.Mouse
import _root_.tui4s.Next
import _root_.tui4s.Paste
import _root_.tui4s.Source
import _root_.tui4s.Stats
import _root_.tui4s.TextField
import _root_.tui4s.TuiError
import _root_.tui4s.TuiHandle
import _root_.tui4s.TuiOptions
import _root_.tui4s.View
import _root_.tui4s.internal.CountingBackend
import _root_.tui4s.internal.Engine
import _root_.tui4s.internal.Item
import _root_.tui4s.internal.Observer
import _root_.tui4s.internal.Probe
import _root_.tui4s.internal.Runner
import _root_.tui4s.internal.Session
import _root_.tui4s.testkit.RecordingControl
import _root_.tui4s.testkit.Screen

/** What the live kit reads of one draw decision, written at once. */
private[tui4s] final case class LiveCounts(
    batches: Long,
    busy: Int,
    ended: Boolean,
)

/** What the facade's live kit reads: copies that the render fiber makes after
  * each draw decision (the state, the counters, the declared sources, the
  * screen), so any fiber may read them.
  */
private[tui4s] final class LiveView[S](control: RecordingControl)
    extends Observer[S]:
    @volatile var state: S               = null.asInstanceOf[S]
    @volatile var stats: Stats           = Session.noStats
    @volatile var sources: Seq[String]   = Nil
    @volatile var counts: LiveCounts     = LiveCounts(0, 0, ended = false)
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
        counts = LiveCounts(stats.batches, engine.sup.busyTasks, ended = false)
        if drawn || screen == null then
            screen = Screen.of(control.backend.buffer(), engine.painter.cursor)
    end published

    /** The quitting batch's state is the last state; nothing runs any more. */
    override def ended(engine: Engine[S])(using AllowUnsafe): Unit =
        if engine.state != null then state = engine.state
        stats = engine.stats
        error = engine.error
        counts = LiveCounts(stats.batches, 0, ended = true)
end LiveView

/** The Kyo side of the facade's live kit (`LiveTuiTest`): the real loop on a
  * test terminal, with the real clock and the frame cap off.
  */
private[tui4s] final class ZioLive[S](
    options: TuiOptions,
    handle: TuiHandle[S],
    val view: LiveView[S],
):
    private def put(item: Item)(using Frame): Unit < Async =
      Abort.run[Closed](handle.inbox.put(item)).unit

    /** Each key in its own batch, settling after each. */
    def press(keys: Seq[Key | Char])(using Frame): Unit < Async =
      Kyo.foreachDiscard(keys)(k =>
        put(Key.sent(Bind.toKey(k))).andThen(settle),
      )

    /** The events in order (usually one batch), then settles. */
    def send(events: Seq[Event])(using Frame): Unit < Async =
      Sync
        .defer(events.foreach(ZioLive.reported(options, _)))
        .andThen(
          Abort.run[Closed](handle.inbox.putBatch(events.map(ZioLive.sent))),
        )
        .andThen(settle)

    /** One batch per grapheme, settling after each. */
    def typeText(text: String)(using Frame): Unit < Async =
      Kyo.foreachDiscard(ZioLive.typed(text))(e =>
        put(ZioLive.sent(e)).andThen(settle),
      )

    /** Puts a probe that completes after the decision of more than `after`
      * batches; Absent when the app has ended (the inbox is closed).
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

    private def await(after: Long)(using Frame): Unit < Async =
      probe(after).map {
        case Present(p) => p.safe.get.unit
        case Absent     => Kyo.unit
      }

    /** Returns when everything put before is folded and drawn, and no `task`
      * body runs (it waits for the running tasks to end, in real time).
      */
    def settle(using Frame): Unit < Async =
      Loop.foreach {
        await(-1).andThen(Sync.defer(view.counts)).map { c =>
          if c.ended || c.busy == 0 then Loop.done
          else await(c.batches).andThen(Loop.continue)
        }
      }

    /** Stops the app; the failure that ended it, if any. */
    def stop(using Frame): Maybe[Throwable] < Async =
      handle.stop.andThen(handle.result).map(_.failureOrPanic)

    /** Interrupts the app and waits for its end (its release has run): the
      * release of a test that was interrupted or failed. Nothing to do when the
      * app already ended.
      */
    def kill(using Frame): Unit < Async =
      handle.kill.andThen(handle.result).unit
end ZioLive

private[tui4s] object ZioLive:
    /** Starts `app` with the frame cap off, on a test terminal of `width` x
      * `height`, with no input thread; settled once.
      */
    def start[S](app: App[S], width: Int, height: Int)(using
        Frame,
    ): ZioLive[S] < (Async & Abort[TuiError]) =
        val fixed    = app.options.copy(fps = 0)
        val live     = new App[S]:
            def init: S                                    = app.init
            def view(state: S): View                       = app.view(state)
            def update(state: S, event: Event): Next[S]    =
              app.update(state, event)
            override def sources(state: S): Seq[Source[S]] = app.sources(state)
            override def options: TuiOptions               = fixed
        val terminal = RecordingControl(width, height)
        val view     = LiveView[S](terminal)
        Runner
          .startOrFail(live, terminal, None, view)
          .map(handle => ZioLive(fixed, handle, view))
          .map(run => run.settle.andThen(run))
    end start

    /** An event as the terminal sends it. */
    private def sent(e: Event): Item = e match
        case k: Key => Key.sent(k)
        case other  => other

    /** The events of typing `text`, one per grapheme ("\n" is Enter). */
    private def typed(text: String): Vector[Event] =
      TextField.graphemes(text).map { g =>
        if g.length == 1 then Key.Char(g.charAt(0))
        else if g == "\r\n" then Key.Enter
        else Paste(g)
      }

    /** Fails the test for an event that the runtime does not report with the
      * app's options: such a test would pass, and the app would not work.
      */
    private def reported(options: TuiOptions, event: Event): Unit =
      event match
          case _: Mouse if !options.mouse                      =>
            throw AssertionError(
              "a Mouse event needs TuiOptions(mouse = true): " +
                "the runtime reports no mouse events without it",
            )
          case FocusGained | FocusLost if !options.focusEvents =>
            throw AssertionError(
              s"$event needs TuiOptions(focusEvents = true): " +
                "the runtime reports no focus events without it",
            )
          case _                                               => ()
end ZioLive
