package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.core.backend.ClearType
import jatatui.core.backend.WindowSize
import jatatui.core.buffer.BufferUpdate
import jatatui.core.layout.Position
import jatatui.core.layout.Size

/** A backend that forwards everything to `underlying` and counts the cell
  * updates of the last draw (`Stats.lastChangedCells`). It counts while the
  * underlying backend iterates, so it copies nothing.
  */
private[tui4s] final class CountingBackend(val underlying: Backend)
    extends Backend:
    private var count = 0

    // Set by the release: a frame that is still being drawn (the shutdown
    // hook can release while the render fiber runs) writes nothing more.
    @volatile private var closed = false

    /** Stops all output: later calls that write to the terminal do nothing. */
    def close(): Unit = closed = true

    /** True after [[close]]: the release of the session started. */
    def isClosed: Boolean = closed

    /** The cell updates that the last `draw` sent. */
    def lastCount: Int = count

    def draw(content: java.lang.Iterable[BufferUpdate]): Unit =
        count = 0
        if !closed then underlying.draw(Counted(content))

    /** `content`; each iteration counts again from 0. */
    private final class Counted(content: java.lang.Iterable[BufferUpdate])
        extends java.lang.Iterable[BufferUpdate]:
        def iterator(): java.util.Iterator[BufferUpdate] =
            count = 0
            val it = content.iterator()
            new java.util.Iterator[BufferUpdate]:
                def hasNext(): Boolean   = it.hasNext()
                def next(): BufferUpdate =
                    count += 1
                    it.next()
        end iterator
    end Counted

    override def appendLines(n: Int): Unit                       =
      if !closed then underlying.appendLines(n)
    def hideCursor(): Unit                                       =
      if !closed then underlying.hideCursor()
    def showCursor(): Unit                                       =
      if !closed then underlying.showCursor()
    def getCursorPosition(): Position                            = underlying.getCursorPosition()
    def setCursorPosition(position: Position): Unit              =
      if !closed then underlying.setCursorPosition(position)
    def clear(): Unit                                            = if !closed then underlying.clear()
    def clearRegion(clearType: ClearType): Unit                  =
      if !closed then underlying.clearRegion(clearType)
    def size(): Size                                             = underlying.size()
    def windowSize(): WindowSize                                 = underlying.windowSize()
    def flush(): Unit                                            = if !closed then underlying.flush()
    def scrollRegionUp(start: Int, end: Int, lines: Int): Unit   =
      if !closed then underlying.scrollRegionUp(start, end, lines)
    def scrollRegionDown(start: Int, end: Int, lines: Int): Unit =
      if !closed then underlying.scrollRegionDown(start, end, lines)
end CountingBackend
