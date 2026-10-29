package tui4s

import scala.util.control.NoStackTrace

/** Why a run in the real terminal failed ([[Tui.run]]). Kyo-free; it extends
  * `Exception`, so that a `KyoApp` main shows it and a ZIO or Cats Effect
  * integration (planned: issues #18 and #19) can use it as its error type
  * unchanged.
  *
  * The errors of a run are in three groups:
  *   - Infrastructure: the four cases of `TuiError`. The terminal or the JVM
  *     failed. They come out of the run in `Abort[TuiError]`.
  *   - Application failures: the app itself failed (the construction of the UI
  *     fails, or `TuiApp.onExit` fails). Such a failure is not a `TuiError`. It
  *     comes out of the run as it is, after the terminal is restored. A handler
  *     that fails is logged, and the run goes on.
  *   - Panics: an exception that nobody expected. `TuiApp` (artifact
  *     tui4s-core) catches it once, in its report: it prints the stack trace
  *     and gives the exit code 1.
  *
  * The runner converts a native exception (of the terminal library or of the
  * JVM) to a `TuiError` at the edge where it calls the terminal. Each case
  * keeps that exception as its cause, so the cause is never null. A `TuiError`
  * has no stack trace of its own (`NoStackTrace`): the cause has the stack
  * trace that tells where the failure came from.
  */
// claude 2026-10-13: created (T1, RC-3): the TuiError of PR 5 Options.scala
// without the cases of dropped features (AppFailed, SourceFailed)
// claude 2026-10-19: changed: a non-null cause, NoStackTrace, the taxonomy in
// the Scaladoc (P1b, A4)
// claude 2026-10-19: changed: TuiApp is in tui4s-app (D25); 2026-10-20:
// changed: the ZIO and Cats Effect integrations are issues, not modules;
// cursor 2026-10-29: changed: TuiApp ships in tui4s-core (ADR 0024)
enum TuiError(message: String, cause: Throwable)
    extends Exception(message, cause)
    with NoStackTrace:

    /** Standard input is not a terminal, or the process has no terminal (raw
      * mode failed). Nothing was changed. Standard output is not checked: it
      * can be a pipe to the terminal.
      */
    case NotATerminal(error: Throwable)
        extends TuiError("stdin/stdout is not a terminal", error)

    /** The JVM does not allow the native terminal library: start it with
      * `--enable-native-access=ALL-UNNAMED`. Nothing was changed.
      */
    case NativeAccessDenied(error: Throwable)
        extends TuiError(
          "start the JVM with --enable-native-access=ALL-UNNAMED",
          error,
        )

    /** The native terminal library could not be loaded: it cannot be found for
      * this platform, or it does not link. The message names `error` and, when
      * `error` wraps another error (an `ExceptionInInitializerError`, or the
      * `NoClassDefFoundError` of a second try), the innermost cause, which says
      * what failed. Nothing was changed.
      */
    // claude 2026-10-13: created (T1, the bot fix of the review thread
    // Crossterm.scala:103: an UnsatisfiedLinkError is not NativeAccessDenied)
    // claude 2026-10-13: changed (T1, review R2-3): the message names the
    // innermost cause
    case NativeLoadFailed(error: Throwable)
        extends TuiError(
          "the native terminal library (crossterm) could not be loaded: " +
            TuiError.withRootCause(error),
          error,
        )

    /** Reading from or writing to the terminal failed (also a terminal mode
      * that could not be set). The terminal is restored as far as possible.
      */
    case TerminalIO(error: Throwable)
        extends TuiError("terminal I/O failed", error)
end TuiError

object TuiError:
    /** The native exception that caused `e`: the cause of each case. */
    // claude 2026-10-19: created (P1b, A4): the typed cause, no null check
    private[tui4s] def causeOf(e: TuiError): Throwable = e match
        case NotATerminal(c)       => c
        case NativeAccessDenied(c) => c
        case NativeLoadFailed(c)   => c
        case TerminalIO(c)         => c

    /** `e`, and its innermost cause when it has one (at most 16 levels; a cycle
      * stops the walk).
      */
    // claude 2026-10-13: created (T1, review R2-3)
    private def withRootCause(e: Throwable): String =
        @annotation.tailrec
        def root(t: Throwable, seen: List[Throwable]): Throwable =
            val c = t.getCause
            if c == null || seen.size >= 16 || seen.exists(_ eq c) then t // scalafix:ok DisableSyntax.null; KEEP: Java getCause can give null
            else root(c, c :: seen)
        val r                                                    = root(e, List(e))
        if r eq e then e.toString else s"$e (cause: $r)"
    end withRootCause
end TuiError
