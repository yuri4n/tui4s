package tui4s.internal

import kyo.*
import kyo.UI.Ast.*
import tui4s.Component

/** What the runner mounts for a component node. */
// claude 2026-10-13: changed (C4, gate 1 item 1): private[tui4s] on the
// top-level definitions of this file (the seal of tui4s.internal)
private[tui4s] sealed trait ComponentSpec

private[tui4s] object ComponentSpec:
    /** A hook-style component: an effect that builds a UI, run once per mount,
      * with a `Scope` that closes at unmount.
      */
    final case class Hook(identity: String, body: () => UI < (Async & Scope))
        extends ComponentSpec

    /** A [[Component]] instance. Its state is keyed by its position and its
      * state key (`Component.stateKey`, by default the class).
      */
    // claude 2026-10-13: changed (C4, review R1-5): the state key, not the
    // class
    final case class Instance(component: Component) extends ComponentSpec

    // claude 2026-10-13: changed (C4): one comment again (the format step
    // had cut it in three); the last sentence (tui4s has more global state
    // now: the output capture of T1)
    // A component node is an ordinary kyo-ui `Reactive` whose signal is
    // registered here. kyo-ui's AST is sealed, so a backend cannot add a node
    // type; the registry is the smallest workaround (K3). Weak keys: an entry
    // goes away with the UI value that holds the signal. The registry is
    // global and mutable, and keyed by the identity of the signal.
    private val registry =
      java.util.Collections.synchronizedMap( // scalafix:ok DisableSyntax.jvmSync; KEEP: the weak registry (sealed kyo-ui AST)
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
