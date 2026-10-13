package tui4s

import java.time.Duration
import tui4s.spi.Settle

/** The options of a run. This type is Kyo-free: a duration is a
  * `java.time.Duration`, so that ZIO and Cats Effect users can build it without
  * Kyo types in their code (D-14).
  *
  * @param autoFocus
  *   focus the first focusable element (or the one with `focusAuto(true)`) when
  *   nothing has the focus
  * @param settle
  *   how the runner decides that the consequences of an event are all in before
  *   it flushes a frame (see [[tui4s.spi.Settle]])
  * @param ctrlCQuits
  *   the key Ctrl-C ends the run. A terminal in raw mode sends Ctrl-C as a key,
  *   not as a signal, so the runner quits on it before any handler sees it.
  *   With `false`, Ctrl-C goes to the handlers as any other key.
  */
// claude 2026-10-12: changed: Kyo-free (java.time.Duration, D-14 a), CanEqual,
// Scaladoc (RC-3)
// claude 2026-10-13: changed: ctrlCQuits (C3, D-24 a)
final case class TuiOptions(
    autoFocus: Boolean = true,
    settle: Settle = Settle.Quiescent(Duration.ofMillis(100)),
    ctrlCQuits: Boolean = true,
) derives CanEqual
