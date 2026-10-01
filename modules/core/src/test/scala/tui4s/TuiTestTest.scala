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
        options = TuiOptions(mouse = true),
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

    "mouse and focus events need their options, as in a terminal" in {
      val t     = TuiTest(still(empty))
      val click = intercept[AssertionError](t.click(0, 0))
      assert(click.getMessage.contains("TuiOptions(mouse = true)"))
      val focus = intercept[AssertionError](t.send(FocusGained))
      assert(focus.getMessage.contains("TuiOptions(focusEvents = true)"))
      assert(t.stats.batches == 0)
      val on    = TuiTest(still(empty, TuiOptions(focusEvents = true)))
      on.send(FocusGained, FocusLost)
      assert(on.stats.events == 2)
    }

    "keys arrive as a terminal sends them" in {
      val app = App[Vector[Event]](
        Vector.empty,
        _ => empty,
        (s, e) => s :+ e,
      )
      val t   = TuiTest(app)
      t.typeText("a\tb\n")
      t.typeText("\r\n")
      t.press('\r', '\u001b', '\u007f', '\b', Key.Ctrl('S'))
      t.send(Key.Char('\t'))
      assert(
        t.state == Vector[Event](
          Key.Char('a'),
          Key.Tab,
          Key.Char('b'),
          Key.Enter,
          Key.Enter,
          Key.Enter,
          Key.Esc,
          Key.Backspace,
          Key.Backspace,
          Key.Ctrl('s'),
          Key.Tab,
        ),
      )
    }

    "resize: the next frame has the new size" in {
      val app = App[(Int, Int)](
        (0, 0),
        (w, h) => text(s"${w}x$h " + "#" * 20),
        (s, e) =>
          e match
              case Resize(w, h) => (w, h)
              case _            => s,
      )
      val t   = TuiTest(app, 30, 3)
      t.resize(12, 2)
      assert(t.state == (12, 2) && t.screen.rows == Vector("12x2 #######", ""))
      t.send(Resize(8, 1))
      assert(t.state == (8, 1) && t.screen.rows == Vector("8x1 ####"))
    }

    "resize after the end of the run: the last frame stays" in {
      val t      = TuiTest(fixtures.CounterApp, 40, 5)
      val before = t.screen
      t.press('q')
      t.resize(30, 5)
      assert(t.quit && t.screen == before && t.frames == 1)
    }

    "a failure during a resize: the last frame stays" in {
      val throwing = App[Int](
        0,
        n => text(n.toString),
        (n, e) => if e.isInstanceOf[Resize] then throw boom else n,
      )
      val update   = TuiTest(throwing, 20, 2)
      val shown    = update.screen
      update.resize(10, 1)
      assert(update.screen == shown)
      assert(
        update.error == Some(TuiError.AppFailed("update on resize:10x1", boom)),
      )
      val painting = App[Int](
        0,
        n => if n > 0 then draw((_, _) => throw boom) else text("ok"),
        (n, e) => if e.isInstanceOf[Resize] then n + 1 else n,
      )
      val paint    = TuiTest(painting, 20, 2)
      paint.resize(10, 1)
      assert(paint.error == Some(TuiError.AppFailed("paint", boom)))
      assert(paint.screen.rows == Vector("ok", ""))
    }
end TuiTestTest
