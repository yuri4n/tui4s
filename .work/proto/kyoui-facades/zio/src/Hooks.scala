package tui4s.interop.zio.ui

import _root_.zio.Scope
import _root_.zio.ZIO
import _root_.zio.stream.SubscriptionRef
import _root_.zio.stream.ZStream
import tui4s.interop.zio.ui.internal.Bridge

// ---- hooks style: a component is a ZIO[Scope, Nothing, HtmlNode] ----

/** A piece of state for a component. It lives as long as the component's scope; kyo-ui can bind it (`render`,
  * `value`, `checked`, a text child).
  */
def useSignal[A](initial: A)(using CanEqual[A, A]): ZIO[Scope, Nothing, SubscriptionRef[A]] =
    Bridge.useSignal(initial)

/** A reactive value that follows a ZStream for the lifetime of the component (a clock, a log tail, ...). */
def useStream[A](stream: ZStream[Any, Nothing, A], initial: A)(using CanEqual[A, A]): ZIO[Scope, Nothing, Signal[A]] =
    Bridge.useStream(stream, initial).map(Signal.fromKyo)

// ---- Component class style: the runner allocates the state at mount ----

/** A component with a type member for its state. `mount` allocates the state (a `useSignal`) and builds the view. */
abstract class Component:
    type State
    def initial: State
    def view(state: SubscriptionRef[State]): HtmlNode

    final def mount(using CanEqual[State, State]): ZIO[Scope, Nothing, HtmlNode] =
        useSignal(initial).map(view)
end Component
