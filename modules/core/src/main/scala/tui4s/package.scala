/** tui4s is a terminal runner for kyo-ui. A [[kyo.UI]] value describes the
  * screen; tui4s lays it out as a flexbox in terminal cells and paints it into
  * a jatatui buffer. The UI DSL is kyo-ui: an app imports `kyo.*` and
  * `kyo.UI.*` for the elements and styles, and `tui4s.*` to run them.
  *
  * In this version tui4s lays out and paints a value (`tui4s.internal.Layout`,
  * `tui4s.internal.Paint`) and follows its signals: a `Signal`, a `Foreach` or
  * a bound `SignalRef` drives a region of the screen, and each change gives one
  * frame (`tui4s.internal.Runner`; [[tui4s.TuiOptions]] tunes it,
  * [[tui4s.FrameStats]] describes a frame). Keys reach the UI: the handlers of
  * the focused element and its ancestors run, Tab moves the focus, a text input
  * edits by grapheme clusters, a bracketed paste is one edit, and
  * [[tui4s.Tui.quit]] ends the run. [[tui4s.Tui.run]] runs a UI in the real
  * terminal (raw mode, the alternate screen, an input thread for the keys),
  * restores the terminal when the run ends, and fails with a [[tui4s.TuiError]]
  * when it cannot start. The headless test kit [[tui4s.testkit.Headless]] runs
  * an app without a terminal and sends it keys. Components come in two styles:
  * [[tui4s.Hooks]] (a component is a function with `Signal.initRef`,
  * `useEffect`, `useInterval`, ...; `component(key)` lets the runner mount it)
  * and the class
  * [[tui4s.Component]] (a `State` type member; the runner keeps the state under
  * the position and the state key). A component in a `when` loses its state
  * when it is hidden, and the fibers of the components and hooks end with the
  * run. [[tui4s.TuiApp]] is the main class of an app: it prepares the JVM (the
  * scheduler values of [[tui4s.spi.SchedulerDefaults]], the output capture),
  * runs the UI with `Tui.run`, and gives the exit code (0 after a quit, 1 after
  * a failure; 143 and 130 when `kill -TERM` and `kill -INT` stop the JVM).
  */
// claude 2026-10-12: created (OS-13 part): the package Scaladoc; changed:
// plain words instead of the pull request ids (review R1-4, R2-3); changed:
// what this version does (signals, the headless kit)
// claude 2026-10-13: changed: keys, Tui.quit (C3)
// claude 2026-10-13: changed: Tui.run in the real terminal (T1)
// claude 2026-10-13: changed: the two component styles (C4)
// claude 2026-10-13: changed: TuiApp (T2)
package object tui4s
