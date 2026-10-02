package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.core.terminal.Terminal
import java.io.PrintStream
import scala.util.control.NonFatal
import tui.crossterm.Command
import tui4s.*
import tui4s.spi.InputPort

/** JVM shutdown hooks, so that tests can see them. */
private[tui4s] trait Hooks:
    def add(hook: Thread): Unit

    /** Removes `hook`; does nothing while the JVM shuts down. */
    def remove(hook: Thread): Unit

private[tui4s] object Hooks:
    /** The JVM's own hooks. */
    val runtime: Hooks = new Hooks:
        def add(hook: Thread): Unit    =
          java.lang.Runtime.getRuntime.nn.addShutdownHook(hook)
        def remove(hook: Thread): Unit =
          try discard(java.lang.Runtime.getRuntime.nn.removeShutdownHook(hook))
          catch case _: IllegalStateException => () // shutdown in progress
end Hooks

/** The real terminal (SPEC 5.9): a [[TerminalControl]] over [[Crossterm]].
  *
  * `enter` does the first steps of the acquire: (1) load the native library,
  * (2) start the output capture, (3) raw mode (`NotATerminal` when stdin is not
  * a terminal or raw mode fails), (4) the alternate screen (or not, for an
  * inline viewport), hide the cursor, and the bracketed paste, mouse and focus
  * modes that the options ask for. A failing step undoes the earlier ones. The
  * [[Session]] then creates the jatatui terminal (5), starts the input thread
  * on [[input]] (6) and calls [[opened]], which registers the shutdown hook
  * (7).
  *
  * `leave` is the end of the release, after the input thread stopped: (3) leave
  * the modes and show the cursor (below an inline viewport), (4) leave raw mode
  * and flush, (5) stop the output capture and print what it kept, (6) remove
  * the shutdown hook. Every step runs even if an earlier one failed; the first
  * error is printed to the real stderr at the end.
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
  */
private[tui4s] final class TerminalIO(
    crossterm: Crossterm,
    sink: LogSink,
    hooks: Hooks = Hooks.runtime,
    realErr: () => PrintStream = () => OutputCapture.realErr,
    restored: () => Unit = () => (),
) extends TerminalControl:
    @volatile private var capture: OutputCapture.Capture | Null = null
    @volatile private var hook: Thread | Null                   = null
    @volatile private var terminal: Terminal[?] | Null          = null
    @volatile private var inHook                                = false

    private def err: PrintStream = realErr()

    def enter(opts: TuiOptions): Backend =
        var undo = List.empty[() => Unit]
        try
            crossterm.load()
            if opts.captureOutput then
                val c = OutputCapture.start(sink)
                capture = c
                undo = (() => c.stop()) :: undo
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
              undo.foreach(step =>
                try step()
                catch case NonFatal(_) => (),
              )
              sink.finish(err)
              throw e
        end try
    end enter

    override def input(): Option[InputPort] = Some(CrosstermInput(crossterm))

    override def opened(terminal: Terminal[?], release: () => Unit): Unit =
        this.terminal = terminal
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

    def leave(opts: TuiOptions): Unit =
        var first: Throwable | Null = null
        def step(f: => Unit): Unit  =
          try f
          catch
              case NonFatal(e) =>
                if first == null then first = e
        step(crossterm.execute(TerminalIO.leaveCommands(opts)))
        opts.viewport match
            case Viewport.Inline(_)  =>
              // The cursor goes to the line after the viewport, so the shell
              // prompt comes below the last frame.
              step {
                val t = terminal
                if t != null then
                    val area = t.getFrame().area()
                    crossterm.execute(
                      Seq(
                        Command.MoveTo(0, (area.y + area.height - 1).max(0)),
                        Command.Print("\r\n"),
                      ),
                    )
                end if
              }
            case Viewport.Fullscreen => ()
        end match
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
        val e                       = first
        if e != null then
            err.println(s"tui4s: restoring the terminal failed: $e")
            err.flush()
        restored()
    end leave
end TerminalIO

private[tui4s] object TerminalIO:
    /** Step 4 of the acquire. */
    def enterCommands(opts: TuiOptions): Seq[Command] =
        val b = Seq.newBuilder[Command]
        if opts.viewport == Viewport.Fullscreen then
            b += Command.EnterAlternateScreen()
        b += Command.Hide()
        if opts.paste then b += Command.EnableBracketedPaste()
        if opts.mouse then b += Command.EnableMouseCapture()
        if opts.focusEvents then b += Command.EnableFocusChange()
        b.result()
    end enterCommands

    /** Step 3 of the release: the modes of [[enterCommands]] in reverse. */
    def leaveCommands(opts: TuiOptions): Seq[Command] =
        val b = Seq.newBuilder[Command]
        if opts.focusEvents then b += Command.DisableFocusChange()
        if opts.mouse then b += Command.DisableMouseCapture()
        if opts.paste then b += Command.DisableBracketedPaste()
        b += Command.Show()
        if opts.viewport == Viewport.Fullscreen then
            b += Command.LeaveAlternateScreen()
        b.result()
    end leaveCommands
end TerminalIO
