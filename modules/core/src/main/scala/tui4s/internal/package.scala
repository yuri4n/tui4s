package tui4s.internal

/** Evaluates `value` and drops it, so that -Wvalue-discard and
  * -Wnonunit-statement stay quiet where dropping the value is intended.
  */
private[tui4s] inline def discard[A](inline value: A): Unit =
    val _ = value
