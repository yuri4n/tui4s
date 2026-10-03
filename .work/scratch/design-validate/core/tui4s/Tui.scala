package tui4s

import kyo.*

object Tui:
  /** Runs `app` in the real terminal until it quits. Returns the last state. The terminal is restored before this
    * computation completes, also on failure and on interruption (5.9). Needs a TTY.
    */
  def run[S](app: App[S])(using Frame): S < (Async & Abort[TuiError]) =
    tui4s.internal.Runner.run(app, tui4s.internal.CrosstermControl())

  /** Starts `app` on its own fiber and returns a handle (G10). The end of the scope stops the app. */
  def start[S](app: App[S])(using Frame): TuiHandle[S] < (Async & Scope) =
    tui4s.internal.Runner.start(app, tui4s.internal.CrosstermControl())
end Tui

final class TuiHandle[S] private[tui4s] (
    inbox: Channel[tui4s.internal.Item],
    fiber: Fiber[S, Abort[TuiError]],
    statesRef: Signal.SignalRef[S],
    statsNow: () => Stats
):
  /** Like a fold, from outside; dropped after the end. */
  def update(f: S => Next[S])(using Frame): Unit < Async =
    Abort.run[Closed](inbox.put(tui4s.internal.Fold(tui4s.internal.Fold.HandleToken, f))).unit

  /** A synthetic input event. */
  def send(event: Event)(using Frame): Unit < Async = Abort.run[Closed](inbox.put(event)).unit

  /** The state after each batch (equal states deduplicated). */
  def states: Signal[S] = statesRef

  /** A snapshot of the counters (read without a lock while the app runs). */
  def stats(using Frame): Stats < Sync = Sync.defer(statsNow())

  /** The result of the run. */
  def await(using Frame): S < (Async & Abort[TuiError]) = fiber.get

  /** Like Quit; returns when the terminal is restored. */
  def stop(using Frame): Unit < Async =
    Abort.run[Closed](inbox.put(tui4s.internal.Stop)).andThen(fiber.getResult).unit
end TuiHandle

/** A main class for Kyo users: `object CounterApp extends TuiApp[Int]`. Not a KyoApp (KyoApp.Base.main is final). */
abstract class TuiApp[S] extends App[S]:
  spi.SchedulerDefaults.applyIfUnset() // first statement: before any user val touches Kyo

  private var _args: Seq[String] = Nil

  /** Runs Tui.run(this); after the restore runs onExit; prints a failure to stderr; exit code 0 or 1. */
  final def main(args: Array[String]): Unit =
    spi.SchedulerDefaults.applyIfUnset()
    _args = args.toSeq
    import AllowUnsafe.embrace.danger
    val result = KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Tui.run(this).map(s => onExit(s).andThen(s)))
    result match
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        java.lang.System.err.println(e.getMessage)
        java.lang.System.exit(1)
      case Result.Panic(e) =>
        e.printStackTrace()
        java.lang.System.exit(1)
  end main

  protected final def args: Seq[String] = _args

  /** Source constructors with `S` fixed. */
  protected final def source: Source.For[S] = Source.For[S]

  /** Runs after the terminal is restored: print a result, write a file. */
  protected def onExit(@annotation.unused state: S): Unit < (Async & Abort[Throwable]) = ()
end TuiApp

final case class TuiOptions(
    fps: Int = 60,                              // at most one frame per 1/fps; <= 0: no cap (live tests)
    mouse: Boolean = false,                     // EnableMouseCapture
    paste: Boolean = true,                      // EnableBracketedPaste: a paste is one Paste event
    focusEvents: Boolean = false,               // EnableFocusChange: FocusGained / FocusLost
    ctrlCQuits: Boolean = true,                 // Ctrl-C quits before update sees it (raw mode: no SIGINT)
    viewport: Viewport = Viewport.Fullscreen,
    pollMillis: Int = 25,                       // input poll timeout; bounds the quit latency
    inboxCapacity: Int = 1024,
    theme: Theme = Theme(),
    captureOutput: Boolean = true,              // System.out / System.err go to the log while the UI is up
    logFile: Option[java.nio.file.Path] = None, // Kyo Log + captured output; None: memory ring
    trace: Option[java.nio.file.Path] = None    // JSON lines, one per batch (5.16)
) derives CanEqual

enum Viewport derives CanEqual:
  case Fullscreen          // alternate screen
  case Inline(height: Int) // jatatui Viewport.inline(height), below the prompt

enum TuiError(message: String, cause: Throwable | Null) extends Exception(message, cause):
  case NotATerminal(error: Throwable) extends TuiError("stdin/stdout is not a terminal", error)
  case NativeAccessDenied(error: Throwable)
      extends TuiError("start the JVM with --enable-native-access=ALL-UNNAMED", error)
  case TerminalIO(error: Throwable)                   extends TuiError("terminal I/O failed", error)
  case AppFailed(where: String, error: Throwable)     extends TuiError(s"$where threw", error)
  case SourceFailed(source: String, error: Throwable) extends TuiError(s"source $source failed", error)

/** Counters of one run; the performance contracts of 7.2 assert on them. */
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
    lastChangedCells: Int
) derives CanEqual
