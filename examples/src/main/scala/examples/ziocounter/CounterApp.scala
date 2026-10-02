package examples.ziocounter

// Not examples.zio.counter: a package named zio would shadow the root zio.

import tui4s.interop.zio.*

/** The Counter for ZIO users (SPEC 3.4): the same app as `examples.counter`,
  * with the import of the ZIO facade. `TuiApp` is a `ZIOAppDefault`.
  *
  * Run it: `sbt examples/launchers`, then `examples/target/bin/ziocounter`.
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
