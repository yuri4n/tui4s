package tui4s

/** The size of the terminal of a run, in cells: `width` columns and `height`
  * rows. [[Tui.size]] gives it as a signal.
  *
  * {{{
  * Tui.size.map(size =>
  *   div(size.map(s => if s.width >= 100 then "wide" else "narrow")),
  * )
  * }}}
  */
// claude 2026-10-19: created (L1b)
final case class TerminalSize(width: Int, height: Int) derives CanEqual
