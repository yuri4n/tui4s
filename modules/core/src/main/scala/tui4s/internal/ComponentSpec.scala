package tui4s.internal

import kyo.*
import kyo.UI.Ast.*
import tui4s.Component

/** What the runner mounts for a component node. */
sealed trait ComponentSpec

object ComponentSpec:
    /** A hook-style component: an effect that builds a UI, run once per mount,
      * with a `Scope` that closes at unmount.
      */
    final case class Hook(identity: String, body: () => UI < (Async & Scope))
        extends ComponentSpec

    /** A [[Component]] instance. Its state is keyed by position and class. */
    final case class Instance(component: Component) extends ComponentSpec

    /* A component node is an ordinary kyo-ui `Reactive` whose signal is
     * registered here. kyo-ui's AST is sealed, so a */
    /* backend cannot add a node type; the registry is the smallest workaround.
     * Weak keys: an entry goes away with the */
    /* UI value that holds the signal. This is the only global mutable object of
     * tui4s-kyoui. */
    private val registry =
      java.util.Collections.synchronizedMap(
        new java.util.WeakHashMap[Signal[?], ComponentSpec](),
      )

    def lookup(signal: Signal[?]): Option[ComponentSpec] = Option(
      registry.get(signal),
    )

    /** The node: a `Reactive` that this runner mounts as a component. Another
      * kyo-ui runner sees `fallback`.
      */
    def node(spec: ComponentSpec, fallback: => UI)(using Frame): Reactive[UI] =
        lazy val fb         = fallback
        val sig: Signal[UI] = Signal.initRaw[UI](
          [B, S] => (f: UI => B < S) => f(fb),
          [B, S] => (f: UI => B < S) => Async.never[UI].map(f),
        )
        discard(registry.put(sig, spec))
        Reactive[UI](sig)
    end node

    private def discard(a: Any): Unit = ()
end ComponentSpec
