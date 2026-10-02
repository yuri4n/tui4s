package tui4s.interop.cats.testkit

import _root_.cats.effect.kernel.Async
import _root_.cats.effect.std.Dispatcher
import _root_.jatatui.core.terminal.Terminal
import _root_.java.util.concurrent.TimeUnit
import _root_.kyo.*
import _root_.scala.concurrent.duration.FiniteDuration
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
import _root_.tui4s.interop.cats.internal.AmbientDispatcher
import _root_.tui4s.interop.cats.internal.CatsBridge
import _root_.tui4s.testkit.RecordingControl
import _root_.tui4s.testkit.Screen

/** A live app under test ([[LiveTuiTest]]): the real loop, with the real source
  * and task fibers, on a test terminal, in real time. Each input method returns
  * after its effect is folded and drawn ([[settle]]).
  *
  * @tparam F
  *   the effect of the test and of the app's sources
  * @tparam S
  *   the state of the app
  */
trait LiveTest[F[_], S]:
    /** Presses each key in its own batch, like a person typing, and settles
      * after each.
      */
    def press(keys: (Key | Char)*): F[Unit]

    /** Sends `events` in order (usually in one batch), then settles. */
    def send(events: Event*): F[Unit]

    /** Types `text`, one batch per grapheme ("\n" is Enter, "\t" is Tab),
      * settling after each.
      */
    def typeText(text: String): F[Unit]

    /** Returns when every item put before the call is folded and drawn, and no
      * `task` runs. A task that never ends makes it wait: give the test a
      * timeout. Sources that run on a timer (`every`, `poll`) do not make it
      * wait: time is real, so wait for their values with [[eventually]].
      */
    def settle: F[Unit]

    /** Settles, then reads `f` of the state until it gives a value, for at most
      * `timeout`; fails with the last state otherwise. For values that a source
      * sends in real time.
      */
    def eventually[B](timeout: FiniteDuration = LiveRun.Wait)(
        f: S => Option[B],
    ): F[B]

    /** The state after the last batch (after the end: the state the app stopped
      * with).
      */
    def state: F[S]

    /** The last drawn frame. */
    def screen: F[Screen]

    /** The counters after the last batch. */
    def stats: F[Stats]

    /** The labels of the declared standing sources after the last batch. */
    def sources: F[Seq[String]]

    /** True after the app stopped. */
    def quit: F[Boolean]

    /** The failure that ended the app, if it ended with one. [[LiveTuiTest]]
      * also fails with it after the test.
      */
    def error: F[Option[TuiError]]
end LiveTest

/** The live test kit of the Cats Effect facade: the real loop (input, source
  * and task fibers, supervisor) on a test terminal, driven from `F`. The app's
  * `F` effects run on the runtime that runs the test. There is no frame cap and
  * no virtual time: sources sleep on the real clock.
  *
  * {{{
  * test("the counter counts") {
  *   LiveTuiTest[IO](CounterApp, 40, 5) { t =>
  *     t.press('+', '+') *> t.state
  *   }.map(n => assertEquals(n, 2))
  * }
  * }}}
  *
  * Most tests do not need it: the synchronous `tui4s.testkit.TuiTest` checks
  * the same fold, frames and screen, and its `emit` sends source values.
  */
object LiveTuiTest:
    /** The kit for the effect `F`: `LiveTuiTest[IO](app) { t => ... }`. */
    def apply[F[_]]: Of[F] = new Of[F]

    /** Runs live tests in `F`. */
    final class Of[F[_]] private[testkit] ():
        /** Starts `app` on a `width` x `height` test terminal, settles, runs
          * `test`, then stops the app. The result is the test's, unless the app
          * failed: then the app's [[TuiError]] (added as suppressed to a
          * failure of the test). A cancel stops the app.
          */
        def apply[S, A](app: App[S], width: Int = 80, height: Int = 24)(
            test: LiveTest[F, S] => F[A],
        )(using F: Async[F]): F[A] =
          LiveTuiTest.run(app, width, height)(test)
    end Of

    private def run[F[_], S, A](app: App[S], width: Int, height: Int)(
        test: LiveTest[F, S] => F[A],
    )(using F: Async[F]): F[A] =
        val fixed = app.options.copy(fps = 0)
        val live  = new App[S]:
            def init: S                                    = app.init
            def view(state: S): View                       = app.view(state)
            def update(state: S, event: Event): Next[S]    =
              app.update(state, event)
            override def sources(state: S): Seq[Source[S]] = app.sources(state)
            override def options: TuiOptions               = fixed
        Dispatcher.parallel[F](await = false).use { d =>
          val control = RecordingControl(width, height)
          val view    = LiveView[S](control)
          val start   = CatsBridge.runF(
            AmbientDispatcher.let(d)(
              Runner.startOrFail(live, control, None, view),
            ),
          )
          // The release also runs on a cancel: it interrupts the app and
          // waits for its end (the terminal is released then).
          F.bracket(start) { handle =>
            val kit = LiveRun[F, S](fixed, handle, view)
            F.flatMap(F.attempt(F.productR(kit.settle)(test(kit))))(result =>
              F.flatMap(
                CatsBridge.runF(handle.stop.andThen(handle.result)),
              )(ended => outcome(result, ended)),
            )
          }(handle =>
            F.void(CatsBridge.runF(handle.kill.andThen(handle.result))),
          )
        }
    end run

    private def outcome[F[_], A](
        test: Either[Throwable, A],
        app: Result[TuiError, Any],
    )(using F: Async[F]): F[A] =
      app.failureOrPanic match
          case Absent     => F.fromEither(test)
          case Present(e) =>
            test match
                case Right(_) => F.raiseError(e)
                case Left(t)  =>
                  if t ne e then t.addSuppressed(e)
                  F.raiseError(t)
    end outcome
