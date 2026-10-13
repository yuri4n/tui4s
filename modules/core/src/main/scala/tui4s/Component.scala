package tui4s

import kyo.*
import kyo.UI.Ast.*
import scala.annotation.unused
import scala.language.implicitConversions
import tui4s.internal.ComponentSpec

/** A component with a type member for its state (the second style the user
  * asked for).
  *
  * The runner allocates `SignalRef[State]` with `initial` when the component
  * mounts, keeps it while a component of the same class stays at the same
  * position (keyed lists: the same key), and drops it when the component
  * unmounts. `view` is pure: it only builds a kyo-ui value. `onMount` runs
  * effects (timers, streams) for the mount's lifetime.
  *
  * {{{
  * final class Counter(label: String) extends Component:
  *     type State = Int
  *     def initial = 0
  *     def view(count: SignalRef[Int]) = div(span(label), span(count.map(_.toString)), button("+").onClick(count.updateAndGet(_ + 1)))
  * }}}
  */
abstract class Component:
    type State
    def initial: State
    def view(state: SignalRef[State]): UI
    // claude 2026-10-13: changed (strict-flag fix, as S6 25aab29): @unused on
    // the parameter of the default
    def onMount(@unused state: SignalRef[State]): Any < (Async & Scope) = ()

    /** The key of this component's state at its position. The runner keeps the
      * state of a component under its position and this key: a component at the
      * same position with the same key gets the same state, and a component
      * with another key starts from `initial`. The default is the name of the
      * class. So a component of another class at the same position does not get
      * this state, and two instances of one class at one position share it (a
      * parent that renders again builds new instances, and they keep the
      * state). Override it when one class shows different things at one
      * position, for example one state per user:
      * `override protected def stateKey = s"user:$id"`.
      */
    // claude 2026-10-13: created (C4, FC-17, plan 0.2): the overridable state
    // key
    protected def stateKey: String = getClass.getName

    /** The kyo-ui node of this component. In another kyo-ui runner (DOM,
      * server-push) it still works: the state is then allocated once per node
      * value.
      */
    final def ui(using Frame): Reactive[UI] =
      ComponentSpec.node(
        ComponentSpec.Instance(this), {
          import AllowUnsafe.embrace.danger
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
