package tui4s.cats.internal

import _root_.cats.effect.std.Dispatcher
import kyo.*

/** The caller's Dispatcher, in an inheritable Kyo Local (the AmbientZIOs pattern): forked source fibers start
  * their F effects on the runtime that called `Tui.run`. Rule: one `F` per app.
  */
private[tui4s] object AmbientDispatcher:
  private val local: Local[Maybe[Dispatcher[?]]] = Local.init(Maybe.empty[Dispatcher[?]])

  def let[A, S](d: Dispatcher[?])(v: A < S)(using Frame): A < S = local.let(Present(d))(v)

  def use[F[_], A](f: Dispatcher[F] => A < (Abort[Throwable] & Async))(using Frame): A < (Abort[Throwable] & Async) =
    local.use {
      case Present(d) => f(d.asInstanceOf[Dispatcher[F]])
      case Absent =>
        Abort.fail(IllegalStateException("tui4s-cats: no Dispatcher in scope; run the app with tui4s.cats.Tui.run"))
    }
end AmbientDispatcher
