package tui4s

import kyo.*
import tui4s.internal.ComponentSpec
import tui4s.internal.Tui4sTrack

/** Hooks: the first style of tui4s components (the second is [[Component]]).
  * They are plain Kyo effects: a component is any function that returns
  * `UI < Sync` or `UI < (Async & Scope)`.
  *
  * {{{
  * def counter(label: String): UI < Sync =
  *     for count <- useSignal(0)
  *     yield div(span(label), span(count.map(_.toString)), button("+").onClick(count.updateAndGet(_ + 1)))
  * }}}
  *
  * At the top of an app, sequence the components in a `for`: they run once, and
  * their effects live as long as the `Scope` of the app. Inside a reactive part
  * of the tree (a `render`, a `foreach` row, a `when`), wrap the component in
  * [[component]]: the runner runs its body when the node mounts, in a `Scope`
  * that closes when the node unmounts. A node inside a `when` that hides it
  * unmounts: its state is dropped, and it starts fresh when it comes back. The
  * frame of the event that mounts a hook component waits for its first UI at
  * most `TuiOptions.pendingTimeout`; then it comes without it, and the UI comes
  * in a later frame.
  *
  * The fibers that the hooks start (`useEffect`, `useInterval`, `useHold`,
  * `useFold`) end when their `Scope` closes, and in a run they also end with
  * the run.
  */
// claude 2026-10-13: changed (C4): the Scaladoc of the final shape (the two
// styles, the mount, the pending frame, the fibers end with the run)
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
      * (the app, or the [[component]] mount). The effect has a `Scope` of its
      * own: when the fiber ends (the enclosing `Scope` closes, or the run
      * ends), it closes, so a `Scope.ensure` inside the effect is its cleanup.
      * There is no cleanup function to return.
      */
    // claude 2026-10-13: changed (C4): the Scaladoc (the cleanup)
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
      * its `Scope` when it unmounts. The identity of the node is its call site
      * and `key`: while a node of the same identity stays at the same position,
      * the mount goes on (its state, its effects and its UI stay, and `body`
      * does not run again). Give a `key` when one call site shows different
      * things at one position (for example `component(user.id)(...)`): another
      * key mounts fresh. In another kyo-ui runner, the node is empty.
      */
    // claude 2026-10-13: changed (C4): the Scaladoc (the identity and the key)
    def component(
        key: String,
    )(body: => UI < (Async & Scope))(using frame: Frame): UI =
      ComponentSpec.node(
        ComponentSpec.Hook(s"${frame.position.show}#$key", () => body),
        UI.empty,
      )

    /** [[component]] with no key: the identity is the call site. */
    // claude 2026-10-13: changed (C4): the Scaladoc
    def component(body: => UI < (Async & Scope))(using frame: Frame): UI =
      component("")(body)
end Hooks
