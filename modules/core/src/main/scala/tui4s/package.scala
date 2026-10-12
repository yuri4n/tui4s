/** tui4s is a terminal runner for kyo-ui. A [[kyo.UI]] value describes the
  * screen; tui4s lays it out as a flexbox in terminal cells and paints it into
  * a jatatui buffer. The UI DSL is kyo-ui: an app imports `kyo.*` and
  * `kyo.UI.*` for the elements and styles, and `tui4s.*` to run them.
  *
  * This PR (C1) brings the layout and paint of a static value
  * (`tui4s.internal.Layout`, `tui4s.internal.Paint`). The runner that follows
  * signals comes in C2, the keys in C3 and the real terminal in T1.
  */
// claude 2026-10-12: created (OS-13 part): the package Scaladoc
package object tui4s
