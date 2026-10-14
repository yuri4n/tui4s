/** tui4s is a terminal runner for kyo-ui. A [[kyo.UI]] value describes the
  * screen; tui4s lays it out as a flexbox in terminal cells and paints it into
  * a jatatui buffer. The UI DSL is kyo-ui: an app imports `kyo.*` and
  * `kyo.UI.*` for the elements and styles, and `tui4s.*` to run them.
  *
  * In this version tui4s lays out and paints a static value
  * (`tui4s.internal.Layout`, `tui4s.internal.Paint`). The runner that follows
  * signals, the keys and the real terminal are not in this version yet.
  */
// claude 2026-10-12: created (OS-13 part): the package Scaladoc; changed:
// plain words instead of the pull request ids (review R1-4, R2-3)
package object tui4s
