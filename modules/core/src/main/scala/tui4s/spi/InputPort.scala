package tui4s.spi

import tui4s.Event

/** Input events for the runtime and for headless runs (for integrations, tests
  * and benchmarks). The runtime calls it only on its input thread, a platform
  * thread: first `poll`, and `read` only after `poll` returned true, so the
  * thread never blocks in `read` (a native `read` cannot be interrupted).
  */
trait InputPort:
    /** Waits at most `timeoutMillis` for an event; true when one is ready. */
    def poll(timeoutMillis: Int): Boolean

    /** The next event; `null` for an event that tui4s ignores (a key release, a
      * media key).
      */
    def read(): Event | Null
end InputPort
