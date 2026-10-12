package tui4s

import kyo.*
import tui4s.spi.Settle

final case class TuiOptions(
    /** Focus the first focusable element (or the one with `focusAuto(true)`)
      * when nothing has the focus.
      */
    autoFocus: Boolean = true,
    /** How the runner decides that the consequences of an event are all in (see
      * [[Settle]]).
      */
    settle: Settle = Settle.Quiescent(100.millis),
)
