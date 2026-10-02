package tui4s.interop.cats.internal

import _root_.cats.effect.std.Dispatcher
import kyo.*

/** The Dispatcher of the caller of `Tui.run`, in an inheritable Kyo `Local`
  * (the pattern of tui4s-zio's AmbientZIOs). The source fibers that the app
  * starts inherit it, so their `F` effects start on the runtime that called
  * `Tui.run`, never on a global one.
  *
  * Rule: one `F` per app. The Dispatcher has the runner's `F`; a source built
  * for another `F` is a run-time error.
  */
private[tui4s] object AmbientDispatcher:
    private val local: Local[Maybe[Dispatcher[?]]] =
      Local.init(Maybe.empty[Dispatcher[?]])

    /** Runs `v` with `d` as the ambient Dispatcher. */
    def let[A, S](d: Dispatcher[?])(v: A < S)(using Frame): A < S =
      local.let(Present(d))(v)

    /** Runs `f` with the ambient Dispatcher; fails when there is none (a Cats
      * source in an app that a Kyo runner runs).
      */
    def use[F[_], A](f: Dispatcher[F] => A < (Abort[Throwable] & Async))(using
        Frame,
    ): A < (Abort[Throwable] & Async) =
      local.use {
        case Present(d) => f(d.asInstanceOf[Dispatcher[F]])
        case Absent     =>
          Abort.fail(
            IllegalStateException(
              "a tui4s.interop.cats source needs the Cats Effect runner: " +
                "run the app with tui4s.interop.cats.Tui.run, Tui.resource " +
                "or TuiApp",
            ),
          )
      }
end AmbientDispatcher
