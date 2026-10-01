package tui4s

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.test.*
import tui4s.testkit.*

/** The exact watcher of `Source.signal` on a `SignalRef` (SPEC 5.8, G14). */
class WatcherTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    /** An app whose state is the last value it saw from `ref`. */
    private def follower(ref: Signal.SignalRef[Int]): App[Int] =
      App[Int](
        -1,
        n => text(n.toString),
        (n, _) => n,
        _ => Seq(Source.For[Int].signal(ref)((_, v) => v).id("ref")),
      )

    private def eventually(max: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Boolean < Async =
      Loop(0) { n =>
        cond.map { ok =>
          if ok then Loop.done(true)
          else if n >= max.toMillis then Loop.done(false)
          else Async.sleep(1.millis).andThen(Loop.continue(n + 1))
        }
      }

    /** 4 fibers write 2 500 distinct values each; the last value arrives. */
    private def round(
        r: Int,
    )(using Frame): Boolean < (Async & Abort[Throwable]) =
      Signal.initRef(0).map { ref =>
        Scope.run(
          spi.Headless
            .start(follower(ref), TestBackend(10, 1), ScriptedInput.empty)
            .map { h =>
              Kyo
                .foreach(0 until 4)(w =>
                  Fiber.initUnscoped(
                    Kyo.foreachDiscard(1 to 2500)(i =>
                      ref.set(r * 100000 + w * 10000 + i),
                    ),
                  ),
                )
                .map(fibers => Kyo.foreachDiscard(fibers)(_.get))
                .andThen(ref.get)
                .map(last =>
                  eventually(5.seconds)(h.states.current.map(_ == last)),
                )
            },
        )
      }

    "10 000 writes from 4 fibers: the last value always arrives".timeout(
      120.seconds,
    ) in {
      Kyo
        .foreach(1 to 10)(round)
        .map(results => assert(results.forall(identity), results.toString))
    }

    "the current value comes first, and an equal write sends nothing".timeout(
      30.seconds,
    ) in {
      Signal.initRef(7).map { ref =>
        val app = App[Vector[Int]](
          Vector.empty,
          v => text(v.mkString(",")),
          (v, _) => v,
          _ => Seq(Source.For[Vector[Int]].signal(ref)(_ :+ _).id("ref")),
        )
        Scope.run(
          spi.Headless.start(app, TestBackend(20, 1), ScriptedInput.empty).map {
            h =>
              eventually(5.seconds)(h.states.current.map(_ == Vector(7)))
                .andThen(ref.set(7))
                .andThen(ref.set(8))
                .andThen(
                  eventually(5.seconds)(
                    h.states.current.map(_.lastOption.contains(8)),
                  ),
                )
                .andThen(Async.sleep(50.millis))
                .andThen(h.states.current)
                .map(v => assert(v == Vector(7, 8)))
          },
        )
      }
    }
end WatcherTest
