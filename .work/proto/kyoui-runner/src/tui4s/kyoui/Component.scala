package tui4s.kyoui

import kyo.*
import kyo.UI.Ast.*
import scala.language.implicitConversions

/** What the runner mounts for a component node. */
sealed trait ComponentSpec

object ComponentSpec:
    /** A hook-style component: an effect that builds a UI, run once per mount, with a `Scope` that closes at unmount. */
    final case class Hook(identity: String, body: () => UI < (Async & Scope)) extends ComponentSpec

    /** A [[Component]] instance. Its state is keyed by position and class. */
    final case class Instance(component: Component) extends ComponentSpec

    // A component node is an ordinary kyo-ui `Reactive` whose signal is registered here. kyo-ui's AST is sealed, so a
    // backend cannot add a node type; the registry is the smallest workaround. Weak keys: an entry goes away with the
    // UI value that holds the signal. This is the only global mutable object of tui4s-kyoui.
    private val registry =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap[Signal[?], ComponentSpec]())

    def lookup(signal: Signal[?]): Option[ComponentSpec] = Option(registry.get(signal))

    /** The node: a `Reactive` that this runner mounts as a component. Another kyo-ui runner sees `fallback`. */
    def node(spec: ComponentSpec, fallback: => UI)(using Frame): Reactive[UI] =
        lazy val fb = fallback
        val sig: Signal[UI] = Signal.initRaw[UI](
            [B, S] => (f: UI => B < S) => f(fb),
            [B, S] => (f: UI => B < S) => Async.never[UI].map(f)
        )
        discard(registry.put(sig, spec))
        Reactive[UI](sig)
    end node

    private def discard(a: Any): Unit = ()
end ComponentSpec

/** A component with a type member for its state (the second style the user asked for).
  *
  * The runner allocates `SignalRef[State]` with `initial` when the component mounts, keeps it while a component of the
  * same class stays at the same position (keyed lists: the same key), and drops it when the component unmounts. `view`
  * is pure: it only builds a kyo-ui value. `onMount` runs effects (timers, streams) for the mount's lifetime.
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

    /** The kyo-ui node of this component. In another kyo-ui runner (DOM, server-push) it still works: the state is then
      * allocated once per node value.
      */
    final def ui(using Frame): Reactive[UI] =
        ComponentSpec.node(
            ComponentSpec.Instance(this), {
                import AllowUnsafe.embrace.danger
                given CanEqual[State, State] = CanEqual.derived
                view(Signal.SignalRef.Unsafe.init[State](initial).safe)
            }
        )
end Component

object Component:
    /** Lets a component be a child: `div(Counter("a"), Counter("b"))`. */
    implicit def componentToChild(c: Component)(using Frame): HtmlChildVal = HtmlChildVal.lift(c.ui)
end Component
