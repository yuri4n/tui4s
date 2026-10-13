package tui4s

import kyo.*
import tui4s.internal.ComponentSpec
import tui4s.internal.Tui4sTrack

/** Hook-style helpers. They are plain Kyo effects: a "component" is any
  * function that returns `UI < (Async & Scope)`.
  *
  * {{{
  * def counter(label: String): UI < Sync =
  *     for count <- useSignal(0)
  *     yield div(span(label), span(count.map(_.toString)), button("+").onClick(count.updateAndGet(_ + 1)))
  * }}}
  *
  * At the top of an app, sequence the components in a `for` (they run once).
  * Inside a reactive part of the tree (a `render`, a `foreach` row, a `when`),
  * wrap the effect in [[component]]: the runner runs it when the node mounts
  * and closes its `Scope` when the node unmounts.
  */
object Hooks:

    /** A piece of state: `Signal.initRef`. */
    def useSignal[A](
        initial: A,
    )(using CanEqual[A, A], Frame): SignalRef[A] < Sync =
      Signal.initRef(initial)

    /** A signal holding the last value of `stream` (`initial` before the first
      * one). The stream runs while the enclosing `Scope` is open.
      */
    def useHold[A](initial: A)(stream: => Stream[A, Async])(using
        CanEqual[A, A],
        Frame,
        Tag[Emit[Chunk[A]]],
    ): Signal[A] < (Async & Scope) =
      // claude 2026-10-13: changed (C4, RC-8): the fiber joins the gauge of the
      // run (R1 option b)
      for
          ref <- Signal.initRef(initial)
          _   <- Fiber.init(stream.foreach(ref.set)).map(Tui4sTrack(_))
      yield ref

    /** A signal folding `stream` into a state. */
    def useFold[A, S](
        initial: S,
    )(stream: => Stream[A, Async])(f: (S, A) => S)(using
        CanEqual[S, S],
        Frame,
        Tag[Emit[Chunk[A]]],
    ): Signal[S] < (Async & Scope) =
      // claude 2026-10-13: changed (C4, RC-8): the fiber joins the gauge of the
      // run (R1 option b)
      for
          ref <- Signal.initRef(initial)
          _   <- Fiber
                   .init(stream.foreach(a => ref.updateAndGet(s => f(s, a))))
                   .map(Tui4sTrack(_))
      yield ref

    /** Runs `effect` in a fiber that lives as long as the enclosing `Scope`
      * (the app, or the [[component]] mount).
      */
    def useEffect(effect: => Any < (Async & Scope))(using
        Frame,
    ): Unit < (Async & Scope) =
      // claude 2026-10-13: changed (C4, RC-8): the fiber joins the gauge of the
      // run (R1 option b)
      Fiber.init(Scope.run(effect)).map(Tui4sTrack(_))

    /** Runs `action` every `period` while the enclosing `Scope` is open. */
    def useInterval(period: Duration)(action: => Any < Async)(using
        Frame,
    ): Unit < (Async & Scope) =
      useEffect(Loop.forever(Async.sleep(period).andThen(action)))

    /** A component node: the runner runs `body` when the node mounts and closes
      * its `Scope` when it unmounts. The node keeps its state while the same
      * call site (and `key`) stays at the same position.
      */
    def component(
        key: String,
    )(body: => UI < (Async & Scope))(using frame: Frame): UI =
      ComponentSpec.node(
        ComponentSpec.Hook(s"${frame.position.show}#$key", () => body),
        UI.empty,
      )

    def component(body: => UI < (Async & Scope))(using frame: Frame): UI =
      component("")(body)
end Hooks
