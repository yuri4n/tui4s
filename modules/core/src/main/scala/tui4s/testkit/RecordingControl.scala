package tui4s.testkit

import jatatui.core.backend.Backend
import jatatui.core.backend.TestBackend
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
    /** The test terminal. A folded `Resize` resizes it. */
    val backend: TestBackend = TestBackend(width, height)

    private val log = new ConcurrentLinkedQueue[String]()

    /** The steps so far, in order. */
    def events: Seq[String] = log.asScala.toVector

    /** The last drawn frame. */
    def screen: Screen = Screen.of(backend.buffer(), None)

    def enter(opts: TuiOptions): Backend =
        record("enter")
        backend

    override def step(name: String): Unit = record(name)

    override def resize(width: Int, height: Int): Unit =
      backend.resize(width, height)

    def leave(opts: TuiOptions): Unit = record("leave-raw-mode")

    private def record(step: String): Unit =
        val _ = log.add(step)
end RecordingControl