end LiveTuiTest

/** What the kit reads of one draw decision, written at once. */
private[testkit] final case class LiveCounts(
    batches: Long,
    busy: Int,
    ended: Boolean,
)

/** What the kit reads: copies made by the render fiber after each batch. */
private[testkit] final class LiveView[S](control: RecordingControl)
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

/** The kit over a started run. */
private[testkit] final class LiveRun[F[_], S](
    options: TuiOptions,
    handle: TuiHandle[S],
    view: LiveView[S],
)(using F: Async[F])
    extends LiveTest[F, S]:

    private def put(item: Item)(using Frame): Unit < kyo.Async =
      Abort.run[Closed](handle.inbox.put(item)).unit

    def press(keys: (Key | Char)*): F[Unit] =
      CatsBridge.runF(
        Kyo.foreachDiscard(keys)(k =>
          put(Key.sent(Bind.toKey(k))).andThen(settled),
        ),
      )

    def send(events: Event*): F[Unit] =
      F.productR(F.delay(events.foreach(LiveRun.reported(options, _))))(
        CatsBridge.runF(
          Abort
            .run[Closed](handle.inbox.putBatch(events.map(LiveRun.sent)))
            .andThen(settled),
        ),
      )

    def typeText(text: String): F[Unit] =
      CatsBridge.runF(
        Kyo.foreachDiscard(LiveRun.typed(text))(e =>
          put(LiveRun.sent(e)).andThen(settled),
        ),
      )

    def settle: F[Unit] = CatsBridge.runF(settled)

    def eventually[B](timeout: FiniteDuration)(f: S => Option[B]): F[B] =
        def loop(deadline: FiniteDuration): F[B] =
          F.flatMap(F.productR(settle)(state)) { s =>
            f(s) match
                case Some(b) => F.pure(b)
                case None    =>
                  F.flatMap(F.monotonic) { now =>
                    if now > deadline then
                        F.raiseError(
                          AssertionError(
                            s"no value within $timeout; the state: $s",
                          ),
                        )
                    else F.productR(F.sleep(LiveRun.Poll))(loop(deadline))
                  }
          }
        F.flatMap(F.monotonic)(start => loop(start + timeout))
    end eventually

    /** Puts a probe that completes after the decision of more than `after`
      * batches; Absent when the app has ended (the inbox is closed).
      */
    private def probe(after: Long)(using
        Frame,
    ): Maybe[Promise.Unsafe[Stats, Any]] < kyo.Async =
      Sync.Unsafe
        .defer(Promise.Unsafe.init[Stats, Any]())
        .map { p =>
          Abort.run[Closed](handle.inbox.put(Probe(p, after))).map {
            case Result.Success(_) => Present(p)
            case _                 => Absent
          }
        }

    /** Waits for the decision after more than `after` batches. */
    private def news(after: Long)(using Frame): Unit < kyo.Async =
      probe(after).map {
        case Present(p) => p.safe.get.unit
        case Absent     => Kyo.unit
      }

    /** Everything put before is folded and drawn, and no task runs. */
    private def settled(using Frame): Unit < kyo.Async =
      Loop.foreach {
        news(-1).andThen(Sync.defer(view.counts)).map { c =>
          if c.ended || c.busy == 0 then Loop.done
          else news(c.batches).andThen(Loop.continue)
        }
      }

    def state: F[S] = F.delay(view.state)

    def screen: F[Screen] =
      F.delay {
        val s = view.screen
        if s == null then Screen(Vector.empty, Vector.empty, None) else s
      }

    def stats: F[Stats] = F.delay(view.stats)

    def sources: F[Seq[String]] = F.delay(view.sources)

    def quit: F[Boolean] = F.delay(view.counts.ended)

    def error: F[Option[TuiError]] = F.delay(Option(view.error))
end LiveRun

private[testkit] object LiveRun:
    /** The default time of [[LiveTest.eventually]]. */
    val Wait: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS)

    /** How often [[LiveTest.eventually]] reads the state. */
    val Poll: FiniteDuration = FiniteDuration(10, TimeUnit.MILLISECONDS)

    /** An event as the terminal sends it. */
    def sent(e: Event): Item = e match
        case k: Key => Key.sent(k)
        case other  => other

    /** The events of typing `text`, one per grapheme. */
    def typed(text: String): Vector[Event] =
      TextField.graphemes(text).map { g =>
        if g.length == 1 then Key.Char(g.charAt(0))
        else if g == "\r\n" then Key.Enter
        else Paste(g)
      }

    /** Fails the test for an event that the runtime does not report with the
      * app's options: such a test would pass, and the app would not work.
      */
    def reported(options: TuiOptions, event: Event): Unit = event match
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
end LiveRun
