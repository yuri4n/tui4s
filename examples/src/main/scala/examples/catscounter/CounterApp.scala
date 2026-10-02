package examples.catscounter

import tui4s.interop.cats.*

/** The Counter for Cats Effect users (SPEC 3.5): the same text as the Kyo
  * Counter, with the facade import. `TuiApp` is an `IOApp.Simple`.
  *
  * Run it: `sbt examples/launchers`, then `examples/target/bin/catscounter`.
  */
object CounterApp extends TuiApp[Int]:
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
