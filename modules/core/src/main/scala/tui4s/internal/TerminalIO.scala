package tui4s.internal

import jatatui.core.backend.Backend
import java.io.PrintStream
import scala.util.control.NonFatal
import tui.crossterm.ClearType
import tui.crossterm.Command
import tui4s.*

/** JVM shutdown hooks, so that tests can see them. */
private[tui4s] trait Hooks:
    def add(hook: Thread): Unit

    /** Removes `hook`; does nothing while the JVM shuts down. */
    def remove(hook: Thread): Unit

    /** True when the JVM shuts down: it runs its shutdown hooks (SIGTERM,
      * SIGINT, `System.exit`) and takes no new hook.
      */
    // claude 2026-10-13: created (T2, RC-24): TuiApp prints nothing and skips
    // onExit when the JVM stops the app
    def shuttingDown: Boolean
end Hooks

private[tui4s] object Hooks:
    /** The JVM's own hooks. */
    val runtime: Hooks = new Hooks:
        def add(hook: Thread): Unit    =
          java.lang.Runtime.getRuntime.nn.addShutdownHook(hook)
        def remove(hook: Thread): Unit =
          try discard(java.lang.Runtime.getRuntime.nn.removeShutdownHook(hook))
          catch
              case _: IllegalStateException => () // shutdown in progress
        // claude 2026-10-13: created (T2, RC-24): the JVM refuses a new hook
        // once it runs its hooks; the probe is a thread that does nothing
        def shuttingDown: Boolean      =
            val probe = Thread(() => (), "tui4s-probe")
            try
                java.lang.Runtime.getRuntime.nn.addShutdownHook(probe)
                discard(
                  java.lang.Runtime.getRuntime.nn.removeShutdownHook(probe),
                )
                false
            catch case _: IllegalStateException => true
        end shuttingDown
end Hooks

/** The real terminal: a [[TerminalControl]] over [[Crossterm]].
  *
  * `enter` does the first steps of the acquire: (1) load the native library,
  * (2) start the output capture, (3) raw mode (`NotATerminal` when stdin is not
  * a terminal or raw mode fails), (4) the alternate screen, clear it, hide the
  * cursor, and the bracketed paste mode when the options ask for it. A failing
  * step undoes the earlier ones. The [[Session]] then makes the canvas (5),
  * starts the input thread on [[input]] (6) and calls [[opened]], which
  * registers the shutdown hook (7).
  *
  * `leave` is the end of the release, after the input thread stopped: (3) leave
  * the modes and show the cursor, (4) leave raw mode and flush, (5) stop the
  * output capture and print what it kept, (6) remove the shutdown hook. Every
  * step runs even if an earlier one failed; the first error is printed to the
  * real stderr at the end.
  *
  * @param crossterm
  *   the native calls
  * @param sink
  *   where the captured output goes; finished by the release
  * @param hooks
  *   the JVM shutdown hooks
  * @param realErr
  *   the real stderr, for what is printed after the restore
  * @param restored
  *   called at the end of [[leave]]: the terminal is restored
  * @param consoleCaptured
  *   true when a capture also takes `scala.Console` (Scala's `println`); see
  *   [[OutputCapture.capturesConsole]]
  */
