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
  */
// claude 2026-10-12: changed: Kyo-free (java.time.Duration, D-14 a), CanEqual,
// Scaladoc (RC-3)
final case class TuiOptions(
    autoFocus: Boolean = true,
    settle: Settle = Settle.Quiescent(Duration.ofMillis(100)),
) derives CanEqual
