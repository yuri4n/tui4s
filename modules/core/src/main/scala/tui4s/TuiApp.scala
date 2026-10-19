package tui4s

import kyo.*
import tui4s.internal.{Crossterm, Hooks, NativeCrossterm, OutputCapture}
import tui4s.spi.SchedulerDefaults

import java.io.PrintStream

/** The main class of a tui4s app: `object CounterApp extends TuiApp`. Its
  * `main` runs [[ui]] in the real terminal with [[Tui.run]] and [[options]],
  * then [[onExit]], and gives an exit code that says how the run ended.
  *
  * {{{
  * object CounterApp extends TuiApp:
  *     def ui: UI < (Async & Scope) =
  *       for count <- Signal.initRef(0)
  *       yield div.onKeyDown {
  *         case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
  *         case _                                       => ()
  *       }(span("Count: ", count.map(_.toString)))
  * }}}
  *
  * The constructor runs before the fields of the app, and it prepares the JVM:
  * it sets the Kyo scheduler values of [[spi.SchedulerDefaults]] that the user
  * did not set (Kyo reads them once, when its scheduler starts; with them the
  * runner gives fewer split frames under load and uses less CPU when the app is
  * idle; `-Dtui4s.schedulerDefaults=false` turns this off), and it installs the
  * streams that capture `System.out`, `System.err` and Scala's `println` while
  * the UI is up (see [[TuiOptions.captureOutput]]). A main of its own (a
  * `KyoApp` that calls `Tui.run`) gives the scheduler values as JVM flags.
  *
  * The exit code:
  *   - 0: the run ended with a quit ([[Tui.quit]], or the key Ctrl-C with
  *     `TuiOptions.ctrlCQuits`; in raw mode Ctrl-C is a key, not a signal) and
  *     [[onExit]] did not fail.
  *   - 1: the run or `onExit` failed. The error is printed to the real stderr
  *     after the terminal is restored: the message of a [[TuiError]] and its
  *     cause (when stdin is not a terminal: "stdin/stdout is not a terminal",
  *     before the terminal changes), "interrupted" for an interrupt, the stack
  *     trace of any other error.
  *   - 143 (`kill -TERM`), 130 (`kill -INT`), 129 (`kill -HUP`, a closed
  *     terminal window), or the code of a `System.exit` while the UI is up: the
  *     JVM stops the app. The shutdown hook of the run interrupts the run,
  *     restores the terminal and waits at most 2 s until the restore is
  *     complete; then the JVM exits with its own code (128 + the signal).
  *     `main` prints nothing then, and `onExit` does not run.
  *
  * Start the JVM with `--enable-native-access=ALL-UNNAMED` (the terminal
  * library crossterm is native).
  */
