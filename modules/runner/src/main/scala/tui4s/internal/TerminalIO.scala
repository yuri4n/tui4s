// scalafix:off DisableSyntax
// EDGE: the crossterm terminal modes
// claude 2026-10-17: created this header (purity plan, phase P0)
package tui4s.internal

import jatatui.core.backend.Backend
import tui.crossterm.{ClearType, Command}
import tui4s.*

import java.io.PrintStream
import scala.util.control.NonFatal

/** The real terminal: a [[TerminalControl]] over [[Crossterm]].
  *
  * `enter` does the first steps of the acquire, and pushes the release of each
  * step to the stack of the run in the same step: (1) load the native library,
  * (2) start the output capture (release: stop it and print the R3 warning),
  * (3) raw mode (`NotATerminal` when stdin is not a terminal or raw mode fails;
  * release: leave raw mode, then flush), (4) the alternate screen, clear it,
  * hide the cursor, and the bracketed paste mode when the options ask for it
  * (release: leave the modes and show the cursor). The [[Session]] then starts
  * the input thread on [[input]] and makes the canvas. The stack runs the
  * releases in reverse: the modes, the flush, raw mode, the capture.
  *
  * @param crossterm
  *   the native calls
  * @param sink
  *   where the captured output goes
  * @param realErr
  *   the real stderr, for what is printed after the restore
  * @param consoleCaptured
  *   true when a capture also takes `scala.Console` (Scala's `println`); see
  *   [[OutputCapture.capturesConsole]]
  */
// claude 2026-10-19: changed (P3, A1, A2): one release step per acquire step
// on the stack of the run; no undo list, no `leave`, no hook of its own
private[tui4s] final class TerminalIO(
    crossterm: Crossterm,
    sink: LogSink,
    realErr: () => PrintStream = () => OutputCapture.realErr,
    consoleCaptured: () => Boolean = () => OutputCapture.capturesConsole,
) extends TerminalControl:
    private def err: PrintStream = realErr()

    // claude 2026-10-19: changed (P3, A1): each step pushes its release
    def enter(opts: TuiOptions, stack: ReleaseStack): Backend =
        crossterm.load()
        if opts.captureOutput then
            val c    = OutputCapture.start(sink)
            // A println that the capture cannot take is a warning (IR R3), in
            // the log now and on stderr after the restore. The print runs
            // after the restore, by design: a 'tui4s: allowed' marker.
            val warn = !consoleCaptured()
            stack.add("output capture") { () =>
              c.stop()
              if warn then
                  err.println(TerminalIO.ConsoleNotCaptured) // tui4s: allowed
                  err.flush()
            }
            // claude 2026-10-19: changed (P6): LogSink.Keep, not a Boolean
            if warn then
                sink.line(TerminalIO.ConsoleNotCaptured, LogSink.Keep.FileOnly)
        end if
        // crossterm takes /dev/tty when stdin is not a terminal, so raw mode
        // alone does not see a piped or redirected stdin.
        if !crossterm.stdinIsTerminal() then
            throw TuiError.NotATerminal(
              java.io.IOException("stdin is not a terminal"),
            )
        try crossterm.enableRawMode()
        catch case NonFatal(e) => throw TuiError.NotATerminal(e)
        stack.add("raw mode")(() => crossterm.disableRawMode())
        stack.add("flush")(() => crossterm.flush())
        // The push comes after the commands, also after a failure: a failing
        // command list can be half done, so its release leaves all the modes.
        // (A push before the commands would leave them before they are entered
        // when the stack is already closed.)
        val failed: Option[Throwable] =
          try
              crossterm.execute(TerminalIO.enterCommands(opts))
              None
          catch
              case e: VirtualMachineError => throw e
              case e: Throwable           => Some(e)
        stack.add("terminal modes")(() =>
          crossterm.execute(TerminalIO.leaveCommands(opts)),
        )
        failed.foreach {
          case e if NonFatal(e) => throw TuiError.TerminalIO(e)
          case e                => throw e
        }
        crossterm.backend()
    end enter

    override def input(): Option[InputPort] = Some(CrosstermInput(crossterm))
end TerminalIO

private[tui4s] object TerminalIO:
    /** The R3 warning: `scala.Console` was in use before the run. */
    // claude 2026-10-13: created (T1, RC-10 part)
    val ConsoleNotCaptured: String =
      "tui4s: scala.Console was in use before the run started, so its " +
        "output was not captured during the run and could write over the " +
        "screen. Start the run before the first use of scala.Console."

    /** Step 4 of the acquire: the alternate screen (cleared: not every terminal
      * clears it), the hidden cursor, and the bracketed paste mode for
      * `TuiOptions.paste`. No mouse and no focus events (D-30), no inline
      * viewport (D-22).
      */
    // claude 2026-10-13: changed (T1): the full screen only; Clear (the
    // prototype's enter); no mouse and no focus modes
    def enterCommands(opts: TuiOptions): Seq[Command] =
        val b = Seq.newBuilder[Command]
        b += Command.EnterAlternateScreen()
        b += Command.Clear(ClearType.All)
        b += Command.Hide()
        if opts.paste then b += Command.EnableBracketedPaste()
        b.result()

    /** The release of step 4: the modes of [[enterCommands]] in reverse. */
    // claude 2026-10-13: changed (T1): the full screen only; no mouse and no
    // focus modes
    def leaveCommands(opts: TuiOptions): Seq[Command] =
        val b = Seq.newBuilder[Command]
        if opts.paste then b += Command.DisableBracketedPaste()
        b += Command.Show()
        b += Command.LeaveAlternateScreen()
        b.result()
end TerminalIO
