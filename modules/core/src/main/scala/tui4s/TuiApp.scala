package tui4s

// `_root_`: see Source.scala (package tui4s and its exported dsl).
import _root_.java.io.PrintStream
import _root_.java.util.concurrent.CountDownLatch
import _root_.java.util.concurrent.TimeUnit
import _root_.kyo.*
import _root_.scala.annotation.unused
import _root_.tui4s.internal.Crossterm
import _root_.tui4s.internal.Hooks
import _root_.tui4s.internal.NativeCrossterm
import _root_.tui4s.internal.OutputCapture
import _root_.tui4s.internal.TuiRun
import _root_.tui4s.internal.discard
import _root_.tui4s.spi.SchedulerDefaults

/** A main class for a Kyo app: `object CounterApp extends TuiApp[Int]`. Its
  * `main` runs the app in the real terminal with [[Tui.run]].
  *
  * The constructor runs before the app's own fields, and it prepares the JVM:
  * it sets the Kyo scheduler settings of [[spi.SchedulerDefaults]] that the
  * user did not set (less idle CPU; `-Dtui4s.schedulerDefaults=false` turns
  * this off), and it installs the streams that capture `System.out` and
  * `System.err` while the UI is up (see [[TuiOptions.captureOutput]]).
  *
  * Start the JVM with `--enable-native-access=ALL-UNNAMED`.
  *
  * @tparam S
  *   the state of the app
  */
abstract class TuiApp[S] extends App[S]:
    SchedulerDefaults.applyIfUnset()
    OutputCapture.install()

    @volatile private var arguments: Seq[String] = Nil

    /** Runs the app in the terminal until it quits, then [[onExit]] with the
      * last state. On a failure it prints the error to stderr after the
      * terminal is restored and exits with code 1; without a terminal it prints
      * "stdin/stdout is not a terminal". SIGTERM and Ctrl-C from outside (`kill
      * -INT`) stop the app and restore the terminal.
      */
    final def main(args: Array[String]): Unit =
        val code = runMain(args, NativeCrossterm(), Hooks.runtime)
        if code != 0 then java.lang.System.exit(code)

    /** [[main]] without the exit: returns the exit code. */
    private[tui4s] final def runMain(
        args: Array[String],
        crossterm: Crossterm,
        hooks: Hooks,
        err: => PrintStream = OutputCapture.realErr,
    ): Int =
        SchedulerDefaults.applyIfUnset()
        arguments = args.toSeq
        val run = TuiRun
          .run(this, crossterm, hooks, () => err)
          .map(s => onExit(s).andThen(s))
        TuiApp.report(TuiApp.block(run, hooks), err)
    end runMain

    /** The command-line arguments of [[main]]. */
    protected final def args: Seq[String] = arguments

    /** Source constructors with `S` fixed: `source.every(1.second)(...)`. */
    protected final def source: Source.For[S] = Source.For[S]

    /** Runs after the terminal is restored, with the last state: print a
      * result, write a file. The default does nothing.
      */
    protected def onExit(@unused state: S): Unit < (Async & Abort[Throwable]) =
      ()
end TuiApp

private[tui4s] object TuiApp:
    /** Runs `program` on a fiber and blocks until it ends. While it runs, a
      * shutdown hook (SIGTERM, `kill -INT`) interrupts the fiber, whose release
      * restores the terminal, and waits for it at most 2 s.
      */
    def block[S](
        program: S < (Async & Abort[Throwable]),
        hooks: Hooks,
    )(using Frame): Result[Throwable, S] =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(
          Fiber.initUnscoped(program).map { fiber =>
            Sync.Unsafe
              .defer {
                val done = CountDownLatch(1)
                fiber.unsafe.onComplete(_ => done.countDown())
                val hook = Thread(() => stop(fiber, done), "tui4s-stop")
                hooks.add(hook)
                hook
              }
              .map(hook =>
                Sync.ensure(Sync.defer(hooks.remove(hook)))(fiber.get),
              )
          },
        )
    end block

    private def stop[A](
        fiber: Fiber[A, Abort[Throwable]],
        done: CountDownLatch,
    )(using Frame, AllowUnsafe): Unit =
        discard(fiber.unsafe.interrupt())
        discard(done.await(2, TimeUnit.SECONDS))

    /** Prints a failed run to `err` and gives the exit code. */
    def report(result: Result[Throwable, Any], err: PrintStream): Int =
        result match
            case Result.Success(_) => ()
            case Result.Failure(e) => describe(e, err)
            case Result.Panic(e)   => describe(e, err)
        err.flush()
        if result.isSuccess then 0 else 1

    private def describe(e: Throwable, err: PrintStream): Unit = e match
        case e: (TuiError.AppFailed | TuiError.SourceFailed) =>
          err.println(e.getMessage)
          val cause = e.getCause
          if cause != null then cause.printStackTrace(err)
        case e: TuiError                                     =>
          err.println(e.getMessage)
          val cause = e.getCause
          if cause != null then err.println(s"  cause: $cause")
        case _: Interrupted                                  =>
          err.println("interrupted")
        case other                                           =>
          other.printStackTrace(err)
end TuiApp
