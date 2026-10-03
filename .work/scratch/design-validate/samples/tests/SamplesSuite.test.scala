package examples

import examples.counter.CounterApp
import examples.dashboard.DashboardApp
import examples.todo.Item
import examples.todo.TodoApp
import kyo.*
import munit.FunSuite
import tui4s.*
import tui4s.testkit.*

/** SPEC 6.3 example tests (sync level), written with munit. */
class CounterSuite extends FunSuite:
  test("update is a pure function") {
    assertEquals(CounterApp.update(1, Key.Char('+')), 2)
    assert(CounterApp.update(1, Key.Char('q')) == Quit)
    assertEquals(CounterApp.update(1, Key.Char('x')), 1)
  }

  test("keys drive the rendered buffer (headless TestBackend)") {
    val t = TuiTest(CounterApp, 40, 6).press('+', '+')
    assertEquals(t.state, 2)
    assert(t.screen.contains("Count: 2"), t.screen.text)
    assert(t.screen.contains("+ increment · - decrement · q quit"), t.screen.text)
    t.press('-')
    assert(t.screen.contains("Count: 1"), t.screen.text)
    assertEquals(t.frames, 4L)
  }

  test("a key that changes nothing builds no view and draws no frame") {
    val t      = TuiTest(CounterApp)
    val before = t.stats
    t.press('x', Key.Up)
    assert(t.stats.viewBuilds == before.viewBuilds && t.stats.frames == before.frames, t.stats)
    assert(t.lastTrace.contains("\"frame\":\"skip-same\""), t.lastTrace)
  }

  test("q quits; Ctrl-C quits") {
    assert(TuiTest(CounterApp).press('q').quit)
    assert(TuiTest(CounterApp).press(Key.Ctrl('c')).quit)
  }

  test("a golden screen") {
    val t = TuiTest(CounterApp, 40, 5).press('+')
    val expected =
      """╭ Counter ─────────────────────────────╮
        |│                                      │
        |│               Count: 1               │
        |│  + increment · - decrement · q quit  │
        |╰──────────────────────────────────────╯""".stripMargin
    val diff = diffScreen(t.screen, expected)
    assert(diff.isEmpty, diff.getOrElse("") + "\n" + t.screen.text)
  }

  test("styles: the count is bold yellow") {
    val t        = TuiTest(CounterApp, 40, 5).press('+')
    val (x, y)   = t.screen.find("Count: 1").get
    val style    = t.screen.styleAt(x + 7, y)
    val expected = Style.default.bold.fg(Color.Yellow)
    assertEquals(style.toString, expected.toString)
  }

  test("view equality: x.size(3).bordered(t) == x.bordered(t).size(3)") {
    val x = text("a")
    assertEquals(x.size(3).bordered("t"), x.bordered("t").size(3))
    assertEquals(gauge(0.5, "50%"), gauge(0.5, "50%"))
  }

  test("plain match version") {
    val t = TuiTest(examples.counter.CounterMatchApp, 20, 3).press('+', '+', '-')
    assertEquals(t.state, 1)
    assert(t.screen.contains("Count: 1"), t.screen.text)
  }
end CounterSuite

class TodoSuite extends FunSuite:
  test("loads at start, then add, toggle, delete") {
    val t = TuiTest(TodoApp, 48, 12)
    assertEquals(t.sources, Seq("load"))
    t.emit("load", Result.succeed(Vector(Item("buy milk"))))
    assert(t.sources.isEmpty && t.state.items.size == 1, (t.sources, t.state))
    t.typeText("call bob")
    t.press(Key.Enter, Key.Tab, ' ')
    assertEquals(t.state.items.map(_.done), Vector(false, true))
    assert(t.screen.contains("✔ call bob"), t.screen.text)
    t.press('d')
    assertEquals(t.state.items.map(_.title), Vector("buy milk"))
  }

  test("'d' and 'q' in the text field are text") {
    val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item])).typeText("dq")
    assert(!t.quit && t.state.field.text == "dq" && t.cursor.isDefined, (t.quit, t.state, t.cursor))
  }

  test("Ctrl-S starts one save task and folds its result") {
    val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item])).press(Key.Ctrl('s'))
    assert(t.started == Seq("save") && t.state.status == "saving…", (t.started, t.state.status))
    t.emit("save", Result.succeed(()))
    assertEquals(t.state.status, "saved")
  }

  test("the help bar shows the keys of the focused pane") {
    val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item]))
    assert(t.screen.contains("enter add") && !t.screen.contains("d delete"), t.screen.text)
    t.press(Key.Tab)
    assert(t.screen.contains("d delete"), t.screen.text)
    assertEquals(TodoApp.itemKeys.explain(Key.Char('d')), "'d' -> delete")
  }
end TodoSuite

class DashboardSuite extends FunSuite:
  test("a sample moves the gauge; pause stops the poller") {
    val t = TuiTest(DashboardApp, 72, 16).emit("cpu", 0.42)
    assert(t.screen.contains("42%"), t.screen.text)
    assertEquals(t.sources, Seq("logs", "cpu"))
    t.press('p')
    assert(t.sources == Seq("logs") && t.screen.contains("CPU (paused)"), (t.sources, t.screen.text))
  }

  test("a stream element folds into the log pane") {
    val t = TuiTest(DashboardApp, 72, 16).emit("logs", "GET /api/items/1 200")
    assert(t.screen.contains("GET /api/items/1 200"), t.screen.text)
  }
end DashboardSuite

class GuardSuite extends FunSuite:
  test("Keymap rejects a key bound twice") {
    val e = intercept[IllegalArgumentException](
      Keymap[Int](Bind[Int]('a', "x")(identity), Bind[Int]('a', "y")(identity))
    )
    assert(e.getMessage.contains("'a'"), e.getMessage)
  }

  test("Bind('a', \"x\")(identity) infers S from the expected type") {
    val b: Bind[Int] = Bind('a', "x")(identity)
    assertEquals(b.keys, Seq(Key.Char('a')))
  }

  test("CallSite is the caller's position") {
    val here = summon[CallSite]
    assert(here.show.startsWith("SamplesSuite.test.scala:"), here.show)
  }

  test("a core source label is the call site") {
    val s = Source.For[Int].every(1.second)(_ + 1)
    assert(s.label.startsWith("SamplesSuite.test.scala:"), s.label)
  }

  test("NotForeign: compileErrors sees the macro error (typer-only check?)") {
    val errs = compileErrors("tui4s.Source.For[Int].task(new zio.ZIO[Any, Nothing, Int])((s, _) => s)")
    // Recorded as a finding either way; the negative compile of NotForeignNeg.scala is the real check.
    println(s"[guard] compileErrors(ZIO task) = '${errs.linesIterator.take(2).mkString(" / ")}'")
  }
  test("NotForeign error vs a fold type error: which one does the user see?") {
    val errs = compileErrors("tui4s.Source.For[Int].task(new zio.ZIO[Any, Nothing, Int])((s: String, _) => s)")
    println(s"[guard-order] ${errs.replace("\n", " | ")}")
  }

  test("NotForeign also guards poll and rejects scala.concurrent.Future") {
    val errs = compileErrors(
      "tui4s.Source.For[Int].poll(kyo.Duration.fromNanos(1))(scala.concurrent.Future.successful(1))((s, _) => s)"
    )
    assert(errs.contains("A Kyo app runs Kyo computations only"), errs)
  }
end GuardSuite
