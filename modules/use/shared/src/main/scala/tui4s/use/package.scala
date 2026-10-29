package tui4s

/** Composables: plain functions over Kyo's `Signal`, `SignalRef` and `Scope`
  * for kyo-ui apps, in the terminal and on the web. The package does not depend
  * on the terminal runner. It builds for the JVM and for Scala.js. The JVM
  * classes ship in tui4s-core.
  *
  * A composable returns a value, or an effect that needs the `Scope` of its
  * caller when it starts a fiber or registers a finalizer: the caller's `Scope`
  * owns it, and there is no hidden registry. Call a composable once, where its
  * state lives (the body of a component, the start of an app), not inside a
  * `render`, `when` or `foreach` closure, which runs again on each change.
  */
// claude 2026-10-19: created (L2a): the package of tui4s-use; cursor 2026-10-29: changed: the JVM classes ship in tui4s-core (ADR 0024)
package object use
