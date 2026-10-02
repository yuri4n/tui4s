package tui4s.interop.zio

import _root_.scala.annotation.unused
import _root_.tui4s.internal.OutputCapture
import _root_.tui4s.spi.SchedulerDefaults
import _root_.zio.Scope
import _root_.zio.ZIO
import _root_.zio.ZIOAppArgs
import _root_.zio.ZIOAppDefault

/** A main class for a ZIO app: `object CounterApp extends TuiApp[Int]`. It is a
  * `ZIOAppDefault` whose `run` runs the app in the real terminal with
  * [[Tui.run]], then [[onExit]] with the last state.
  *
  * The constructor runs before the app's own fields, and it prepares the JVM:
  * it sets the Kyo scheduler settings of `tui4s.spi.SchedulerDefaults` that the
  * user did not set (less idle CPU; `-Dtui4s.schedulerDefaults=false` turns
  * this off), and it installs the streams that capture `System.out` and
  * `System.err` while the UI is up (see `TuiOptions.captureOutput`).
  *
  * ZIO's default console loggers are off while the UI is up, and on again after
  * the restore: a failure of the run is then logged by ZIO as usual and the
  * exit code is 1 (without a terminal: "stdin/stdout is not a terminal").
  * SIGTERM and Ctrl-C from outside (`kill -INT`) interrupt `run` through ZIO's
  * shutdown hook; the terminal is restored before the JVM exits.
  *
  * Start the JVM with `--enable-native-access=ALL-UNNAMED`.
  *
  * @tparam S
  *   the state of the app
  */
abstract class TuiApp[S] extends ZIOAppDefault, _root_.tui4s.App[S]:
    SchedulerDefaults.applyIfUnset()
    OutputCapture.install()

    /** Source constructors with `S` fixed: `source.poll(1.second)(...)`. */
    protected final def source: Source.For[S] = Source.For[S]

    /** Runs the app in the terminal until it quits, then [[onExit]]. */
    final def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
      Tui.run(this).flatMap(onExit)

    /** Runs after the terminal is restored, with the last state: print a
      * result, write a file. The default does nothing.
      */
    protected def onExit(@unused state: S): ZIO[Any, Throwable, Unit] = ZIO.unit
end TuiApp
