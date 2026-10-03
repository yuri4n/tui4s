package tui4s.interop.zio.ui

import _root_.kyo.Async
import _root_.kyo.Scope as KScope
import _root_.kyo.tui4sproto.Headless as KHeadless
import _root_.zio.Promise
import _root_.zio.Scope
import _root_.zio.UIO
import _root_.zio.ZIO
import tui4s.interop.zio.ui.internal.AmbientZIOs

/** A headless runner for tests (a stand-in for the terminal runner): kyo-ui's own mount, dispatch and HTML render. */
final class Headless private (k: KHeadless):
    def text: UIO[String]                        = AmbientZIOs.run(k.text)
    def click(id: String): UIO[Unit]             = AmbientZIOs.run(k.send(KHeadless.Event.Click(id)))
    def input(id: String, value: String): UIO[Unit] = AmbientZIOs.run(k.send(KHeadless.Event.Input(id, value)))
    def keyDown(id: String, key: String): UIO[Unit] = AmbientZIOs.run(k.send(KHeadless.Event.KeyDown(id, key)))
    def check(id: String, on: Boolean): UIO[Unit]   = AmbientZIOs.run(k.send(KHeadless.Event.Checked(id, on)))
end Headless

object Headless:
    /** Builds the app, mounts it once, and starts the runner fiber on the CALLER's runtime: every handler of the app
      * runs with the FiberRefs and the executor of the fiber that calls `mount`.
      */
    def mount(app: ZIO[Scope, Nothing, UI]): ZIO[Scope, Nothing, Headless] =
        for
            ui      <- app
            started <- Promise.make[Nothing, KHeadless]
            _ <- AmbientZIOs.run(
                KScope.run(
                    KHeadless.mount(unsafeToKyo(ui)).map(h => AmbientZIOs.get(started.succeed(h)).andThen(Async.never))
                )
            ).forkScoped
            k <- started.await
        yield new Headless(k)
end Headless
