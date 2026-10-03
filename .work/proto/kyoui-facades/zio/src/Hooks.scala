package tui4s.interop.zio.ui

import _root_.zio.Scope
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.stream.ZStream
import tui4s.interop.zio.ui.internal.Bridge

// ---- hooks style: a component is a ZIO[Scope, Nothing, HtmlNode] ----

/** A piece of state for a component. kyo-ui can bind it (`render`, `value`, `checked`, a text child). Its state is the
  * cell that kyo-ui reads: no copy, no lag.
  */
// claude 2026-10-03: changed useSignal: it gives a SignalRef (one cell) instead of a SubscriptionRef (two cells)
def useSignal[A](initial: A)(using CanEqual[A, A]): UIO[SignalRef[A]] =
    Bridge.useSignal(initial)

/** A reactive value that follows a ZStream for the lifetime of the component (a clock, a log tail, ...). */
def useStream[A](stream: ZStream[Any, Nothing, A], initial: A)(using CanEqual[A, A]): ZIO[Scope, Nothing, Signal[A]] =
    Bridge.useStream(stream, initial).map(Signal.fromKyo)

// ---- Component class style: the runner allocates the state at mount ----

/** A component with a type member for its state. `mount` allocates the state (a `useSignal`) and builds the view. */
abstract class Component:
    type State
    def initial: State
    // claude 2026-10-03: changed the state type of view to SignalRef
    def view(state: SignalRef[State]): HtmlNode

    final def mount(using CanEqual[State, State]): ZIO[Scope, Nothing, HtmlNode] =
        useSignal(initial).map(view)
end Component
