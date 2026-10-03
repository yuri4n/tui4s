package tui4s.interop.zio.ui

import _root_.kyo.<
import _root_.kyo.Async
import _root_.kyo.Fiber as KFiber
import _root_.kyo.Frame
import _root_.kyo.Scope as KScope
import _root_.zio.FiberRef
import _root_.zio.Promise
import _root_.zio.Scope
import _root_.zio.UIO
import _root_.zio.Unsafe
import _root_.zio.ZIO
import tui4s.interop.zio.ui.internal.AmbientZIOs
import tui4s.kyoui.FrameStats
import tui4s.kyoui.Headless as KHeadless
import tui4s.kyoui.Tui as KTui

/** The ZIO entry point of the terminal: runs a facade app on the tui4s kyo-ui runner.
  *
  * {{{
  * object CounterMain extends ZIOAppDefault:
  *     def run = Tui.run(CounterApp.app)
  * }}}
  *
  * The app is the facade value (`ZIO[Scope, Nothing, UI]`) with no change. Its handlers run on the runtime of the fiber
  * that calls `run` (its FiberRefs, its executor), through the facade's `AmbientZIOs`. Ctrl-C quits; `Tui.quit` in a
  * handler quits too.
  */
// claude 2026-10-03: created the ZIO terminal entry point of the integration prototype (glue between the ZIO facade
// and the kyoui runner; no file of the two prototypes changed)
object Tui:

    /** The quit signal of the run that is active in this fiber (and in the fibers it forks: the handlers). */
    // claude 2026-10-03: created quitRef: a FiberRef, because a handler runs on a ZIO fiber that the facade forks from the
    // runtime captured by `AmbientZIOs.run`; that runtime carries this FiberRef, but not the Kyo Local of the runner.
    private val quitRef: FiberRef[Option[Promise[Nothing, Unit]]] =
        Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Option.empty[Promise[Nothing, Unit]]))

    /** Runs the app in the real terminal until Ctrl-C or [[quit]]. The terminal is restored when the run ends, also on
      * an interrupt.
      */
    // claude 2026-10-03: created run: build the app in a ZIO Scope, then run the kyo-ui value on the terminal runner
    // inside AmbientZIOs.run (so every ZIO handler runs on the caller's runtime)
    def run(app: ZIO[Scope, Nothing, UI]): UIO[Unit] =
        ZIO.scoped {
            for
                quit <- Promise.make[Nothing, Unit]
                _ <- quitRef.locally(Some(quit)) {
                    for
                        ui <- app
                        _  <- AmbientZIOs.run(KTui.run(withQuit(quit, unsafeToKyo(ui))))
                    yield ()
                }
            yield ()
        }

    /** Ends the run of this app. Call it in a handler: `button("Quit").onClick(Tui.quit)`. Outside a run it does nothing. */
    // claude 2026-10-03: created quit: completes the promise of the active run (found through quitRef)
    val quit: UIO[Unit] =
        quitRef.get.flatMap {
            case Some(p) => p.succeed(()).unit
            case None    => ZIO.unit
        }

    /** The UI, plus one Kyo fiber that waits for the quit promise and then calls the runner's quit. The fiber is started
      * while the runner builds the UI, so it sees the runner's quit action (a Kyo `Local`), and the runner's `Scope`
      * stops it when the run ends.
      */
    // claude 2026-10-03: created withQuit (shared by run and the headless test kit)
    private[ui] def withQuit(quit: Promise[Nothing, Unit], ui: _root_.kyo.UI)(using Frame): _root_.kyo.UI < (Async & KScope) =
        KFiber.init(AmbientZIOs.get(quit.await).andThen(KTui.quit)).andThen(ui)

    // claude 2026-10-03: created setQuit (the test kit sets the quit signal like run does)
    private[ui] def setQuit[R, E, A](quit: Promise[Nothing, Unit])(z: ZIO[R, E, A]): ZIO[R, E, A] =
        quitRef.locally(Some(quit))(z)
end Tui

/** A headless run of a facade app on the TERMINAL runner (a jatatui `TestBackend`, no real terminal): send keys, read the
  * screen text. Each key returns the runner's `FrameStats` of its frame.
  */
// claude 2026-10-03: created the ZIO test kit over the runner's Headless (the facade's own Headless uses kyo-ui's HTML
// renderer; this one uses the terminal runner: layout, focus, key dispatch, paint)
final class TuiHeadless private (k: KHeadless):
    // claude 2026-10-03: created the key and screen methods (each one is a Kyo call run through AmbientZIOs)
    def key(key: Keyboard, ctrl: Boolean = false, shift: Boolean = false): UIO[FrameStats] =
        AmbientZIOs.run(k.key(key, ctrl, shift))
    def char(c: Char): UIO[FrameStats]           = AmbientZIOs.run(k.char(c))
    def typeText(s: String): UIO[Unit]           = AmbientZIOs.run(k.typeText(s)).unit
    def screen: UIO[String]                      = AmbientZIOs.run(k.screen)
    def lines: UIO[Vector[String]]               = AmbientZIOs.run(k.lines)
    def finished: UIO[Boolean]                   = AmbientZIOs.run(k.finished)
    def awaitEnd(timeout: _root_.zio.Duration): UIO[Boolean] =
        AmbientZIOs.run(k.awaitEnd(_root_.kyo.Duration.fromJava(timeout)))
end TuiHeadless

object TuiHeadless:
    /** Builds the app and mounts it on a headless terminal runner of `width` x `height` cells. The runner fiber lives as
      * long as the ZIO `Scope`. Handlers run on the runtime of the fiber that calls `start`.
      */
    // claude 2026-10-03: created start: the same steps as Tui.run, with the runner's Headless.start instead of Tui.run
    def start(app: ZIO[Scope, Nothing, UI], width: Int = 100, height: Int = 30): ZIO[Scope, Nothing, TuiHeadless] =
        for
            quit    <- Promise.make[Nothing, Unit]
            started <- Promise.make[Nothing, KHeadless]
            _ <- Tui.setQuit(quit) {
                for
                    ui <- app
                    _ <- AmbientZIOs.run(
                        KScope.run(
                            KHeadless.start(width, height)(Tui.withQuit(quit, unsafeToKyo(ui)))
                                .map(h => AmbientZIOs.get(started.succeed(h)).andThen(Async.never))
                        )
                    ).forkScoped
                yield ()
            }
            k <- started.await
            // claude 2026-10-03: changed: quit the run and wait for its end BEFORE the scope interrupts the runner fiber.
            // An interrupted runner leaks its region watchers (they are unscoped fibers; see REPORT.md, leak probe).
            _ <- ZIO.addFinalizer(AmbientZIOs.run(k.quit.andThen(k.awaitEnd(_root_.kyo.Duration.fromJava(java.time.Duration.ofSeconds(5))))))
        yield new TuiHeadless(k)
end TuiHeadless
