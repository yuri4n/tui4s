package tui4s.interop.cats

import _root_.cats.effect.IO
import _root_.cats.effect.IOApp
import _root_.cats.effect.unsafe.IORuntimeConfig
import _root_.scala.annotation.unused
import _root_.scala.concurrent.duration.Duration
import _root_.tui4s.internal.OutputCapture
import _root_.tui4s.spi.SchedulerDefaults

/** A main class for a Cats Effect app: `object CounterApp extends TuiApp[Int]`.
  * It is an `IOApp.Simple` whose `run` runs the app in the real terminal with
  * [[Tui.run]], then [[onExit]] with the last state.
  *
  * The constructor runs before the app's own fields, and it prepares the JVM:
  * it sets the Kyo scheduler settings of `tui4s.spi.SchedulerDefaults` that the
  * user did not set (less idle CPU; `-Dtui4s.schedulerDefaults=false` turns
  * this off), and it installs the streams that capture `System.out` and
  * `System.err` while the UI is up (see `TuiOptions.captureOutput`).
  *
  * Cats Effect's CPU starvation checker is off: it prints to stderr, over the
  * UI. A failure ends the app after the terminal is restored; `IOApp` then
  * prints it and exits with code 1. SIGTERM and `kill -INT` cancel the app
  * through `IOApp`, and the cancel restores the terminal.
  *
  * Start the JVM with `--enable-native-access=ALL-UNNAMED`.
  *
  * @tparam S
  *   the state of the app
  */
abstract class TuiApp[S] extends IOApp.Simple, tui4s.App[S]:
    SchedulerDefaults.applyIfUnset()
    OutputCapture.install()

    private val sourceFor: Source.For[IO, S] = Source.For[IO, S]

    /** The runtime settings of `IOApp`, with the CPU starvation checker off. */
    override protected def runtimeConfig: IORuntimeConfig =
      super.runtimeConfig.copy(cpuStarvationCheckInitialDelay = Duration.Inf)

    /** Source constructors with `F = IO` and `S` fixed:
      * `source.poll(250.millis)(cpuLoad)(_.sample(_))`.
      */
    protected final def source: Source.For[IO, S] = sourceFor

    /** Runs the app in the terminal until it quits, then [[onExit]]. */
    final def run: IO[Unit] = Tui.run[IO, S](this).flatMap(onExit)

    /** Runs after the terminal is restored, with the last state: print a
      * result, write a file. The default does nothing.
      */
    protected def onExit(@unused state: S): IO[Unit] = IO.unit
end TuiApp
