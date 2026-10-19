package tui4s

/** The tui4s experience in one dependency: the artifact tui4s-core.
  *
  * {{{
  * libraryDependencies += "io.github.yuri4n" %% "tui4s-core" % "<version>"
  * }}}
  *
  * tui4s-core holds no code of its own. It depends on three artifacts, and an
  * app gets all of them:
  *   - tui4s-runner, the terminal runner for kyo-ui: `Tui.run` runs a kyo-ui
  *     value in the real terminal, follows its signals and sends it the keys;
  *     `Hooks` and `Component` are the two styles of components;
  *     `TuiOptions`, `TuiError`, `FrameStats` and the screen types (`Screen`,
  *     `CellStyle`, `CellColor`) are its data. The package Scaladoc of `tui4s`
  *     describes the runner.
  *   - tui4s-app: `TuiApp`, the main class of an app (`object App extends
  *     TuiApp` with `def ui`). It prepares the JVM, runs the UI with
  *     `Tui.run` and gives the exit code.
  *   - tui4s-testkit: `tui4s.testkit.Headless`, the headless test kit. A test
  *     runs the app without a terminal, sends it keys and reads the screen.
  *
  * A project that needs only a part depends on that artifact: tui4s-runner
  * alone for a library or a main of its own, tui4s-testkit in the `Test`
  * scope. The packages are the same in all the artifacts (`tui4s`,
  * `tui4s.spi`, `tui4s.testkit`), so an import does not change.
  *
  * This object has no members. It is the entry of the documentation of
  * tui4s-core.
  */
// claude 2026-10-19: created (D25): the composite artifact tui4s-core
object Core
