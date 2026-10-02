package tui4s

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*
import kyo.test.*
import tui4s.internal.LogSink
import tui4s.internal.OutputCapture

/** SPEC 5.10: the sink of a run and the capture of `System.out` / `err`.
  * Sequential: the capture is JVM-global.
  */
class OutputCaptureTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    private def stream(): (ByteArrayOutputStream, PrintStream) =
        val bytes = ByteArrayOutputStream()
        (bytes, PrintStream(bytes, true, StandardCharsets.UTF_8))

    "the ring keeps the newest important lines and counts the rest" in {
      val sink        = LogSink(None, capacity = 3)
      (1 to 5).foreach(i => sink.line(s"line $i", important = true))
      sink.line("debug detail", important = false)
      val (bytes, ps) = stream()
      sink.finish(ps)
      val text        = bytes.toString(StandardCharsets.UTF_8).linesIterator.toVector
      assert(
        text == Vector(
          "tui4s: 2 earlier output lines are not shown",
          "line 3",
          "line 4",
          "line 5",
        ),
        text.toString,
      )
    }

    "after finish, an important line is printed at once; finish is idempotent" in {
      val sink        = LogSink(None)
      val (bytes, ps) = stream()
      sink.finish(ps)
      sink.line("late warning", important = true)
      sink.line("late detail", important = false)
      sink.finish(ps)
      assert(bytes.toString(StandardCharsets.UTF_8) == "late warning\n")
    }

    "with a file, every line goes to the file and nothing is printed" in {
      Sync.defer(Files.createTempFile("tui4s-sink", ".log")).map { file =>
        val sink        = LogSink(Some(file))
        sink.line("one", important = true)
        sink.line("two", important = false)
        val (bytes, ps) = stream()
        sink.finish(ps)
        val text        = Files.readString(file, StandardCharsets.UTF_8)
        assert(text == "one\ntwo\n", text)
        assert(bytes.size == 0)
      }
    }

    "with a file, a line after finish is still appended to the file" in {
      // Kyo writes its log on its own fiber: a line can come after the release.
      Sync.defer(Files.createTempFile("tui4s-sink", ".log")).map { file =>
        val sink        = LogSink(Some(file))
        sink.line("before", important = false)
        val (bytes, ps) = stream()
        sink.finish(ps)
        sink.line("late detail", important = false)
        sink.line("late warning", important = true)
        val text        = Files.readString(file, StandardCharsets.UTF_8)
        assert(text == "before\nlate detail\nlate warning\n", text)
        assert(bytes.size == 0, bytes.toString(StandardCharsets.UTF_8))
      }
    }

    "a log file that cannot be opened: the ring, and a note after the restore" in {
      val sink        =
        LogSink(Some(java.nio.file.Path.of("/nonexistent-tui4s/x.log")))
      sink.line("kept", important = true)
      val (bytes, ps) = stream()
      sink.finish(ps)
      val text        = bytes.toString(StandardCharsets.UTF_8)
      assert(text.contains("kept") && text.contains("cannot be written"), text)
    }

    "System.out and System.err go to the sink while a capture runs" in {
      val sink    = LogSink(None)
      val capture = OutputCapture.start(sink)
      java.lang.System.out.println("to out")
      java.lang.System.err.print("partial")
      java.lang.System.err.print(" line\r\n")
      java.lang.System.out.print("no line break")
      capture.stop()
      java.lang.System.out.println("tui4s test: after the capture")
      assert(
        sink.kept == Seq("to out", "partial line", "no line break"),
        sink.kept.toString,
      )
      assert(!capture.capturing)
    }

    "a progress line ('\\r') keeps only its last update" in {
      val sink    = LogSink(None)
      val capture = OutputCapture.start(sink)
      java.lang.System.out.print("progress 1\rprogress 2\rprogress 3\n")
      java.lang.System.out.print("windows line\r\n")
      java.lang.System.out.print("50%\r")
      capture.stop()
      assert(
        sink.kept == Seq("progress 3", "windows line", "50%"),
        sink.kept.toString,
      )
    }

    "a line is cut at MaxLine bytes, never inside a character" in {
      val sink    = LogSink(None)
      val capture = OutputCapture.start(sink)
      java.lang.System.out.print("a" * 20_000)
      java.lang.System.out.print("\u00e9" * 5_000 + "\n")
      capture.stop()
      val kept    = sink.kept
      val bytes   = kept.map(_.getBytes(StandardCharsets.UTF_8).length)
      assert(bytes.forall(_ <= OutputCapture.MaxLine), bytes.toString)
      assert(kept.mkString == "a" * 20_000 + "\u00e9" * 5_000)
      assert(!kept.exists(_.contains('\ufffd')))
    }

    "a capture restores the streams of its own start" in {
      // The program sets its own System.out between two runs.
      val real          = OutputCapture.realOut
      OutputCapture.start(LogSink(None)).stop()
      val (bytes, mine) = stream()
      java.lang.System.setOut(mine)
      val sink          = LogSink(None)
      val capture       = OutputCapture.start(sink)
      java.lang.System.out.println("captured")
      capture.stop()
      java.lang.System.out.println("after the run")
      val text          = bytes.toString(StandardCharsets.UTF_8)
      // Forward to the real stdout again, for the other tests.
      java.lang.System.setOut(real)
      OutputCapture.start(LogSink(None)).stop()
      assert(sink.kept == Seq("captured"), sink.kept.toString)
      assert(text == "after the run\n", text)
    }

    "only one capture at a time; stop is idempotent" in {
      val first  = OutputCapture.start(LogSink(None))
      val second = OutputCapture.start(LogSink(None))
      val both   = (first.capturing, second.capturing)
      second.stop()
      val still  = first.capturing
      first.stop()
      first.stop()
      assert(both == (true, false) && still && !first.capturing)
    }

    "after a capture, System.out writes to the real stdout again" in {
      val sink = LogSink(None)
      OutputCapture.start(sink).stop()
      java.lang.System.out.println("tui4s test: not in the sink")
      assert(sink.kept.isEmpty)
    }
end OutputCaptureTest