private[tui4s] final class TerminalIO(
    crossterm: Crossterm,
    sink: LogSink,
    hooks: Hooks = Hooks.runtime,
    realErr: () => PrintStream = () => OutputCapture.realErr,
    restored: () => Unit = () => (),
    // claude 2026-10-13: created (T1, RC-10 part): the R3 check, for the tests
    consoleCaptured: () => Boolean = () => OutputCapture.capturesConsole,
) extends TerminalControl:
    @volatile private var capture: OutputCapture.Capture | Null = null
    @volatile private var hook: Thread | Null                   = null
    @volatile private var inHook                                = false
    // claude 2026-10-13: created (T1, RC-10 part): the R3 warning of this run
    @volatile private var consoleWarning: String | Null         = null

    private def err: PrintStream = realErr()

    def enter(opts: TuiOptions): Backend =
        var undo = List.empty[() => Unit]
        try
            crossterm.load()
            if opts.captureOutput then
                val c = OutputCapture.start(sink)
                capture = c
                undo = (() => c.stop()) :: undo
                // claude 2026-10-13: created (T1, RC-10 part, IR R3): a
                // println that the capture cannot take is a warning, in the
                // log now and on stderr after the restore
                if !consoleCaptured() then
                    consoleWarning = TerminalIO.ConsoleNotCaptured
                    sink.line(TerminalIO.ConsoleNotCaptured, important = false)
            end if
            // crossterm takes /dev/tty when stdin is not a terminal, so raw
            // mode alone does not see a piped or redirected stdin.
            if !crossterm.stdinIsTerminal() then
                throw TuiError.NotATerminal(
                  java.io.IOException("stdin is not a terminal"),
                )
            try crossterm.enableRawMode()
            catch case NonFatal(e) => throw TuiError.NotATerminal(e)
            undo = (() => crossterm.disableRawMode()) :: undo
            // A failing command list can be half done: leave all its modes.
            undo = (() => crossterm.execute(TerminalIO.leaveCommands(opts))) ::
              undo
            try crossterm.execute(TerminalIO.enterCommands(opts))
            catch case NonFatal(e) => throw TuiError.TerminalIO(e)
            crossterm.backend()
        catch
            case e: Throwable =>
              // claude 2026-10-13: changed (PR #14 review: Codex,
              // TerminalIO.scala:138): an undo step that fails with any error
              // but a fatal JVM error does not stop the other undo steps
              undo.foreach(step =>
                try step()
                catch
                    case e: VirtualMachineError => throw e
                    case _: Throwable           => (),
              )
              sink.finish(err)
              throw e
        end try
    end enter

    override def input(): Option[InputPort] = Some(CrosstermInput(crossterm))

    // claude 2026-10-13: changed (T1): no jatatui Terminal (the runner's Canvas
    // draws on the backend)
    override def opened(release: () => Unit): Unit =
        val h = Thread(
          () =>
              inHook = true
              release()
          ,
          "tui4s-restore",
        )
        hooks.add(h)
        hook = h
    end opened

    // claude 2026-10-13: changed (T1): no inline viewport step (D-22)
    def leave(opts: TuiOptions): Unit =
        var first: Throwable | Null = null
        /* claude 2026-10-13: changed (PR #14 review: Codex,
         * TerminalIO.scala:138): */
        // every error but a fatal JVM error is kept and the next steps still
        // run, also a LinkageError of a native call (NonFatal does not match
        // it); as the input thread does. Before, a LinkageError in the first
        // step skipped disableRawMode and left the shell in raw mode.
        def step(f: => Unit): Unit  =
          try f
          catch
              case e: VirtualMachineError => throw e
              case e: Throwable           =>
                if first == null then first = e
        step(crossterm.execute(TerminalIO.leaveCommands(opts)))
        step(crossterm.disableRawMode())
        step(crossterm.flush())
        step {
          val c = capture
          if c != null then c.stop()
        }
        step(sink.finish(err))
        step {
          val h = hook
          if h != null && !inHook then hooks.remove(h)
        }
        // claude 2026-10-13: created (T1, RC-10 part): the R3 warning, once.
        // The two prints below run after the restore, by design: a 'tui4s:
        // allowed' marker at each
        step {
          val w = consoleWarning
          consoleWarning = null
          if w != null then
              err.println(w) // tui4s: allowed
              err.flush()
        }
        val e                       = first
        if e != null then
            val problem = s"tui4s: restoring the terminal failed: $e"
            err.println(problem) // tui4s: allowed
            err.flush()
        restored()
    end leave
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

    /** Step 3 of the release: the modes of [[enterCommands]] in reverse. */
    // claude 2026-10-13: changed (T1): the full screen only; no mouse and no
    // focus modes
    def leaveCommands(opts: TuiOptions): Seq[Command] =
        val b = Seq.newBuilder[Command]
        if opts.paste then b += Command.DisableBracketedPaste()
        b += Command.Show()
        b += Command.LeaveAlternateScreen()
        b.result()
end TerminalIO
