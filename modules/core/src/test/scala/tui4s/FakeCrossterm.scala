package tui4s

import jatatui.core.backend.Backend
import jatatui.core.backend.ClearType
import jatatui.core.backend.TestBackend
import jatatui.core.backend.WindowSize
import jatatui.core.buffer.BufferUpdate
import jatatui.core.layout.Position
import jatatui.core.layout.Size
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import tui.crossterm.Command
import tui.crossterm.Event as CEvent
import tui4s.internal.Crossterm
import tui4s.internal.Hooks
import tui4s.testkit.Screen

/** A terminal for the tests of the real-terminal path: it records the native
  * calls in order, draws on a `TestBackend`, and gives scripted crossterm
  * events to the input thread.
  *
  * @param failAt
  *   the call that throws: "load", "raw", "enter" (the first `execute`),
  *   "leave" (the second `execute`), "poll" (the input fails), "draw" (a draw
  *   of the backend fails) or "" for none
  * @param stdinTty
  *   what [[stdinIsTerminal]] says
  */
final class FakeCrossterm(
    width: Int = 40,
    height: Int = 10,
    failAt: String = "",
    events: Seq[CEvent] = Nil,
    stdinTty: Boolean = true,
    val log: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String](),
) extends Crossterm:
    val backendT: TestBackend = TestBackend(width, height)

    private val queue                           = LinkedBlockingQueue[CEvent]()
    events.foreach(queue.put)
    @volatile private var peeked: CEvent | Null = null
    @volatile private var executes              = 0

    /** The calls so far, in order. */
    def calls: Seq[String] = log.asScala.toVector

    /** The screen of the test backend. The read holds the backend's monitor,
      * which the runner's canvas holds while it draws: never a torn frame.
      */
    // claude 2026-10-13: changed (T1): under the backend's monitor
    def screen: Screen =
      backendT.synchronized(Screen.of(backendT.buffer(), None))

    /** Queues a crossterm event for the input thread. */
    def push(e: CEvent): Unit = queue.put(e)

    private def record(s: String): Unit =
        val _ = log.add(s)

    def load(): Unit =
        record("load")
        if failAt == "load" then
            throw TuiError.NativeAccessDenied(
              UnsatisfiedLinkError("no crossterm"),
            )

    def stdinIsTerminal(): Boolean = stdinTty

    def enableRawMode(): Unit =
        record("raw-mode-on")
        if failAt == "raw" then
            throw RuntimeException(
              "IO error: Device not configured (os error 6)",
            )

    def disableRawMode(): Unit = record("raw-mode-off")

    def execute(commands: Seq[Command]): Unit =
        executes += 1
        record("execute " + commands.mkString(","))
        if (failAt == "enter" && executes == 1) ||
            (failAt == "leave" && executes == 2)
        then throw RuntimeException("IO error: broken pipe")

    def flush(): Unit = record("flush")

    // claude 2026-10-13: changed (T1): failAt "poll"
    def poll(timeoutMillis: Int): Boolean =
      if failAt == "poll" then throw java.io.IOException("tty gone")
      else
          peeked != null || {
            val e =
              queue.poll(timeoutMillis.max(0).toLong, TimeUnit.MILLISECONDS)
            if e == null then false
            else
                peeked = e
                true
          }

    def read(): CEvent =
        val p = peeked
        peeked = null
        if p != null then p else queue.take().nn

    // claude 2026-10-13: changed (T1): failAt "draw"
    def backend(): Backend =
        record("backend")
        if failAt == "draw" then FailingDraw(backendT) else backendT
end FakeCrossterm

/** `underlying`, whose draw fails with an `IOException` (a terminal that went
  * away).
  */
// claude 2026-10-13: created (T1)
final class FailingDraw(underlying: TestBackend) extends Backend:
    def draw(content: java.lang.Iterable[BufferUpdate]): Unit    =
      throw java.io.IOException("broken pipe")
    def hideCursor(): Unit                                       = underlying.hideCursor()
    def showCursor(): Unit                                       = underlying.showCursor()
    def getCursorPosition(): Position                            = underlying.getCursorPosition()
    def setCursorPosition(position: Position): Unit              =
      underlying.setCursorPosition(position)
    def clear(): Unit                                            = underlying.clear()
    def clearRegion(clearType: ClearType): Unit                  =
      underlying.clearRegion(clearType)
    def size(): Size                                             = underlying.size()
    def windowSize(): WindowSize                                 = underlying.windowSize()
    def flush(): Unit                                            = underlying.flush()
    def scrollRegionUp(start: Int, end: Int, lines: Int): Unit   =
      underlying.scrollRegionUp(start, end, lines)
    def scrollRegionDown(start: Int, end: Int, lines: Int): Unit =
      underlying.scrollRegionDown(start, end, lines)
end FailingDraw

/** Shutdown hooks that are recorded, not registered with the JVM. */
final class FakeHooks(log: ConcurrentLinkedQueue[String]) extends Hooks:
    val added: ConcurrentLinkedQueue[Thread]   = ConcurrentLinkedQueue()
    val removed: ConcurrentLinkedQueue[Thread] = ConcurrentLinkedQueue()

    def add(hook: Thread): Unit =
        val _ = log.add("hook-added " + hook.getName)
        val _ = added.add(hook)

    def remove(hook: Thread): Unit =
        val _ = log.add("hook-removed " + hook.getName)
        val _ = removed.add(hook)
end FakeHooks

/** A stderr for tests: each line it gets is recorded as "err: <line>" in `log`,
  * after the calls of the terminal.
  */
final class FakeErr(log: ConcurrentLinkedQueue[String]):
    private val bytes = ByteArrayOutputStream()

    val stream: PrintStream = PrintStream(
      new java.io.OutputStream:
          override def write(b: Int): Unit = FakeErr.this.synchronized {
            if b == '\n' then
                val line = bytes.toString(StandardCharsets.UTF_8)
                bytes.reset()
                val _    = log.add("err: " + line)
            else bytes.write(b)
          }
      ,
      true,
      StandardCharsets.UTF_8,
    )

    /** The lines printed so far. */
    def lines: Seq[String] =
      log.asScala.toVector.collect {
        case l if l.startsWith("err: ") => l.drop(5)
      }
end FakeErr
