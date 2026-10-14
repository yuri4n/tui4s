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
  * [[tui4s.Tui.quit]] ends the run. The headless test kit
  * [[tui4s.testkit.Headless]] runs an app without a terminal and sends it keys.
  * The components and the real terminal are not in this version yet.
  */
// claude 2026-10-12: created (OS-13 part): the package Scaladoc; changed:
// plain words instead of the pull request ids (review R1-4, R2-3); changed:
// what this version does (signals, the headless kit)
// claude 2026-10-13: changed: keys, Tui.quit (C3)
package object tui4s
