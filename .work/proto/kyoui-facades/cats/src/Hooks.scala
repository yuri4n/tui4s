package tui4s.interop.cats.ui

import _root_.cats.effect.kernel.Async as CEAsync
import _root_.cats.effect.kernel.Resource
import _root_.fs2.Stream as FStream
import _root_.fs2.concurrent.SignallingRef
import tui4s.interop.cats.ui.internal.Bridge

// ---- hooks style: a component is a Resource[F, HtmlNode] ----

/** A piece of state for a component: `useSignal[IO](0)`. kyo-ui can bind it (`render`, `value`, `checked`, a text
  * child). It is a real `SignallingRef`, and its state is the cell that kyo-ui reads: no copy, no lag.
  */
def useSignal[F[_]]: UseSignal[F] = new UseSignal[F]

final class UseSignal[F[_]] private[ui] ():
    def apply[A](initial: A)(using CEAsync[F], CanEqual[A, A]): Resource[F, SignallingRef[F, A]] =
        Bridge.useSignal[F, A](initial)

/** A reactive value that follows an fs2 Stream for the lifetime of the component. */
def useStream[F[_], A](stream: FStream[F, A], initial: A)(using CEAsync[F], CanEqual[A, A]): Resource[F, Signal[A]] =
    Bridge.useStream(stream, initial).map(Signal.fromKyo)

// ---- Component class style: the runner allocates the state at mount ----

/** A component with a type member for its state. `mount` allocates the state (a `useSignal`) and builds the view. */
abstract class Component[F[_]]:
    type State
    def initial: State
    def view(state: SignallingRef[F, State]): HtmlNode

    final def mount(using CEAsync[F], CanEqual[State, State]): Resource[F, HtmlNode] =
        useSignal[F](initial).map(view)
end Component
