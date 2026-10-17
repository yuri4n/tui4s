package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.crossterm.CrosstermBackend
import tui.crossterm.{
  Command,
  CrosstermJni,
  Duration as CDuration,
  Event as CEvent,
}
import tui4s.*

import java.lang.foreign.{FunctionDescriptor, Linker, ValueLayout}
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

/** The native terminal calls that a run makes, so that [[TerminalIO]] can be
  * tested without a terminal. [[NativeCrossterm]] is the real one.
  *
  * The input thread is the only caller of [[poll]] and [[read]]; the render
  * fiber and the release call the rest.
  */
private[tui4s] trait Crossterm:
    /** Loads the native library. Throws `NativeAccessDenied` when the JVM does
      * not allow it, and `NativeLoadFailed` when it cannot be loaded for
      * another reason ([[NativeCrossterm.loadError]]).
      */
    // claude 2026-10-13: changed (T1, review R1-2): NativeLoadFailed
    def load(): Unit

    /** False when stdin is not a terminal. crossterm then uses `/dev/tty` for
      * raw mode and input, so [[enableRawMode]] alone does not see a piped or
      * redirected stdin. True when it cannot be known (Windows).
      */
    def stdinIsTerminal(): Boolean

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

    /** The next event. Call only after [[poll]] returned true. `null` is
      * possible at the JNI boundary (a native method returns a reference that
      * can be null); the input thread drops it.
      */
    // claude 2026-10-13: changed (T1, strict flags: the Java boundary rule of
    // AGENTS.md): the result can be null
    def read(): CEvent | Null

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

    def stdinIsTerminal(): Boolean = Tty.isatty(0).getOrElse(true)

    def enableRawMode(): Unit                 = native.enableRawMode()
    def disableRawMode(): Unit                = native.disableRawMode()
    def execute(commands: Seq[Command]): Unit =
      native.execute(java.util.Arrays.asList(commands*))
    def flush(): Unit                         = native.flush()

    def poll(timeoutMillis: Int): Boolean =
        val ms = timeoutMillis.max(0)
        native.poll(CDuration(ms / 1000L, (ms % 1000) * 1_000_000))

    // claude 2026-10-13: changed (T1, strict flags: the Java boundary)
    def read(): CEvent | Null = native.read()

    def backend(): Backend = CrosstermBackend(native)
end NativeCrossterm

private[tui4s] object NativeCrossterm:
    /** The [[TuiError]] for a failure to load the native library. The library
      * loads in a static initializer, so its error arrives wrapped in an
      * `ExceptionInInitializerError`, and a second try gives a
      * `NoClassDefFoundError` (its cause is the first error). Only a JVM that
      * denies native access (an `IllegalCallerException` in the causes, JEP
      * 472) is `NativeAccessDenied`, whose message names
      * `--enable-native-access`; every other failure of the load (an
      * `UnsatisfiedLinkError`, an unknown platform) is `NativeLoadFailed` with
      * its cause. A fatal error of the JVM stays as it is.
      */
    // claude 2026-10-13: changed (T1, the bot fix of the review thread
    // Crossterm.scala:103): NativeLoadFailed; access denied only for an
    // IllegalCallerException
    def loadError(e: Throwable): Throwable = e match
        case _: VirtualMachineError | _: InterruptedException              => e
        case _ if causes(e).exists(_.isInstanceOf[IllegalCallerException]) =>
          TuiError.NativeAccessDenied(e)
        case NonFatal(_) | _: LinkageError                                 =>
          TuiError.NativeLoadFailed(e)
        case fatal                                                         => fatal

    /** `e` and its causes (at most 16). */
    // claude 2026-10-13: created (T1)
    private def causes(e: Throwable): List[Throwable] =
        @annotation.tailrec
        def walk(t: Throwable | Null, acc: List[Throwable]): List[Throwable] =
          if t == null || acc.size >= 16 || acc.exists(_ eq t) then acc.reverse
          else walk(t.getCause, t :: acc)
        walk(e, Nil)
end NativeCrossterm

/** `isatty` of the C library, through the foreign function API. It needs the
  * same native access as the crossterm library, so call it after that loaded.
  */
private[tui4s] object Tty:
    private lazy val handle: Option[java.lang.invoke.MethodHandle] =
      if java.lang.System
            .getProperty("os.name", "")
            .nn
            .toLowerCase
            .nn
            .contains("win")
      then None
      else
          try
              val linker = Linker.nativeLinker().nn
              val int    = ValueLayout.JAVA_INT
              linker
                .defaultLookup()
                .nn
                .find("isatty")
                .nn
                .toScala
                .map(at =>
                  linker.downcallHandle(at, FunctionDescriptor.of(int, int)).nn,
                )
          catch case NonFatal(_) => None

    /** True when `fd` is a terminal; `None` when this cannot be known. */
    def isatty(fd: Int): Option[Boolean] =
      handle.flatMap { h =>
        try
            Some(
              h.invokeWithArguments(Integer.valueOf(fd)) match
                  case n: Integer => n.intValue == 1
                  case _          => false,
            )
        catch case NonFatal(_) => None
      }
end Tty

/** crossterm as the [[InputPort]] of the input thread: `poll` with the poll
  * interval, then `read`. The input thread maps the events ([[EventMapping]]).
  */
// claude 2026-10-13: changed (T1, D-21 a): the crossterm Event, not mapped here
private[tui4s] final class CrosstermInput(crossterm: Crossterm)
    extends InputPort:
    def poll(timeoutMillis: Int): Boolean = crossterm.poll(timeoutMillis)
    def read(): CEvent | Null             = crossterm.read()
