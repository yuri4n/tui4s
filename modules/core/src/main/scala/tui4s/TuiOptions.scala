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
  * @param paste
  *   bracketed paste: a paste arrives as ONE event, one edit of the focused
  *   text input, and a line break in it is a space (it never submits a form).
  *   In the terminal, the runner of a later version turns the bracketed paste
  *   mode of the terminal on for `true`. With `false`, a paste arrives as
  *   single keys, as in a terminal without bracketed paste: a line break is an
  *   Enter, so it can submit a form. `Headless.paste` follows this option.
  */
// claude 2026-10-12: changed: Kyo-free (java.time.Duration, D-14 a), CanEqual,
// Scaladoc (RC-3)
// claude 2026-10-13: changed: ctrlCQuits (C3, D-24 a); paste (C3, RC-14 part)
final case class TuiOptions(
    autoFocus: Boolean = true,
    settle: Settle = Settle.Quiescent(Duration.ofMillis(100)),
    ctrlCQuits: Boolean = true,
    paste: Boolean = true,
) derives CanEqual
