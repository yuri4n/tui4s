package examples.printing

import java.nio.file.Paths
import kyo.*
import kyo.UI.*
import tui4s.Hooks.useSignal
import tui4s.Tui
import tui4s.TuiApp
import tui4s.TuiOptions

/** An app that prints while its UI is up: a line when the UI is built and a
  * line at each `+`. A print would write over the screen, so the run captures
  * `System.out`, `System.err` and Scala's `println`
  * (`TuiOptions.captureOutput`; `TuiApp` installs the capture before the app's
  * own code, so `println` is captured too). Without a log file the lines come
  * on stderr after the terminal is restored; with a log file they go into it.
  * `q` quits.
  */
// claude 2026-10-13: created (T2, RC-31 part, RC-10 part): the println row of
// the PTY kit (IR output probe), and a TuiApp that uses args and options
object Printing:

    /** The UI; `print` writes a line (Scala's `println` in the app). */
    def ui(
        quit: Unit < Sync,
        print: String => Unit < Sync = line => Sync.defer(println(line)),
    ): UI < Sync =
      for
          _     <- print("printing: the UI is built")
          count <- useSignal(0)
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('+'), _, _) =>
          count.updateAndGet(_ + 1).map(n => print(s"printing: + gives $n"))
        case KeyboardEvent(Keyboard.Char('q'), _, _) => quit
        case _                                       => ()
      }(
        h1("Printing"),
        span("Count: ", count.map(_.toString)),
        p("+ prints a line, q quits").style(_.color(Style.Color.gray)),
      )

    /** The options for the command-line arguments: `--log <file>` sends the
      * captured lines to that file.
      */
    def options(args: Seq[String]): TuiOptions = args match
        case Seq("--log", file, _*) =>
          TuiOptions(logFile = Some(Paths.get(file)))
        case _                      => TuiOptions()
end Printing

/** Runs the printing app in the real terminal. Start it with its start script,
  * not with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/printing                 # the lines come after the UI
  * examples/target/bin/printing --log out.log   # the lines go to out.log
  * }}}
  */
// claude 2026-10-13: created (T2)
object PrintingApp extends TuiApp:
    override def options: TuiOptions = Printing.options(args)
    def ui: UI < (Async & Scope)     = Printing.ui(Tui.quit)
