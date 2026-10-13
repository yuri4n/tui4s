package tui4s

/** Why a run in the real terminal failed ([[Tui.run]]). Kyo-free; it extends
  * `Exception`, so that a `KyoApp` main shows it and the ZIO and Cats Effect
  * modules can use it as their error type unchanged.
  *
  * A failure of the app itself (the construction of the UI throws) is not a
  * `TuiError`: it comes out of the run as it is, after the terminal is
  * restored. A handler that fails is logged, and the run goes on.
  */
// claude 2026-10-13: created (T1, RC-3): the TuiError of PR 5 Options.scala
// without the cases of dropped features (AppFailed, SourceFailed)
enum TuiError(message: String, cause: Throwable | Null)
    extends Exception(message, cause):

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
      * this platform, or it does not link. The message has the cause. Nothing
      * was changed.
      */
    // claude 2026-10-13: created (T1, the bot fix of the review thread
    // Crossterm.scala:103: an UnsatisfiedLinkError is not NativeAccessDenied)
    case NativeLoadFailed(error: Throwable)
        extends TuiError(
          s"the native terminal library (crossterm) could not be loaded: $error",
          error,
        )

    /** Reading from or writing to the terminal failed (also a terminal mode
      * that could not be set). The terminal is restored as far as possible.
      */
    case TerminalIO(error: Throwable)
        extends TuiError("terminal I/O failed", error)
end TuiError
