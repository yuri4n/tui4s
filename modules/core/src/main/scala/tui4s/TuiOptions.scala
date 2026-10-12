package tui4s

import kyo.*
import tui4s.spi.Settle

final case class TuiOptions(
    /** Ctrl-C ends the run (raw mode turns it into a key). */
    ctrlCQuits: Boolean = true,
    /** Focus the first focusable element (or the one with `focusAuto(true)`)
      * when nothing has the focus.
      */
    autoFocus: Boolean = true,
    /** How the runner decides that the consequences of an event are all in (see
      * [[Settle]]).
      */
    settle: Settle = Settle.Quiescent(100.millis),
    /** How long a frame waits for a mounting component's first content. */
    pendingTimeout: Duration = 2.seconds,
    /** Where stdout and stderr go while the terminal UI runs (they would
      * corrupt the screen).
      */
    logFile: String = "tui4s.log",
)
