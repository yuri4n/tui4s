package tui4s

import kyo.test.*
import tui4s.testkit.*

/** The synchronous driver records failures of user code instead of throwing. */
class TuiTestTest extends TuiSuite:
    private val boom = new RuntimeException("boom")

    "update that throws ends the run with AppFailed" in {
      val app = App[Int](
        0,
        n => text(n.toString),
        (n, e) => if e == Key.Char('x') then throw boom else n + 1,
      )
      val t   = TuiTest(app)
      t.press('a', 'x', 'a')
      assert(t.quit && t.state == 1)
      assert(t.error == Some(TuiError.AppFailed("update on key:x", boom)))
      assert(t.error.exists(_.getMessage == "update on key:x threw"))
    }

    "view that throws ends the run with AppFailed(view)" in {
      val app = App[Int](
        0,
        n => if n > 0 then throw boom else text("ok"),
        (n, _) => n + 1,
      )
      val t   = TuiTest(app)
      t.press('a')
      assert(t.quit && t.error == Some(TuiError.AppFailed("view", boom)))
      assert(t.screen.contains("ok")) // the last drawn frame
    }

    "init that throws draws nothing" in {
      val app = new App[Int]:
          def init: Int                               = throw boom
          def view(n: Int): View                      = text(n.toString)
          def update(n: Int, event: Event): Next[Int] = n
      val t   = TuiTest(app)
      assert(t.quit && t.frames == 0)
      assert(t.error == Some(TuiError.AppFailed("init", boom)))
    }

    "a paint that throws ends the run with AppFailed(paint)" in {
      val app = App[Int](
        0,
        n => if n > 0 then draw((_, _) => throw boom) else text("ok"),
        (n, _) => n + 1,
      )
      val t   = TuiTest(app)
      t.press('a')
      assert(t.quit && t.error == Some(TuiError.AppFailed("paint", boom)))
    }

    "the first frame is drawn at construction" in {
      val t = TuiTest(fixtures.CounterApp, 40, 5)
      assert(t.frames == 1 && t.stats.viewBuilds == 1 && !t.quit)
      assert(t.view == fixtures.CounterApp.view(0))
    }

    "paste and send" in {
      val app = App[String](
        "",
        s => text(s),
        (s, e) =>
          e match
              case Paste(p)    => s + p
              case Key.Char(c) => s + c
              case _           => s,
      )
      val t   = TuiTest(app, 20, 1)
      t.paste("hello ")
      t.send(Key.Char('w'), Key.Char('o'))
      assertShows(t.screen, "hello wo")
      assert(t.stats.batches == 2 && t.stats.frames == 3)
    }

    "click sends button down and up in one batch" in {
      val app = App[Vector[MouseKind]](
        Vector.empty,
        _ => empty,
        (s, e) =>
          e match
              case m: Mouse => s :+ m.kind
              case _        => s,
      )
      val t   = TuiTest(app)
      t.click(1, 2, Button.Right)
      assert(
        t.state == Vector(
          MouseKind.Down(Button.Right),
          MouseKind.Up(Button.Right),
        ),
      )
      assert(t.stats.batches == 1)
    }
end TuiTestTest
