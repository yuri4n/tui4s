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
  *   In the real terminal, the run turns the bracketed paste mode of the
  *   terminal on for `true`. With `false`, a paste arrives as single keys, as
  *   in a terminal without bracketed paste: a line break is an Enter, so it can
  *   submit a form. `Headless.paste` follows this option.
  * @param pollInterval
  *   how long the input thread of the real terminal waits for input in one
  *   poll. A shorter interval stops the input thread sooner at the end of the
  *   run (the end waits at most about two intervals for it); a longer one wakes
  *   it less often when nobody types. The keys themselves arrive at once: the
  *   poll returns as soon as input is ready. An interval below 1 ms (also zero
  *   or a negative one) counts as 1 ms.
  * @param inboxCapacity
  *   how many messages (keys, pastes, resizes and the new values of the
  *   signals) can wait for the runner. When the inbox is full, the input thread
  *   and the signal watchers wait until the runner takes a message: no key and
  *   no value is dropped, and the typing waits. A value below 1 counts as 1.
  * @param captureOutput
  *   while the UI is up in the real terminal, `System.out` and `System.err` go
  *   to the log of the run (see `logFile`), so that a stray print cannot draw
  *   over the screen. The capture is JVM-wide. Scala's `println` writes to
  *   `scala.Console.out`, which keeps the stream that `System.out` was when
  *   `scala.Console` was first used: when it was used before the run, `println`
  *   is not captured, and the run says so in the log and on stderr after the
  *   terminal is restored. A [[TuiApp]] installs the capture streams in its
  *   constructor, before the app's own code, so this does not happen to it;
  *   with `Tui.run` in a main of its own, start the run before the first
  *   `println`. With `false`, nothing is captured.
  * @param logFile
  *   where the Kyo log of the run and the captured output go (appended). `None`
  *   (the default): no file; the last 200 lines of captured output and of log
  *   lines at warn or above stay in memory and are printed to stderr after the
  *   terminal is restored. The log lines are those at or above the level of the
  *   Kyo logger where the run starts (Kyo's default is warn; set
  *   `-Dkyo.Log.defaultLevel=info`, or bind a logger with `Log.let`, to get
  *   info and debug lines).
  * @param pendingTimeout
  *   how long a frame waits for a mounting component's first content. A hook
  *   component ([[tui4s.Hooks.component]]) runs its body when it mounts, and
  *   the frame of that event waits for the UI that the body gives, at most this
  *   long in all (other values that arrive meanwhile do not make the wait
  *   longer). When the body takes longer, the frame comes without it (the
  *   component's place is empty), and its content comes in a later frame; the
  *   frames between do not wait for it again. A negative value counts as zero.
  *   After the wait, the frame settles as usual: the `max` of `settle` counts
  *   from the end of the wait. A quit (`Tui.quit`, or Ctrl-C with `ctrlCQuits`)
  *   ends the wait at once, and so does a body that fails (the component's
  *   place stays empty).
  */
// claude 2026-10-12: changed: Kyo-free (java.time.Duration, D-14 a), CanEqual,
// Scaladoc (RC-3)
// claude 2026-10-13: changed: ctrlCQuits (C3, D-24 a); paste (C3, RC-14 part)
// claude 2026-10-13: changed (T1, RC-3 part): pollInterval, inboxCapacity,
// captureOutput, logFile (the PR 5 option set: pollMillis as a Duration; no
// fps, mouse, focusEvents, viewport, theme or trace)
// claude 2026-10-13: changed (T1, review R2-1): pollInterval below 1 ms is 1 ms
// claude 2026-10-13: changed (C4, the C2 cut back): pendingTimeout (D-14 a: a
// java.time.Duration; the prototype's default, 2 s); changed (C4, P-6): the
// bound is in total, and only the frame of the mount waits
// claude 2026-10-13: changed (C4, review R1-1, R2-1): the Scaladoc of
// pendingTimeout: the settle after the wait
// claude 2026-10-13: changed (C4, review R2-2): the Scaladoc of
// pendingTimeout: a quit ends the wait
// claude 2026-10-13: changed (C4, review R2-5): the Scaladoc of
// pendingTimeout: a body that fails ends the wait
// claude 2026-10-13: changed (T2, review R1-3): the Scaladoc of captureOutput:
// a TuiApp installs the capture in its constructor
final case class TuiOptions(
    autoFocus: Boolean = true,
    settle: Settle = Settle.Quiescent(Duration.ofMillis(100)),
    ctrlCQuits: Boolean = true,
    paste: Boolean = true,
    pollInterval: Duration = Duration.ofMillis(25),
    inboxCapacity: Int = 1024,
    captureOutput: Boolean = true,
    logFile: Option[java.nio.file.Path] = None,
    pendingTimeout: Duration = Duration.ofMillis(2000),
) derives CanEqual
