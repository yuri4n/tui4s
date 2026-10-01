package tui4s

import kyo.test.*
import tui4s.internal.Engine
import tui4s.internal.Painter
import tui4s.internal.Step
import tui4s.testkit.*

class NextTest extends Test[Any]:
    private val save = new Source[Int]("save", null, keyed = true)

    private def engine(update: (Int, Event) => Next[Int]): Engine[Int] =
        val app = App[Int](0, n => dsl.text(n.toString), update)
        val e   = Engine(app, app.options, Painter(Theme()))
        require(e.start())
        e

    "the shapes of Next" in {
      assert(Quit(5) == Next.Then(5, Nil, quit = true))
      assert(5.withTask(save) == Next.Then(5, Seq(save), quit = false))
      assert(5.withTask() == Next.Then(5, Nil, quit = false))
      val plain: Next[Int] = 5
      assert(plain == 5)
    }

    "a plain state replaces the state" in {
      val e = engine((n, _) => n + 1)
      assert(e.fold(Seq(Key.Char('a'), Key.Char('b'))) == Step.Changed)
      assert(e.state == 2)
    }

    "Quit stops with the current state and folds nothing after it" in {
      val e = engine((n, k) => if k == Key.Char('q') then Quit else n + 1)
      assert(
        e.fold(Seq(Key.Char('a'), Key.Char('q'), Key.Char('b'))) == Step.Quit,
      )
      assert(e.state == 1 && e.stats.events == 2)
    }

    "Quit(last) stops with the state last" in {
      val e = engine((_, _) => Quit(42))
      assert(e.fold(Seq(Key.Char('a'))) == Step.Quit)
      assert(e.state == 42)
    }

    "withTask sets the state and keeps the tasks in order" in {
      val other = new Source[Int]("other", null, keyed = false)
      val e     = engine((n, _) => (n + 1).withTask(save, other))
      assert(e.fold(Seq(Key.Char('a'))) == Step.Changed)
      assert(e.state == 1)
      assert(e.takePending() == Seq(save, other))
      assert(e.takePending().isEmpty)
    }

    "the same state with new tasks is work, not a change" in {
      val e = engine((n, _) => n.withTask(save))
      assert(e.fold(Seq(Key.Char('a'))) == Step.WorkOnly)
      assert(e.takePending() == Seq(save))
      assert(e.fold(Seq(Resize(1, 1))) == Step.Changed) // a resize always draws
    }

    "the runtime splits Next the same way (TuiTest)" in {
      val app = App[Int](
        0,
        n => dsl.text(n.toString),
        (n, e) =>
          e match
              case Key.Char('t') => (n + 1).withTask(save)
              case Key.Char('q') => Quit(n * 10)
              case Key.Char('x') => Quit
              case _             => n,
      )
      val t   = TuiTest(app, 10, 1)
      t.press('t', 't')
      assert(t.state == 2 && !t.quit && t.screen.row(0) == "2")
      t.press('q')
      assert(t.state == 20 && t.quit)
      assert(t.screen.row(0) == "2") // a quit draws no frame
      val u = TuiTest(app, 10, 1)
      u.press('t', 'x')
      assert(u.state == 1 && u.quit)
    }

    "a source has the identity of its label" in {
      assert(save == new Source[Int]("save", null, keyed = false))
      assert(save.hashCode == "save".hashCode)
      assert(save.id("load").label == "load" && save.id("load").keyed)
      assert(save.recover((n, _) => n).label == "save")
      assert(save.toString == "Source(save)")
    }
end NextTest