// claude 2026-10-19: changed: the Scaladoc names the one hook of the run (P3,
// A2) and kill -HUP
abstract class TuiApp:
    SchedulerDefaults.applyIfUnset()
    OutputCapture.install()

    // claude 2026-10-19: changed: a Chunk, not a Seq (P1b, A5 a)
    @volatile private var arguments: Chunk[String] = Chunk.empty // scalafix:ok DisableSyntax.var, DisableSyntax.jvmSync; KEEP: args: set once before the run

    /** The UI of the app. [[Tui.quit]] ends the run: an app whose UI is a
      * function of the quit value (as [[Tui.run]] gives it) passes `Tui.quit`
      * to it. It runs once, when the run starts; [[args]] is set then. After a
      * quit, a `Scope.ensure` inside it runs before the terminal is restored.
      */
    // claude 2026-10-13: created (T2, RC-24)
    def ui: UI < (Async & Scope)

    /** The options of the run (the default: `TuiOptions()`). Read once, when
      * the run starts; [[args]] is set then.
      */
    // claude 2026-10-13: created (T2, RC-24)
    def options: TuiOptions = TuiOptions()

    /** Runs the app in the terminal until it quits, then [[onExit]], and exits
      * with the code that the class Scaladoc gives (it returns for code 0).
      */
    // claude 2026-10-13: changed (T2, RC-24): the Scaladoc (the class has the
    // exit codes)
    final def main(args: Array[String]): Unit =
        val code = runMain(args, NativeCrossterm(), Hooks.runtime)
        // claude 2026-10-13: created (T2, review R2-4): when the JVM stops the
        // app, runMain gives 1 and this System.exit(1) blocks (the JDK's
        // Shutdown.exit holds its lock while the hooks run) until the JVM halts
        // with its own code: 128 + the signal, or the code of the app's
        // System.exit. Never replace it with Runtime.halt: that skips the
        // restore and gives 1.
        if code != 0 then java.lang.System.exit(code) // scalafix:ok DisableSyntax.jvmGlobal; KEEP: the exit code of the process
    end main

    /** [[main]] without the exit: returns the exit code (1 when the JVM stops
      * the app; the JVM then exits with its own code). The test seam: the
      * terminal of `crossterm`, the shutdown hooks `hooks`, the stderr `err`.
      */
    // claude 2026-10-19: changed (P3, A2): the one hook of the run (in
    // Tui.runWith) sets `hooks.shuttingDown`; no latch and no hook here
    private[tui4s] final def runMain(
        args: Array[String],
        crossterm: Crossterm,
        hooks: Hooks,
        err: => PrintStream = OutputCapture.realErr,
    ): Int =
        SchedulerDefaults.applyIfUnset()
        arguments = Chunk.from(args)
        val run    = Tui
          .runWith(options, crossterm, hooks, () => err)(_ => ui)
          .andThen(Sync.defer(hooks.shuttingDown))
          .map(stopping => if stopping then Kyo.unit else onExit)
        val result = TuiApp.block(run)
        if hooks.shuttingDown then 1 else TuiApp.report(result, err)
    end runMain

    /** The command-line arguments of [[main]], in order. They are set before
      * [[ui]] and [[options]] are read.
      *
      * {{{
      * object Greeter extends TuiApp:
      *     def ui: UI < (Async & Scope) =
      *       span(s"Hello, ${args.headOption.getOrElse("you")}")
      * }}}
      */
    // claude 2026-10-19: changed: a Chunk, not a Seq (P1b, A5 a)
    protected final def args: Chunk[String] = arguments

    /** Runs after the terminal is restored, after a run that ended with a quit:
      * print a result, write a file. A kyo-ui app keeps its state in its
      * signals, so `onExit` reads them where `ui` made them (for example in a
      * field of the app). It does not run when the run failed or when the JVM
      * stops the app. A failure gives the exit code 1. The default does
      * nothing.
      */
    // claude 2026-10-13: changed (T2, RC-24): no state parameter
    protected def onExit: Unit < (Async & Abort[Throwable]) = ()
end TuiApp

private[tui4s] object TuiApp:
    /** Runs `program` and blocks the calling thread (`main`) until it ends. The
      * shutdown hook of the run is in `Tui.runWith` (one hook per run).
      */
    // claude 2026-10-19: changed (P3, A2): no hook and no latch of its own
    def block[A](program: A < (Async & Abort[Throwable]))(using
        Frame,
    ): Result[Throwable, A] =
        import AllowUnsafe.embrace.danger // scalafix:ok DisableSyntax.unsafe; KEEP: main blocks its thread until the run ends
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(program)

    /** Prints a failed run to `err` and gives the exit code. The prints run
      * after the terminal is restored, by design: a 'tui4s: allowed' marker at
      * each.
      */
    // claude 2026-10-13: changed (T2): the markers
    def report(result: Result[Throwable, Any], err: PrintStream): Int =
        result match
            case Result.Success(_) => ()
            case Result.Failure(e) => describe(e, err)
            case Result.Panic(e)   => describe(e, err)
        err.flush()
        if result.isSuccess then 0 else 1

    // claude 2026-10-13: changed (T2): no AppFailed and no SourceFailed (the
    // old engine's cases; a failure of the UI comes as it is: its stack trace)
    private def describe(e: Throwable, err: PrintStream): Unit = e match
        // claude 2026-10-19: changed: the typed cause of the case, never null
        // (P1b, A4)
        case e: TuiError    =>
          err.println(e.getMessage) // tui4s: allowed
          val line = s"  cause: ${TuiError.causeOf(e)}"
          err.println(line) // tui4s: allowed
        case _: Interrupted =>
          err.println("interrupted") // tui4s: allowed
        case other =>
          other.printStackTrace(err)
end TuiApp
