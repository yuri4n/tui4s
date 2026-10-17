package examples.printing

import kyo.*
import kyo.UI.*
import tui4s.Hooks.useSignal
import tui4s.{Tui, TuiApp, TuiOptions}

import java.nio.file.Paths

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
  *
  * The app also prints one line before the run, when the object is made. It
  * comes in the terminal at once, before the UI. It is the first use of
  * `scala.Console`, which keeps the stream of that time. The constructor of
  * `TuiApp` installed the capture streams before it, so the prints while the UI
  * is up are captured all the same.
  */
// claude 2026-10-13: created (T2)
// claude 2026-10-13: changed (T2, review R1-1): the Scaladoc (the println
// before the run)
object PrintingApp extends TuiApp:
    // claude 2026-10-13: created (T2, review R1-1): a println before the run,
    // the IR output probe (R3): the PTY rows "printing" check that the later
    // prints are captured
    println("printing: before the run")

    override def options: TuiOptions = Printing.options(args)
    def ui: UI < (Async & Scope)     = Printing.ui(Tui.quit)
end PrintingApp
