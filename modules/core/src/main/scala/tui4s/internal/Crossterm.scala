package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.crossterm.CrosstermBackend
import scala.util.control.NonFatal
import tui.crossterm.Command
import tui.crossterm.CrosstermJni
import tui.crossterm.Duration as CDuration
import tui.crossterm.Event as CEvent
import tui4s.*
import tui4s.spi.InputPort

/** The native terminal calls that a run makes, so that [[TerminalIO]] can be
  * tested without a terminal. [[NativeCrossterm]] is the real one.
  *
  * The input thread is the only caller of [[poll]] and [[read]]; the render
  * fiber and the release call the rest.
  */
private[tui4s] trait Crossterm:
    /** Loads the native library. Throws `NativeAccessDenied` when the JVM does
      * not allow it.
      */
    def load(): Unit

    /** Raw mode: no line buffering, no echo, no signals from Ctrl-C. */
    def enableRawMode(): Unit

    /** Leaves raw mode. */
    def disableRawMode(): Unit

    /** Runs `commands` and writes them out. */
    def execute(commands: Seq[Command]): Unit

    /** Writes out queued output. */
    def flush(): Unit

    /** True when an event is ready within `timeoutMillis`. */
    def poll(timeoutMillis: Int): Boolean

    /** The next event. Call only after [[poll]] returned true. */
    def read(): CEvent

    /** The jatatui backend that draws through this terminal. */
    def backend(): Backend
end Crossterm

/** The real terminal through jatatui's crossterm JNI library. */
private[tui4s] final class NativeCrossterm extends Crossterm:
    // Set by `load`; every other call comes after it.
    @volatile private var jni: CrosstermJni | Null = null

    private def native: CrosstermJni =
        val j = jni
        if j == null then throw IllegalStateException("crossterm is not loaded")
        j

    def load(): Unit =
      if jni == null then
          try jni = new CrosstermJni()
          catch case e: Throwable => throw NativeCrossterm.loadError(e)

    def enableRawMode(): Unit                 = native.enableRawMode()
    def disableRawMode(): Unit                = native.disableRawMode()
    def execute(commands: Seq[Command]): Unit =
      native.execute(java.util.Arrays.asList(commands*))
    def flush(): Unit                         = native.flush()

    def poll(timeoutMillis: Int): Boolean =
        val ms = timeoutMillis.max(0)
        native.poll(CDuration(ms / 1000L, (ms % 1000) * 1_000_000))

    def read(): CEvent = native.read()

    def backend(): Backend = CrosstermBackend(native)
end NativeCrossterm

private[tui4s] object NativeCrossterm:
    /** The [[TuiError]] for a failure to load the native library. The library
      * loads in a static initializer, so its error arrives wrapped in an
      * `ExceptionInInitializerError`, and a second try gives a
      * `NoClassDefFoundError`.
      */
    def loadError(e: Throwable): Throwable =
        val cause = e match
            case i: ExceptionInInitializerError if i.getCause != null =>
              i.getCause.nn
            case other                                                => other
        cause match
            case _: IllegalCallerException | _: UnsatisfiedLinkError |
                _: NoClassDefFoundError =>
              TuiError.NativeAccessDenied(e)
            case NonFatal(_) | _: LinkageError => TuiError.TerminalIO(e)
            case fatal                         => fatal
    end loadError
end NativeCrossterm

/** crossterm as the [[InputPort]] of the input thread (SPEC 5.3): `poll` with
  * the poll timeout, then `read`, mapped by [[EventMapping]].
  */
private[tui4s] final class CrosstermInput(crossterm: Crossterm)
    extends InputPort:
    def poll(timeoutMillis: Int): Boolean = crossterm.poll(timeoutMillis)
    def read(): Event | Null              = EventMapping(crossterm.read())
