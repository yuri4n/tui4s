package tui4s.interop.cats.ui

import _root_.cats.effect.kernel.Async as CEAsync
import _root_.cats.effect.kernel.Deferred
import _root_.cats.effect.kernel.Resource
import _root_.cats.effect.std.Dispatcher
import _root_.cats.effect.syntax.all.*
import _root_.cats.syntax.all.*
import _root_.kyo.<
import _root_.kyo.Async
import _root_.kyo.Fiber as KFiber
import _root_.kyo.Frame
import _root_.kyo.Scope as KScope
import scala.concurrent.duration.FiniteDuration
import tui4s.interop.cats.ui.internal.AmbientDispatcher
import tui4s.interop.cats.ui.internal.CatsBridge
import tui4s.kyoui.FrameStats
import tui4s.kyoui.Headless as KHeadless
import tui4s.kyoui.Tui as KTui

/** The Cats Effect entry point of the terminal: runs a facade app on the tui4s kyo-ui runner, for any `F` with an
  * `Async`.
  *
  * {{{
  * object CounterMain extends IOApp.Simple:
  *     def run = Tui.run(CounterApp.app)
  * }}}
  *
  * The app is the facade value (`Resource[F, UI]`) with no change. Its handlers run through a `Dispatcher[F]` made from
  * the caller's runtime (`await = false`, see the facade's CatsBridge). Ctrl-C quits. [[runWithQuit]] gives the app an
  * `F[Unit]` that quits.
  */
// claude 2026-10-03: created the Cats terminal entry point of the integration prototype (glue between the Cats facade and
// the kyoui runner; no file of the two prototypes changed)
object Tui:

    /** Runs the app in the real terminal until Ctrl-C. The terminal is restored when the run ends, also on a cancel. */
    // claude 2026-10-03: created run
    def run[F[_]](app: Resource[F, UI])(using F: CEAsync[F]): F[Unit] =
        runWithQuit[F](_ => app)

    /** Runs the app in the real terminal until Ctrl-C or until a handler runs the `quit` action that the app gets. */
    // claude 2026-10-03: created runWithQuit: Dispatcher + quit Deferred + the app's Resource, then the kyo-ui value runs on
    // the terminal runner with the Dispatcher in the facade's ambient Local (AmbientDispatcher)
    def runWithQuit[F[_]](app: F[Unit] => Resource[F, UI])(using F: CEAsync[F]): F[Unit] =
        (for
            d    <- Dispatcher.parallel[F](await = false)
            quit <- Resource.eval(Deferred[F, Unit])
            ui   <- app(quit.complete(()).void)
        yield (d, quit, ui)).use { (d, quit, ui) =>
            CatsBridge.runF(AmbientDispatcher.let(d)(KTui.run(withQuit(d, quit, unsafeToKyo(ui)))))
        }

    /** The UI, plus one Kyo fiber that waits for the quit `Deferred` and then calls the runner's quit. It starts while the
      * runner builds the UI (so it sees the runner's quit action, a Kyo `Local`); the runner's `Scope` stops it.
      */
    // claude 2026-10-03: created withQuit (shared by runWithQuit and the headless test kit)
    private[ui] def withQuit[F[_]](d: Dispatcher[F], quit: Deferred[F, Unit], ui: _root_.kyo.UI)(using
        Frame
    ): _root_.kyo.UI < (Async & KScope) =
        KFiber.init(CatsBridge.getF(quit.get)(using d).andThen(KTui.quit)).andThen(ui)
end Tui

/** A headless run of a facade app on the TERMINAL runner (a jatatui `TestBackend`, no real terminal). */
// claude 2026-10-03: created the Cats test kit over the runner's Headless
final class TuiHeadless[F[_]] private (k: KHeadless)(using F: CEAsync[F]):
    // claude 2026-10-03: created the key and screen methods (each one is a Kyo call run through CatsBridge.runF)
    def key(key: Keyboard, ctrl: Boolean = false, shift: Boolean = false): F[FrameStats] =
        CatsBridge.runF(k.key(key, ctrl, shift))
    def char(c: Char): F[FrameStats]     = CatsBridge.runF(k.char(c))
    def typeText(s: String): F[Unit]     = CatsBridge.runF(k.typeText(s)).void
    def screen: F[String]                = CatsBridge.runF(k.screen)
    def lines: F[Vector[String]]         = CatsBridge.runF(k.lines)
    def finished: F[Boolean]             = CatsBridge.runF(k.finished)
    def awaitEnd(timeout: FiniteDuration): F[Boolean] =
        CatsBridge.runF(k.awaitEnd(_root_.kyo.Duration.fromJava(java.time.Duration.ofNanos(timeout.toNanos))))
end TuiHeadless

object TuiHeadless:
    /** Builds the app and mounts it on a headless terminal runner of `width` x `height` cells, for the lifetime of the
      * Resource.
      */
    // claude 2026-10-03: created start
    def start[F[_]](app: Resource[F, UI], width: Int = 100, height: Int = 30)(using F: CEAsync[F]): Resource[F, TuiHeadless[F]] =
        startWithQuit[F](_ => app, width, height)

    // claude 2026-10-03: created startWithQuit: the same steps as Tui.runWithQuit, with the runner's Headless.start
    def startWithQuit[F[_]](app: F[Unit] => Resource[F, UI], width: Int = 100, height: Int = 30)(using
        F: CEAsync[F]
    ): Resource[F, TuiHeadless[F]] =
        for
            d       <- Dispatcher.parallel[F](await = false)
            quit    <- Resource.eval(Deferred[F, Unit])
            ui      <- app(quit.complete(()).void)
            started <- Resource.eval(Deferred[F, KHeadless])
            _ <- CatsBridge.runF(
                AmbientDispatcher.let(d)(
                    KScope.run(
                        KHeadless.start(width, height)(Tui.withQuit(d, quit, unsafeToKyo(ui)))
                            .map(h => CatsBridge.getF(started.complete(h).void)(using d))
                            .andThen(Async.never)
                    )
                )
            ).background
            // claude 2026-10-03: changed: quit the run and wait for its end BEFORE the background fiber is cancelled. An
            // interrupted runner leaks its region watchers (they are unscoped fibers; see REPORT.md, leak probe).
            k <- Resource.make(started.get)(k =>
                CatsBridge.runF(k.quit.andThen(k.awaitEnd(_root_.kyo.Duration.fromJava(java.time.Duration.ofSeconds(5))))).void
            )
        yield new TuiHeadless[F](k)
end TuiHeadless
