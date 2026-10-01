package tui4s

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*
import kyo.test.*
import scala.jdk.CollectionConverters.*
import tui4s.fixtures.*
import tui4s.internal.EventCode
import tui4s.internal.Json
import tui4s.testkit.*

/** The trace line format (SPEC 5.16), `lastTrace`, the `slow` line (G16) and
  * replay.
  */
class TraceTest extends Test[Any]:
    private val number = "[0-9]+"
    private val time   = "\"t\":[0-9]+\\.[0-9]{3}"

    "the first line says the size and the declared sources" in {
      // A cold first `sources(init)` can be slower than one frame and write a
      // slow line before the start line: warm it up once.
      val _ = TuiTest(DashboardApp, 72, 16)
      val t = TuiTest(DashboardApp, 72, 16)
      assert(
        t.trace.head.matches(
          s"""\\{$time,"kind":"start","size":"72x16","sources":\\["logs","cpu"\\]\\}""",
        ),
      )
    }

    "a batch that draws: input, state, frame, cells and times" in {
      val t = TuiTest(CounterApp, 40, 5)
      t.press('+')
      assert(
        t.lastTrace.matches(
          s"""\\{$time,"kind":"batch","in":\\["key:\\+"\\],"state":"changed",""" +
            s""""frame":"drawn","frameMicros":$number,"cells":1,"batchMicros":$number\\}""",
        ),
      )
    }

    "a batch with an equal view; a fold; a diff; a task" in {
      val t = TuiTest(DashboardApp, 72, 16)
      t.emit("cpu", 0.0) // the gauge shows 0% again, the sparkline one bar
      assert(
        t.lastTrace.contains(
          "\"in\":[],\"folds\":[\"cpu\"],\"state\":\"changed\"",
        ),
      )
      val u    = TuiTest(App[Int](0, _ => text("same"), (n, _) => n + 1))
      u.press('a')
      assert(
        u.lastTrace.contains(
          "\"state\":\"changed\",\"frame\":\"skip-equal-view\"",
        ),
      )
      t.press('p')
      assert(t.lastTrace.contains("\"stop\":[\"cpu\"]"))
      t.press('p')
      assert(t.lastTrace.contains("\"start\":[\"cpu\"]"))
      val todo = TuiTest(TodoApp)
      todo.press(Key.Ctrl('s'))
      assert(
        todo.lastTrace.contains("\"state\":\"changed\",\"tasks\":[\"save\"]"),
      )
    }

    "the last line says why the app quit" in {
      val q = TuiTest(CounterApp)
      q.press('q')
      assert(
        q.lastTrace.contains(
          "\"in\":[\"key:q\"],\"state\":\"quit\",\"frame\":\"skip-quit\"",
        ),
      )
      assert(
        q.trace.last.matches(s"""\\{$time,"kind":"quit","reason":"update"\\}"""),
      )
      val c = TuiTest(CounterApp)
      c.press(Key.Ctrl('c'))
      assert(c.trace.last.contains("\"reason\":\"ctrl-c\""))
      val f = TuiTest(
        App[Int](
          0,
          n => text(n.toString),
          (_, _) => throw IllegalStateException("x\"y"),
        ),
      )
      f.press('a')
      assert(
        f.trace.last.contains(
          "\"reason\":\"error\",\"error\":\"update on key:a threw\"",
        ),
      )
    }

    "user code slower than one frame writes a slow line" in {
      val t    = TuiTest(
        App[Int](
          0,
          n => text(n.toString),
          (n, e) =>
            if e == Key.Char('s') then
                Thread.sleep(30)
                n + 1
            else n,
        ),
      )
      t.press('x')
      assert(!t.trace.exists(_.contains("\"kind\":\"slow\"")))
      t.press('s')
      val slow = t.trace.filter(_.contains("\"kind\":\"slow\""))
      assert(slow.size == 1)
      assert(
        slow.head.matches(
          s"""\\{$time,"kind":"slow","where":"update on key:s","micros":$number\\}""",
        ),
      )
      assert(t.lastTrace.contains("\"in\":[\"key:s\"]"))
    }

    "event codes read back as the events" in {
      val events = Seq[Event](
        Key.Char('+'),
        Key.Char(' '),
        Key.Char('"'),
        Key.Ctrl('s'),
        Key.Alt('x'),
        Key.Enter,
        Key.BackTab,
        Key.Up,
        Key.F(12),
        Key.Modified(Key.Up, Mods(shift = true)),
        Key.Modified(Key.Left, Mods(ctrl = true, alt = true)),
        Paste("héllo\nwörld"),
        Resize(80, 24),
        Mouse(MouseKind.Down(Button.Left), 3, 4, Mods(), Nil),
        Mouse(MouseKind.ScrollUp, 1, 2, Mods(), Nil),
        FocusGained,
        FocusLost,
      )
      events.foreach { e =>
        val code = EventCode(e)
        assert(EventCode.decode(code) == Some(e), s"$e -> $code")
        val line = s"""{"in":${Json.arr(Seq(code))}}"""
        assert(Json.strings(line, "in") == Some(Vector(code)))
      }
      assert(
        EventCode.decode("nothing").isEmpty && EventCode.decode("key:").isEmpty,
      )
    }

    "replay feeds the traced input back, batch by batch" in {
      val t    = TuiTest(TodoApp, 48, 12)
      t.emit("load", Result.succeed(Vector(Item("one"))))
      t.typeText("two")
      t.press(Key.Enter, Key.Tab, ' ')
      t.paste("x")
      val file = Files.createTempFile("tui4s-trace", ".jsonl")
      Files.write(file, t.trace.asJava, StandardCharsets.UTF_8)
      val r    = TuiTest.replay(TodoApp, file, 48, 12)
      // Folds are not replayed: the load never came, so only the typed item.
      assert(r.state.items.map(_.title) == Vector("two"))
      assert(r.state.items.map(_.done) == Vector(true))
      assert(r.stats.batches == t.stats.batches - 1)
      Files.delete(file)
    }
end TraceTest
