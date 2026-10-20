package tui4s

import kyo.*

/** A piece of reactive state: the same as `Signal.initRef(initial)`. The name
  * folds `Signal.initRef` into one word, so there is one thing to remember, and
  * it still reads as Kyo's own name. The function is akin to Vue's `ref()`: it
  * returns Kyo's `SignalRef` (the state that kyo-ui binds), which plays the
  * part of Vue's `Ref`. It has no lifetime: call it in a component, in a store
  * or in a test.
  *
  * {{{
  * import kyo.*
  * import kyo.UI.*
  * import tui4s.initSig
  *
  * def counter: UI < Sync =
  *   for count <- initSig(0)
  *   yield span(count.map(_.toString))
  * }}}
  *
  * Anyone who prefers Kyo's own name writes `Signal.initRef`; the two are the
  * same. Shared code for the terminal and the web (tui4s-use, tui4s-widgets,
  * the assistant) has no runner on its classpath: it calls `Signal.initRef`
  * directly.
  */
// claude 2026-10-20: created: initSig, the short name of Signal.initRef (D26)
def initSig[A](initial: A)(using CanEqual[A, A], Frame): SignalRef[A] < Sync =
  Signal.initRef(initial)
