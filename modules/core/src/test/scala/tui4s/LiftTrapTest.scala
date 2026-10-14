package tui4s

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** The lift-trap canary (RR O5). Kyo lifts a plain value into `A < S`, so a
  * handler value with no type compiles where kyo-ui expects `Any < Async`, but
  * its side effect is not in the effect: it runs once, when the value is built,
  * and never on the key. With the type `: Any < Async` (and the effect inside
  * `Sync.defer`) it runs on each key. AGENTS.md "Rules for code" has the rule.
  * When Kyo or kyo-ui closes the trap, this canary fails: then update the rule.
  */
// claude 2026-10-13: created (C3, RR O5)
class LiftTrapTest extends RunnerSuite:

    "an untyped handler val (a plain side effect) runs once at construction and never on the key; a typed one runs on each key" in {
      val trapped = new AtomicInteger(0)
      val typed   = new AtomicInteger(0)
      for
          h <- Headless.start(30, 3) { _ =>
                 // the trap: `h1` is a Unit, built here, once
                 val h1              =
                     val _ = trapped.incrementAndGet()
                 // the rule: a typed effect value
                 val h2: Any < Async =
                   Sync.defer { val _ = typed.incrementAndGet() }
                 div.style(_.row)(
                   button("trap").onClick(h1),
                   button("typed").onClick(h2),
                 )
               }
          // after the construction: the trap ran once, the typed one never
          c0 = (trapped.get, typed.get)
          _ <- Kyo.foreach(1 to 3)(_ => h.key(Keyboard.Enter)) // "trap"
          _ <- h.key(Keyboard.Tab)
          _ <- Kyo.foreach(1 to 3)(_ => h.key(Keyboard.Enter)) // "typed"
      yield
          val after = (trapped.get, typed.get)
          assert(
            c0 == (1, 0) && after == (1, 3),
            s"(trap, typed) after the construction: $c0; after 3 Enter on each: $after",
          )
      end for
    }

    "the same trap in a key handler function: an untyped match runs nothing; with `: Any < Async` it runs" in {
      for
          a      <- Signal.initRef(0)
          b      <- Signal.initRef(0)
          // the trap: the match widens to `Any`, so the effect is a value
          untyped = (e: KeyboardEvent) =>
                      e.key match
                          case Keyboard.Char('+') => a.updateAndGet(_ + 1)
                          case _                  => ()
          // the rule: the result type `Any < Async`
          typed   = (e: KeyboardEvent) =>
                      (e.key match
                          case Keyboard.Char('+') => b.updateAndGet(_ + 1)
                          case _                  => ()
                      ): Any < Async
          h      <- Headless.start(30, 3)(_ =>
                      div.onKeyDown(untyped)(div.onKeyDown(typed)(button("k"))),
                    )
          _      <- Kyo.foreach(1 to 3)(_ => h.char('+'))
          x      <- a.get
          y      <- b.get
      yield assert(
        x == 0 && y == 3,
        s"untyped handler: $x writes; typed handler: $y writes",
      )
    }
end LiftTrapTest
