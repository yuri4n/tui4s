package tui4s.interop.cats.ui

import _root_.cats.effect.kernel.Async as CEAsync
import _root_.cats.effect.kernel.Deferred
import _root_.cats.effect.kernel.Resource
import _root_.cats.effect.std.Dispatcher
import _root_.cats.effect.syntax.all.*
import _root_.cats.syntax.all.*
import _root_.kyo.Async
import _root_.kyo.Scope as KScope
import _root_.kyo.tui4sproto.Headless as KHeadless
import tui4s.interop.cats.ui.internal.AmbientDispatcher
import tui4s.interop.cats.ui.internal.CatsBridge

/** A headless runner for tests (a stand-in for the terminal runner): kyo-ui's own mount, dispatch and HTML render. */
final class Headless[F[_]] private (k: KHeadless)(using F: CEAsync[F]):
    def text: F[String]                            = CatsBridge.runF(k.text)
    def click(id: String): F[Unit]                 = CatsBridge.runF(k.send(KHeadless.Event.Click(id)))
    def input(id: String, value: String): F[Unit]  = CatsBridge.runF(k.send(KHeadless.Event.Input(id, value)))
    def keyDown(id: String, key: String): F[Unit]  = CatsBridge.runF(k.send(KHeadless.Event.KeyDown(id, key)))
    def check(id: String, on: Boolean): F[Unit]    = CatsBridge.runF(k.send(KHeadless.Event.Checked(id, on)))
end Headless

object Headless:
    /** Builds the app, mounts it once, and starts the runner fiber with a Dispatcher made from the CALLER's runtime
      * (await = false: F10 in the Kyo report). Every handler of the app runs through that Dispatcher.
      */
    def mount[F[_]](app: Resource[F, UI])(using F: CEAsync[F]): Resource[F, Headless[F]] =
        for
            dispatcher <- Dispatcher.parallel[F](await = false)
            ui         <- app
            started    <- Resource.eval(Deferred[F, KHeadless])
            _ <- CatsBridge.runF(
                AmbientDispatcher.let(dispatcher)(
                    KScope.run(
                        KHeadless.mount(unsafeToKyo(ui))
                            .map(h => CatsBridge.getF(started.complete(h).void)(using dispatcher))
                            .andThen(Async.never)
                    )
                )
            ).background
            k <- Resource.eval(started.get)
        yield new Headless[F](k)
end Headless
