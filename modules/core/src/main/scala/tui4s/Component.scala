package tui4s

import kyo.*
import kyo.UI.Ast.*
import tui4s.internal.ComponentSpec

import scala.annotation.unused
import scala.language.implicitConversions

/** A component class: the second style of tui4s components (the first is
  * [[Hooks]]). The state is a type member, `State`, with its `initial` value.
  *
  * The runner allocates a `SignalRef[State]` with `initial` when the component
  * mounts, and keeps it under the position of the component and its
  * [[stateKey]] (by default the name of the class). The mount lasts while a
  * component with the same key stays at the same position: when its parent
  * renders again (it builds a new instance), the new instance gets the same
  * state, and `view` of the new instance gives the content. A keyed list
  * (`foreachKeyed`) keeps the position of an item when it moves. When the
  * component goes away (for example inside a `when` that hides it), the mount
  * ends and its state is dropped: when it comes back, it starts from `initial`.
  *
  * `view` is pure: it only builds a kyo-ui value from the state. `onMount` runs
  * once per mount, in a fiber with a `Scope` of its own, for effects (timers,
  * streams, [[Hooks.useInterval]]): the end of the mount interrupts that fiber,
  * and the end of the run too.
  *
  * A component is a child of an element: `div(Counter("a"), Counter("b"))` (the
  * conversion of the companion), or `ui` where a `UI` is needed.
  *
  * {{{
  * final class Counter(label: String) extends Component:
  *     type State = Int
  *     def initial = 0
  *     def view(count: SignalRef[Int]) = div(span(label), span(count.map(_.toString)), button("+").onClick(count.updateAndGet(_ + 1)))
  * }}}
  */
// claude 2026-10-13: changed (C4): the Scaladoc of the final shape (the state
// key, the mount, onMount once per mount and its end with the run)
abstract class Component:
    type State

    /** The state when the component mounts. */
    def initial: State

    /** The UI of the component over its state. Pure: it only builds a value. */
    def view(state: SignalRef[State]): UI

    /** Effects for the time of the mount (by default none). It runs once per
      * mount, in its own fiber, with a `Scope` that closes when the mount ends
      * or the run ends.
      */
    // claude 2026-10-13: changed (strict-flag fix, as S6 25aab29): @unused on
    // the parameter of the default
    // claude 2026-10-13: changed (C4): the Scaladoc of the members
    def onMount(@unused state: SignalRef[State]): Any < (Async & Scope) = ()

    /** The key of this component's state at its position. The runner keeps the
      * state of a component under its position and this key: a component at the
      * same position with the same key gets the same state (the mount goes on:
      * `onMount` does not run again), and a component with another key starts
      * from `initial`. The default is the name of the class. So a component of
      * another class at the same position does not get this state, and two
      * instances of one class at one position share it (a parent that renders
      * again builds new instances, and they keep the state). Override it when
      * one class shows different things at one position, for example one state
      * per user:
      * `override protected def stateKey = getClass.getName + ":" + id`. A
      * custom key replaces the class name: keep the class name in it, or make
      * sure that the classes that give one key at one position have the same
      * `State` type (they then share the state and the mount).
      */
    // claude 2026-10-13: created (C4, FC-17, plan 0.2): the overridable state
    // key
    // claude 2026-10-13: changed (C4, review R2-6, K1-X1): the Scaladoc: a
    // custom key replaces the class name (the example keeps it)
    protected def stateKey: String = getClass.getName

    /** The kyo-ui node of this component. In another kyo-ui runner (DOM,
      * server-push) it still works: the state is then allocated once per node
      * value.
      */
    final def ui(using Frame): Reactive[UI] =
      ComponentSpec.node(
        ComponentSpec.Instance(this), {
          import AllowUnsafe.embrace.danger // scalafix:ok DisableSyntax.unsafe; KEEP: a node state outside the tui4s runner
          given CanEqual[State, State] = CanEqual.derived
          view(Signal.SignalRef.Unsafe.init[State](initial).safe)
        },
      )
end Component

object Component:
    /** Lets a component be a child: `div(Counter("a"), Counter("b"))`. */
    implicit def componentToChild(c: Component)(using Frame): HtmlChildVal =
      HtmlChildVal.lift(c.ui)

    /** The state key of `c` (its `stateKey`, which is protected), for the
      * runner.
      */
    // claude 2026-10-13: created (C4, FC-17)
    private[tui4s] def stateKeyOf(c: Component): String = c.stateKey
end Component
