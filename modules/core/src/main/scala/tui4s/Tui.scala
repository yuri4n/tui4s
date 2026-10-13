package tui4s

import kyo.*
import tui4s.internal.Tui4sQuit

/** The entry points of a tui4s app. In this version it has `quit`; `run` (the
  * real terminal) comes in a later version.
  */
// claude 2026-10-13: created (C3, RC-2 part: the prototype's Tui.quit)
object Tui:

    /** Ends the run that runs the caller. A key handler, the construction of
      * the UI, and a fiber that one of them started can call it: the runner
      * sets the quit of the run around each handler and around the UI
      * construction, and a forked fiber keeps it. Outside a run it does
      * nothing. The run ends after the frame of the current event.
      */
    def quit(using Frame): Unit < Sync = Tui4sQuit()
end Tui
