package tui4s.internal

/** Evaluates `value` and drops it, so that -Wvalue-discard and
  * -Wnonunit-statement stay quiet where the drop is intended.
  */
// claude 2026-10-12: created (OS-12 part; the same helper as the old PR 3)
private[tui4s] inline def discard[A](inline value: A): Unit =
    val _ = value
