package tui4s

import kyo.*
import tui4s.internal.{ComponentSpec, Tui4sTrack}

/** Hooks: the first style of tui4s components (the second is [[Component]]).
  * They are plain Kyo effects: a component is any function that returns
  * `UI < Sync`, or `UI < (Sync & Scope)` when it uses a hook that starts a
  * fiber (`useHold`, `useFold`, `useEffect`, `useInterval`). A component that
  * itself waits (an `Async` step in its body) returns `UI < (Async & Scope)`.
  *
  * {{{
  * def counter(label: String): UI < Sync =
  *     for count <- Signal.initRef(0)
  *     yield div(span(label), span(count.map(_.toString)), button("+").onClick(count.updateAndGet(_ + 1)))
  * }}}
  *
  * A piece of state is a Kyo `SignalRef`: `Signal.initRef(initial)`.
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
// claude 2026-10-19: changed: the Scaladoc names Sync & Scope (P1b, A5 e)
object Hooks:

    /** A piece of state: `Signal.initRef`. Deprecated: it only renames Kyo's
      * `Signal.initRef`, so call that.
      *
      * {{{
      * for count <- Signal.initRef(0) // not useSignal(0)
      * yield span(count.map(_.toString))
      * }}}
      */
    // claude 2026-10-19: changed: deprecated, Signal.initRef replaces it (P1b,
    // A5 f)
    @deprecated(
      "use Signal.initRef(initial): useSignal only renames it",
      "0.1.0",
    )
    def useSignal[A](
        initial: A,
    )(using CanEqual[A, A], Frame): SignalRef[A] < Sync =
      Signal.initRef(initial)

    /** A signal holding the last value of `stream` (`initial` before the first
      * one). The stream runs in a fiber while the enclosing `Scope` is open;
      * the hook itself does not suspend.
      *
      * {{{
      * def status(lines: Stream[String, Async]): UI < (Sync & Scope) =
      *   for last <- useHold("waiting")(lines)
      *   yield span(last)
      * }}}
      */
    // claude 2026-10-19: changed: Sync & Scope, not Async & Scope (P1b, A5 e)
    def useHold[A](initial: A)(stream: => Stream[A, Async])(using
        CanEqual[A, A],
        Frame,
        Tag[Emit[Chunk[A]]],
    ): Signal[A] < (Sync & Scope) =
      // claude 2026-10-19: changed: Tui4sTrack.init: the fiber joins the gauge
      // of the run in one bracket (P2, A7)
      for
          ref <- Signal.initRef(initial)
          _   <- Tui4sTrack.init(stream.foreach(ref.set))
      yield ref

    /** A signal folding `stream` into a state. The stream runs in a fiber while
      * the enclosing `Scope` is open; the hook itself does not suspend.
      *
      * {{{
      * def total(amounts: Stream[Int, Async]): UI < (Sync & Scope) =
      *   for sum <- useFold(0)(amounts)(_ + _)
      *   yield span(sum.map(s => s"Total: $s"))
      * }}}
      */
    // claude 2026-10-19: changed: Sync & Scope, not Async & Scope (P1b, A5 e)
    def useFold[A, S](
        initial: S,
    )(stream: => Stream[A, Async])(f: (S, A) => S)(using
        CanEqual[S, S],
        Frame,
        Tag[Emit[Chunk[A]]],
    ): Signal[S] < (Sync & Scope) =
      // claude 2026-10-19: changed: Tui4sTrack.init: the fiber joins the gauge
      // of the run in one bracket (P2, A7)
      for
          ref <- Signal.initRef(initial)
          _   <- Tui4sTrack.init(
                   stream.foreach(a => ref.updateAndGet(s => f(s, a))),
                 )
      yield ref

    /** Runs `effect` in a fiber that lives as long as the enclosing `Scope`
      * (the app, or the [[component]] mount). The effect has a `Scope` of its
      * own: when the fiber ends (the enclosing `Scope` closes, or the run
      * ends), it closes, so a `Scope.ensure` inside the effect is its cleanup.
      * There is no cleanup function to return. The effect can suspend; the hook
      * itself does not.
      *
      * {{{
      * def logged(name: String): UI < (Sync & Scope) =
      *   for _ <- useEffect(Log.info(s"$name mounted").andThen(Scope.ensure(Log.info(s"$name unmounted"))))
      *   yield span(name)
      * }}}
      */
    // claude 2026-10-13: changed (C4): the Scaladoc (the cleanup)
    // claude 2026-10-19: changed: Sync & Scope, not Async & Scope (P1b, A5 e)
    def useEffect(effect: => Any < (Async & Scope))(using
        Frame,
    ): Unit < (Sync & Scope) =
      // claude 2026-10-19: changed: Tui4sTrack.init: the fiber joins the gauge
      // of the run in one bracket (P2, A7)
      Tui4sTrack.init(Scope.run(effect))

    /** Runs `action` every `period` while the enclosing `Scope` is open.
      *
      * {{{
      * def clock: UI < (Sync & Scope) =
      *   for
      *       seconds <- Signal.initRef(0)
      *       _       <- useInterval(1.second)(seconds.updateAndGet(_ + 1))
      *   yield span(seconds.map(s => s"$s s"))
      * }}}
      */
    // claude 2026-10-19: changed: Sync & Scope, not Async & Scope (P1b, A5 e)
    def useInterval(period: Duration)(action: => Any < Async)(using
        Frame,
    ): Unit < (Sync & Scope) =
      useEffect(Loop.forever(Async.sleep(period).andThen(action)))

    /** A component node: the runner runs `body` when the node mounts and closes
      * its `Scope` when it unmounts. The identity of the node is its call site
      * and `key`: while a node of the same identity stays at the same position,
      * the mount goes on (its state, its effects and its UI stay, and `body`
      * does not run again). Give a `key` when one call site shows different
      * things at one position (for example `component(user.id)(...)`): another
      * key mounts fresh. When `body` fails, the node stays empty, and the run's
      * Kyo logger gets one line at error (the position and the failure). In
      * another kyo-ui runner, the node is empty.
      */
    // claude 2026-10-13: changed (C4): the Scaladoc (the identity and the key)
    // claude 2026-10-19: changed: a body that fails is logged
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
