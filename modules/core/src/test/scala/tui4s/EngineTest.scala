package tui4s

import kyo.*
import kyo.test.*
import tui4s.internal.*

/** The engine and the supervisor at the level of tokens: generation tokens,
  * keyed tasks, chunks (SPEC 5.5, 5.8, 7.2 C5 and C6).
  */
class EngineTest extends Test[Any]:
    private val s = Source.For[Int]

    private def engine(
        sources: Int => Seq[Source[Int]],
        update: (Int, Event) => Next[Int] = (n, _) => n,
    ): Engine[Int] =
        val app = App[Int](0, n => text(n.toString), update, sources)
        val e   =
          Engine(app, app.options, Painter(Theme()), Supervisor[Int](), null)
        require(e.start() && e.syncWork())
        e
    end engine

    /** The token of the running generation `label`. */
    private def token(e: Engine[Int], label: String): Long =
      e.sup.byLabel(label).nn.token

    "C5: a value from a stopped source is dropped and counted" in {
      val e   = engine(
        n => if n == 0 then Seq(s.every(1.second)(_ + 1).id("a")) else Nil,
        (n, _) => n + 100,
      )
      val old = token(e, "a")
      e.sup.toStart.clear()
      assert(e.fold(Seq(Fold(old, ()))) == Step.Changed && e.state == 1)
      assert(e.syncWork()) // n = 1 declares nothing: "a" stops
      assert(e.sup.toStop.map(_.source.label) == Seq("a"))
      assert(e.fold(Seq(Fold(old, ()))) == Step.Same)
      assert(e.state == 1 && e.stats.droppedFolds == 1 && e.stats.folds == 1)
      // A late Done of the stopped generation finds no token either.
      assert(
        e.fold(Seq(Done(old, Result.fail(new RuntimeException)))) == Step.Same,
      )
      assert(e.error == null)
    }

    "C5: a replaced keyed task's late value and Done are dropped" in {
      def task(n: Int) = s.task(Kyo.lift(n))((x, a: Int) => x + a).id("k")
      val e            = engine(
        _ => Nil,
        (n, ev) =>
          ev match
              case Key.Char(c) => n.withTask(task(c - '0'))
              case _           => n,
      )
      assert(e.fold(Seq(Key.Char('1'))) == Step.WorkOnly && e.syncWork())
      val first        = e.sup.toStart.last.token
      assert(e.fold(Seq(Key.Char('2'))) == Step.WorkOnly && e.syncWork())
      val second       = e.sup.toStart.last.token
      assert(e.sup.toStop.map(_.token) == Seq(first))
      assert(
        e.fold(
          Seq(Fold(first, 10), Done(first, Result.succeed(()))),
        ) == Step.Same,
      )
      assert(e.stats.droppedFolds == 1 && e.state == 0)
      assert(
        e.fold(
          Seq(Fold(second, 2), Done(second, Result.succeed(()))),
        ) == Step.Changed,
      )
      assert(e.state == 2 && e.stats.tasks == 0 && e.sup.idle)
    }

    "a chunk folds element by element and stops at a quit" in {
      val e  = engine(_ =>
        Seq(
          s.stream(Stream.init(Seq(1)))((n, a: Int) =>
            if a < 0 then Quit else n + a,
          ).id("st"),
        ),
      )
      val st = token(e, "st")
      assert(
        e.fold(Seq(Fold(st, Chunk(1, 2, 3)))) == Step.Changed && e.state == 6,
      )
      assert(e.fold(Seq(Fold(st, Chunk(10, -1, 100)))) == Step.Quit)
      assert(e.state == 16 && e.quitReason == "fold")
    }

    "items after a quit are not folded" in {
      val e = engine(_ => Seq(s.every(1.second)(_ + 1).id("a")))
      val a = token(e, "a")
      assert(
        e.fold(Seq(Fold(a, ()), Stop, Fold(a, ()), Key.Char('x'))) == Step.Quit,
      )
      assert(e.state == 1 && e.quitReason == "stop" && e.stats.events == 0)
    }

    "an input failure ends the run with its error" in {
      val e   = engine(_ => Nil)
      val err = TuiError.TerminalIO(new java.io.IOException("gone"))
      assert(e.fold(Seq(Failed(err))) == Step.Failed && e.error == err)
    }

    "C6: 10 000 one-shot tasks that complete leave the supervisor empty" in {
      val sup  = Supervisor[Int]()
      val task = s.task(Kyo.lift(1))((n, a: Int) => n + a)
      (1 to 10000).foreach { i =>
        sup.start(Seq(if i % 2 == 0 then task.id("k") else task))
        val r = sup.toStart.last
        sup.toStart.clear()
        sup.finished(r.token)
      }
      assert(sup.idle && sup.taskCount == 0 && sup.toStop.isEmpty)
    }

    "the diff keeps the generation of a kept label and takes its newest declaration" in {
      val sup   = Supervisor[Int]()
      val first = s.every(1.second)(_ + 1).id("x")
      sup.sync(Seq(first))
      val r     = sup.toStart.head
      sup.toStart.clear()
      val newer = s.every(1.second)(_ + 2).id("x")
      sup.sync(Seq(newer))
      assert(sup.toStart.isEmpty && sup.toStop.isEmpty)
      assert(sup.live(r.token).nn.source eq newer)
      sup.sync(Nil)
      assert(sup.toStop.map(_.token) == Seq(r.token) && sup.idle)
    }

    "suffixes: the second and third source with one label get #1 and #2" in {
      val a = s.every(1.second)(identity).id("a")
      val b = s.every(1.second)(identity).id("b")
      assert(Supervisor.suffixed(Seq(a, b)).map(_.label) == Seq("a", "b"))
      assert(
        Supervisor.suffixed(Seq(a, b, a, a, b)).map(_.label) ==
          Seq("a", "b", "a#1", "a#2", "b#1"),
      )
    }
end EngineTest
