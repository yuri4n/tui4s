package examples.counterkyo

import examples.counter.Counter
import kyo.*
import tui4s.Tui

/** The Counter with a main of its own: a plain `KyoApp` whose `main` calls
  * `Tui.run`, which gives the UI the quit value. Use this form when the app
  * already has a main (a `KyoApp`, a ZIO or Cats Effect main later); a
  * [[tui4s.TuiApp]] (see `examples.counter.CounterApp`) is the shorter form.
  *
  * A main of its own gives the tui4s scheduler values as JVM flags (`TuiApp`
  * sets them in code): the start script does this. Start it with its start
  * script, not with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/counterkyo
  * }}}
  *
  * The flags of the script: `--enable-native-access=ALL-UNNAMED` (crossterm is
  * a native library), `--sun-misc-unsafe-memory-access=allow`, `-Xss16m` and
  * the three scheduler values (see `examples/README.md`). On `kill -TERM` and
  * `kill -INT`, Kyo's own signal handling of `KyoApp` interrupts the run, which
  * restores the terminal.
  */
// claude 2026-10-13: created (T1, RC-33 part; the lesson of the review thread
// CounterApp.scala:8 of PR 5: the Scaladoc names the launchers, not sbt run)
// claude 2026-10-13: changed (T2, RC-33): moved from examples.counter (one
// main per package: the script `counter` now starts the TuiApp CounterApp)
// claude 2026-10-20: created the region below (L5): the docs page "The
// terminal contract" shows it
// region: main
object CounterKyoApp extends KyoApp:
    run(Tui.run(quit => Counter.ui(quit)))
// endregion: main
