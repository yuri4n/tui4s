package tui4s.internal

import tui.crossterm.Event as CEvent

/** The input of a run in the terminal: crossterm events (D-21 a), so that the
  * tests of the input thread also test [[EventMapping]]. The input thread is
  * its only caller, a platform thread: first `poll`, and `read` only after
  * `poll` returned true, so the thread never blocks in `read` (a native `read`
  * cannot be interrupted).
  */
// claude 2026-10-13: changed (T1, D-21 a): internal (the element is a jatatui
// type, RC-36); the element is the crossterm Event, not a tui4s event
private[tui4s] trait InputPort:
    /** Waits at most `timeoutMillis` for an event; true when one is ready. */
    def poll(timeoutMillis: Int): Boolean

    /** The next event; `null` when none is ready. */
    def read(): CEvent | Null
