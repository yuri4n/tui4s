package tui4s.testkit

import jatatui.core.backend.Backend
import jatatui.core.backend.ClearType
import jatatui.core.backend.TestBackend
import jatatui.core.backend.WindowSize
import jatatui.core.buffer.BufferUpdate
import jatatui.core.layout.Position
import jatatui.core.layout.Size
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import tui4s.*
import tui4s.internal.TerminalControl

/** A terminal control for tests of the run seams (`Tui.runWith`): it draws on a
  * `TestBackend` and records the steps of the terminal session in order:
  * "enter", "input-started", "input-stopped", "input-joined", "leave-raw-mode"
  * ("input-hung" when the input thread does not stop).
  *
  * @param width
  *   the columns of the test terminal
  * @param height
  *   the rows of the test terminal
  */
private[tui4s] final class RecordingControl(width: Int = 80, height: Int = 24)
    extends TerminalControl:
    /** The test terminal. A folded `Resize` resizes it. Only the render fiber
      * may read it while the app runs.
      */
    val backend: TestBackend = TestBackend(width, height)

    private val log = new ConcurrentLinkedQueue[String]()

    @volatile private var shown: Screen = Screen.of(backend.buffer(), None)

    /** The steps so far, in order. */
    def events: Seq[String] = log.asScala.toVector

    /** The last drawn frame: a copy that the render fiber takes when a frame is
      * flushed, so any thread may read it while the app draws.
      */
    def screen: Screen = shown

    def enter(opts: TuiOptions): Backend =
        record("enter")
        RecordingControl.Flushed(
          backend,
          () => shown = Screen.of(backend.buffer(), None),
        )

    override def step(name: String): Unit = record(name)

    override def resize(width: Int, height: Int): Unit =
      backend.resize(width, height)

    def leave(opts: TuiOptions): Unit = record("leave-raw-mode")

    private def record(step: String): Unit =
        val _ = log.add(step)
end RecordingControl

private[tui4s] object RecordingControl:
    /** `underlying`; after each flush (the end of a frame) `flushed` runs. */
    private final class Flushed(underlying: TestBackend, flushed: () => Unit)
        extends Backend:
        def draw(content: java.lang.Iterable[BufferUpdate]): Unit    =
          underlying.draw(content)
        override def appendLines(n: Int): Unit                       =
          underlying.appendLines(n)
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
        def flush(): Unit                                            =
            underlying.flush()
            flushed()
        def scrollRegionUp(start: Int, end: Int, lines: Int): Unit   =
          underlying.scrollRegionUp(start, end, lines)
        def scrollRegionDown(start: Int, end: Int, lines: Int): Unit =
          underlying.scrollRegionDown(start, end, lines)
    end Flushed
end RecordingControl
