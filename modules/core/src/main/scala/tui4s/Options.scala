package tui4s

/** Terminal and loop options. An app gives them in [[App.options]]; the runtime
  * reads them once, at start.
  *
  * @param fps
  *   at most one frame per `1/fps` seconds; `<= 0`: no cap
  * @param mouse
  *   report [[Mouse]] events (mouse capture)
  * @param paste
  *   bracketed paste: a paste arrives as one [[Paste]] event
  * @param focusEvents
  *   report [[FocusGained]] and [[FocusLost]]
  * @param ctrlCQuits
  *   Ctrl-C stops the app before `update` sees it (in raw mode Ctrl-C sends no
  *   signal)
  * @param viewport
  *   the full screen, or an inline area below the prompt
  * @param pollMillis
  *   the input poll timeout; it bounds the quit latency
  * @param inboxCapacity
  *   how many events and values can wait for the render fiber
  * @param theme
  *   colors and styles of focus, selection and help
  * @param captureOutput
  *   while the UI is up, `System.out` and `System.err` go to the log, so that a
  *   stray `println` cannot draw over the screen. JVM-global. Scala's `println`
  *   is captured in a [[TuiApp]]; with [[Tui.run]] only when nothing used
  *   `scala.Console` before the first run
  * @param logFile
  *   where the Kyo log of the run and the captured output go (appended);
  *   `None`: the last 200 lines of output and of log lines at warn or above
  *   stay in memory and are printed to stderr after the terminal is restored.
  *   The log lines are those at or above the level of the Kyo logger where the
  *   run starts: Kyo's default is warn; set `-Dkyo.Log.defaultLevel=info` (or
  *   bind a logger with `Log.let`) to get info and debug lines
  * @param trace
  *   a file that gets one JSON line per batch of events
  */
final case class TuiOptions(
    fps: Int = 60,
    mouse: Boolean = false,
    paste: Boolean = true,
    focusEvents: Boolean = false,
    ctrlCQuits: Boolean = true,
    viewport: Viewport = Viewport.Fullscreen,
    pollMillis: Int = 25,
    inboxCapacity: Int = 1024,
    theme: Theme = Theme(),
    captureOutput: Boolean = true,
    logFile: Option[java.nio.file.Path] = None,
    trace: Option[java.nio.file.Path] = None,
) derives CanEqual

/** Where the app draws. */
enum Viewport derives CanEqual:
    /** The alternate screen: the whole terminal. */
    case Fullscreen

    /** `height` rows below the prompt; the shell output stays visible. */
    case Inline(height: Int)

/** Why a run failed. Extends `Exception`, so the ZIO and Cats Effect facades
  * use it as their error type unchanged.
  */
enum TuiError(message: String, cause: Throwable | Null)
    extends Exception(message, cause):
    /** Standard input is not a terminal, or the process has no terminal.
      * Standard output is not checked: it can be a pipe to the terminal.
      */
    case NotATerminal(error: Throwable)
        extends TuiError("stdin/stdout is not a terminal", error)

    /** The JVM does not allow the native terminal library. */
    case NativeAccessDenied(error: Throwable)
        extends TuiError(
          "start the JVM with --enable-native-access=ALL-UNNAMED",
          error,
        )

    /** Reading from or writing to the terminal failed. */
    case TerminalIO(error: Throwable)
        extends TuiError("terminal I/O failed", error)

    /** User code threw: `where` says which, for example "view" or "update on
      * key:x".
      */
    case AppFailed(where: String, error: Throwable)
        extends TuiError(s"$where threw", error)

    /** A source or task failed and has no `recover`. */
    case SourceFailed(source: String, error: Throwable)
        extends TuiError(s"source $source failed", error)
end TuiError

/** Counters of one run. Tests assert on them as performance contracts.
  *
  * @param frames
  *   frames drawn
  * @param viewBuilds
  *   calls of `view`
  * @param skippedSame
  *   batches whose final state was the same object: no `view` call, no frame
  * @param skippedEqualView
  *   new states whose view was equal to the last drawn view: no frame
  * @param batches
  *   batches of inbox items folded
  * @param events
  *   input events folded
  * @param folds
  *   values from sources and tasks folded
  * @param droppedFolds
  *   values dropped because their source had stopped
  * @param sources
  *   standing sources running now
  * @param tasks
  *   one-shot tasks running now
  * @param lastFrameMicros
  *   the time of the last draw, in microseconds
  * @param lastChangedCells
  *   the cells the last draw sent to the terminal
  */
final case class Stats(
    frames: Long,
    viewBuilds: Long,
    skippedSame: Long,
    skippedEqualView: Long,
    batches: Long,
    events: Long,
    folds: Long,
    droppedFolds: Long,
    sources: Int,
    tasks: Int,
    lastFrameMicros: Long,
    lastChangedCells: Int,
) derives CanEqual
