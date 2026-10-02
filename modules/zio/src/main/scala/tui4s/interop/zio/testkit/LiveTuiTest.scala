package tui4s.interop.zio.testkit

import _root_.kyo.Present
import _root_.tui4s.interop.zio.App
import _root_.tui4s.interop.zio.Event
import _root_.tui4s.interop.zio.Key
import _root_.tui4s.interop.zio.Stats
import _root_.tui4s.interop.zio.TuiError
import _root_.tui4s.interop.zio.internal.AmbientZIOs
import _root_.tui4s.interop.zio.internal.ZioLive
import _root_.tui4s.testkit.Screen
import _root_.zio.Exit
import _root_.zio.Trace
import _root_.zio.UIO
import _root_.zio.ZIO

/** The live test kit of the ZIO facade (SPEC 4.4): the real loop of an app
  * (sources and tasks on fibers, the supervisor, the frame rules) on a test
  * terminal, as ZIO effects. No terminal, no input thread: the test sends the
  * input. For the synchronous level, use `tui4s.testkit.TuiTest`, the same for
  * every ecosystem.
  *
  * {{{
  * import tui4s.interop.zio.*
  * import tui4s.interop.zio.testkit.*
  *
  * LiveTuiTest(CounterApp, 40, 5) { t =>
  *   t.press('+', '+') *> t.state
  * } // ZIO[Any, Throwable, Int]: 2
  * }}}
  *
  * The app's sources run on the runtime of the fiber that runs the test, with
  * its clock: real time, or a zio-test `TestClock` that the test adjusts
  * (`TestClock.adjust(period)` after `TestClock.sleeps` shows the source
  * asleep). The frame cap is off: each changed batch draws.
  */
object LiveTuiTest:
    /** Starts `app` on a `width` x `height` test terminal, settles, runs
      * `test`, then stops the app. The result is the test's, unless the app
      * failed (user code threw, a source failed): then the run fails with the
      * app's `TuiError` (added as suppressed to a failure of the test).
      * Interrupting the test stops the app.
      */
    def apply[S, A](app: App[S], width: Int = 80, height: Int = 24)(
        test: LiveTest[S] => ZIO[Any, Throwable, A],
    )(using Trace): ZIO[Any, Throwable, A] =
      ZIO.acquireReleaseWith(
        AmbientZIOs.run(ZioLive.start(app, width, height)),
      )(run => AmbientZIOs.run(run.kill)) { run =>
        test(LiveRun(run)).exit.flatMap { tested =>
          AmbientZIOs.run(run.stop).flatMap {
            case Present(e) => failed(tested, e)
            case _          => tested
          }
        }
      }

    /** The test's exit, with the app's failure `e`. */
    private def failed[A](tested: Exit[Throwable, A], e: Throwable)(using
        Trace,
    ): ZIO[Any, Throwable, A] =
      tested match
          case Exit.Success(_)     => ZIO.fail(e)
          case Exit.Failure(cause) =>
            cause.failureOption
              .orElse(cause.dieOption)
              .foreach(t => if t ne e then t.addSuppressed(e))
            ZIO.failCause(cause)
end LiveTuiTest

/** A live app under test ([[LiveTuiTest]]). Each input method waits until its
  * effect is folded and drawn ([[settle]]).
  */
trait LiveTest[S]:
    /** Presses each key in its own batch, like a person typing, and settles
      * after each. A `Char` is `Key.Char(c)`.
      */
    def press(keys: (Key | Char)*)(using Trace): UIO[Unit]

    /** Sends `events` in order (usually in one batch), then settles. A mouse or
      * focus event dies with an `AssertionError` when the app's options do not
      * turn it on: the runtime would not report it.
      */
    def send(events: Event*)(using Trace): UIO[Unit]

    /** Types `text`, one batch per grapheme ("\n" is Enter, "\t" is Tab),
      * settling after each.
      */
    def typeText(text: String)(using Trace): UIO[Unit]

    /** Returns when every item put before the call is folded and drawn, and no
      * `task` source runs: it waits for running tasks to end. A task that never
      * ends makes it wait; give the test a timeout.
      */
    def settle(using Trace): UIO[Unit]

    /** The state after the last batch (after the end: the state the app stopped
      * with).
      */
    def state(using Trace): UIO[S]

    /** The last drawn frame. */
    def screen(using Trace): UIO[Screen]

    /** The counters after the last batch. */
    def stats(using Trace): UIO[Stats]

    /** The labels of the declared standing sources after the last batch. */
    def sources(using Trace): UIO[Seq[String]]

    /** True after the app stopped (`Quit`, Ctrl-C, a failure). */
    def quit(using Trace): UIO[Boolean]

    /** The failure that ended the app, if it ended with one: `AppFailed` (user
      * code threw) or `SourceFailed`.
      */
    def error(using Trace): UIO[Option[TuiError]]
end LiveTest

/** The live kit on the Kyo side of the facade. */
private final class LiveRun[S](run: ZioLive[S]) extends LiveTest[S]:
    def press(keys: (Key | Char)*)(using Trace): UIO[Unit] =
      AmbientZIOs.run(run.press(keys))

    def send(events: Event*)(using Trace): UIO[Unit] =
      AmbientZIOs.run(run.send(events))

    def typeText(text: String)(using Trace): UIO[Unit] =
      AmbientZIOs.run(run.typeText(text))

    def settle(using Trace): UIO[Unit] = AmbientZIOs.run(run.settle)

    def state(using Trace): UIO[S] = ZIO.succeed(run.view.state)

    def screen(using Trace): UIO[Screen] =
      ZIO.succeed {
        val s = run.view.screen
        if s == null then Screen(Vector.empty, Vector.empty, None) else s
      }

    def stats(using Trace): UIO[Stats] = ZIO.succeed(run.view.stats)

    def sources(using Trace): UIO[Seq[String]] = ZIO.succeed(run.view.sources)

    def quit(using Trace): UIO[Boolean] = ZIO.succeed(run.view.counts.ended)

    def error(using Trace): UIO[Option[TuiError]] =
      ZIO.succeed(Option(run.view.error))
end LiveRun
