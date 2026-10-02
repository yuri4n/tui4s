package tui4s.interop.zio.fixtures

import _root_.zio.FiberRef
import tui4s.interop.zio.*

/** A test-local copy of the Counter of SPEC 3.4 (the examples depend on this
  * module, not the reverse).
  */
object CounterApp extends App[Int]:
    val keys: Keymap[Int] = Keymap(
      bind('+', "increment")(_ + 1),
      bind('-', "decrement")(_ - 1),
      bind('q', "quit")(_ => Quit),
    )

    def init: Int = 0

    def update(n: Int, event: Event): Next[Int] = keys(n, event)

    def view(n: Int): View =
      column(
        text("Count: ", n.toString.bold.yellow).centered.size(1),
        help(keys).centered.size(1),
      ).flex(Flex.Center).bordered(" Counter ")
end CounterApp

/** An app whose first state declares a task that reads `ref`, and quits with
  * its value.
  */
def readRefOnce(ref: FiberRef[Int]): App[Int] =
  App[Int](
    init = -1,
    view = n => text(n.toString),
    update = (n, _) => n,
    sources = n =>
      if n >= 0 then Nil
      else Seq(Source.For[Int].task(ref.get)((_, v) => Quit(v))),
  )
