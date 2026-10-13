package tui4s

import kyo.*
import kyo.UI.Ast.*
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
    def onMount(state: SignalRef[State]): Any < (Async & Scope) = ()

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
