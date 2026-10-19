package examples.printing

import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp, TuiOptions}

import java.nio.file.Paths

/** An app that prints while its UI is up: a line when the UI is built and a
  * line at each `+`. A print would write over the screen, so the run captures
  * `System.out`, `System.err` and Scala's `println`
  * (`TuiOptions.captureOutput`; `TuiApp` installs the capture before the app's
  * own code, so `println` is captured too). Without a log file the lines come
  * on stderr after the terminal is restored; with a log file they go into it.
  * `q` quits. The test aid `--fail-on-f` makes the key `f` fail the UI: the run
  * ends with exit code 1, and the failure is printed after the restore (the PTY
  * row `printfail`).
  */
// claude 2026-10-13: created (T2, RC-31 part, RC-10 part): the println row of
// the PTY kit (IR output probe), and a TuiApp that uses args and options
object Printing:

    /** The UI; `print` writes a line (Scala's `println` in the app). With
      * `failOnF` (the test aid `--fail-on-f`), the key `f` shows a part whose
      * render fails, as a bug in a render would: the run ends with that
      * failure.
      */
    // claude 2026-10-19: changed: the test aid failOnF
    def ui(
        quit: Unit < Sync,
        print: String => Unit < Sync = line => Sync.defer(println(line)),
        failOnF: Boolean = false,
    ): UI < Sync =
      for
          _      <- print("printing: the UI is built")
          count  <- Signal.initRef(0)
          failed <- Signal.initRef(false)
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('+'), _, _)            =>
          count.updateAndGet(_ + 1).map(n => print(s"printing: + gives $n"))
        case KeyboardEvent(Keyboard.Char('f'), _, _) if failOnF => failed.set(true)
        case KeyboardEvent(Keyboard.Char('q'), _, _)            => quit
        case _                                                  => ()
      }(
        h1("Printing"),
        span("Count: ", count.map(_.toString)),
        when(failed)(span(count.map(_ => Printing.failure()))),
        p("+ prints a line, q quits").style(_.color(Style.Color.gray)),
      )

    /** The failure of the test aid `--fail-on-f`. */
    // claude 2026-10-19: created: the test aid of the PTY row printfail
    private def failure(): String =
      throw IllegalStateException(Printing.FailureMessage) // scalafix:ok DisableSyntax.throw; KEEP: the test aid --fail-on-f fails a render

    /** The message of the failure of `--fail-on-f`. */
    // claude 2026-10-19: created: the test aid of the PTY row printfail
    val FailureMessage: String = "printing: f failed the UI (--fail-on-f)"

    /** True when the arguments have the test aid `--fail-on-f`. */
    // claude 2026-10-19: created: the test aid of the PTY row printfail
    def failOnF(args: Chunk[String]): Boolean = args.contains("--fail-on-f")

    /** The options for the command-line arguments (`TuiApp.args`, a Chunk):
      * `--log <file>` sends the captured lines to that file.
      */
    // claude 2026-10-19: changed: the Chunk of TuiApp.args (P1b, A5 a)
    def options(args: Chunk[String]): TuiOptions = args.toSeq match
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
  * examples/target/bin/printing --fail-on-f     # f fails the UI (a test aid)
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
    // claude 2026-10-19: changed: the test aid --fail-on-f
    def ui: UI < (Async & Scope)     =
      Printing.ui(Tui.quit, failOnF = Printing.failOnF(args))
end PrintingApp
