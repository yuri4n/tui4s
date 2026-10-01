# tui4s

Declarative terminal user interfaces for Scala 3.

tui4s has a [Kyo](https://getkyo.io) core and separate integrations for
[ZIO](https://zio.dev) and [Cats Effect](https://typelevel.org/cats-effect/).
It renders with [jatatui](https://github.com/oyvindberg/jatatui), a Java port of
[ratatui](https://ratatui.rs).

> **Status:** under construction. Nothing is published yet.

## Modules

Each module is released on its own, with its own version.

| Artifact                         | For                                    |
| -------------------------------- | -------------------------------------- |
| `io.github.yuri4n::tui4s-core`   | Kyo users, and the base of the others. |
| `io.github.yuri4n::tui4s-zio`    | ZIO users.                             |
| `io.github.yuri4n::tui4s-cats`   | Cats Effect users.                     |

## Requirements

- JDK 25 (Kyo 1.0.0-RC7 needs it).
- Scala 3.9.0 or later.

## Quick start

An app is pure functions of one immutable state: `init`, `update`, `view`.
This is the Counter of [`examples/`](examples/README.md):

```scala
import tui4s.*

object CounterApp extends TuiApp[Int]:
  val keys: Keymap[Int] = Keymap(
    bind('+', "increment")(_ + 1),
    bind('-', "decrement")(_ - 1),
    bind('q', "quit")(_ => Quit)
  )

  def init: Int = 0

  def update(n: Int, event: Event): Next[Int] = keys(n, event)

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      help(keys).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")
```

A terminal app needs a forked JVM with the terminal on stdin, and two JVM
flags. In `build.sbt`:

```scala
libraryDependencies += "io.github.yuri4n" %% "tui4s-core" % "<version>"

run / fork         := true
run / connectInput := true
outputStrategy     := Some(StdoutOutput)
run / javaOptions ++= Seq(
  "--enable-native-access=ALL-UNNAMED",   // crossterm is a native library
  "--sun-misc-unsafe-memory-access=allow" // Kyo uses sun.misc.Unsafe
)
```

Then `sbt run`. `TuiApp` sets the Kyo scheduler flags that cut the idle CPU of
a waiting app. With your own `KyoApp`, call `Tui.run(app)` and pass these flags
yourself:

```sh
-Dkyo.scheduler.cycleIntervalNs=10000000
-Dkyo.scheduler.blockingMonitorIntervalNs=20000000
-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100
```

Test an app without a terminal:

```scala
import tui4s.testkit.*

val t = TuiTest(CounterApp).press('+', '+')
assert(t.screen.contains("Count: 2"))
```

## Contributing

Read [AGENTS.md](AGENTS.md). It has the commands, the code rules, and the
release process for humans and for AI agents.

## License

[Apache-2.0](LICENSE)
