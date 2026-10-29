package tui4s

/** The tui4s experience in one dependency: the artifact tui4s-core.
  *
  * {{{
  * libraryDependencies += "io.github.yuri4n" %% "tui4s-core" % "<version>"
  * }}}
  *
  * The jar holds:
  *   - `TuiApp`, the main class of an app (`object App extends TuiApp` with
  *     `def ui`). It prepares the JVM, runs the UI with `Tui.run` and gives the
  *     exit code.
  *   - `tui4s.use`, the composables over Kyo signals.
  *   - `tui4s.widgets`, the widgets, on the composables.
  *
  * The POM depends on two more artifacts, and an app gets them too:
  *   - tui4s-runner, the terminal runner for kyo-ui: `Tui.run` runs a kyo-ui
  *     value in the real terminal, follows its signals and sends it the keys;
  *     `Hooks` and `Component` are the two styles of components; `TuiOptions`,
  *     `TuiError`, `FrameStats` and the screen types (`Screen`, `CellStyle`,
  *     `CellColor`) are its data. The package Scaladoc of `tui4s` describes the
  *     runner.
  *   - tui4s-testkit: `tui4s.testkit.Headless`, the headless test kit. A test
  *     runs the app without a terminal, sends it keys and reads the screen.
  *
  * A library that needs only the runner depends on tui4s-runner. Add
  * tui4s-testkit in the `Test` scope for `Headless`. The packages are the same
  * in all the artifacts (`tui4s`, `tui4s.use`, `tui4s.widgets`, `tui4s.spi`,
  * `tui4s.testkit`), so an import does not change.
  *
  * The sources of `tui4s.use` and `tui4s.widgets` also build for Scala.js in
  * this repository. Those Scala.js projects are not published.
  *
  * This object has no members. It is the entry of the documentation of
  * tui4s-core.
  */
// claude 2026-10-19: created (D25): the composite artifact tui4s-core; cursor 2026-10-29: changed: the jar holds TuiApp, use and widgets (ADR 0024)
object Core
