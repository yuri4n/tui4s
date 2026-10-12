package tui4s

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import tui4s.internal.*
import tui4s.testkit.Headless

/** The region watchers: R1 (no fiber outlives the run) and, later, the own
  * watcher loop and the repair tick.
  */
// claude 2026-10-12: created (RC-8; the S2 r1-live test is the model)
class WatcherTest extends RunnerSuite:

    /** The refs of the test app: all region kinds, and a region whose content
      * holds a nested region.
      */
    final case class Refs(
        a: SignalRef[Int],
        t: SignalRef[String],
        l: SignalRef[Chunk[Int]],
    )

    def refs(using Frame): Refs < Sync =
      for
          a <- Signal.initRef(0)
          t <- Signal.initRef("t0;")
          l <- Signal.initRef(Chunk(0))
      yield Refs(a, t, l)

    /** A Reactive span, a bound input, a Foreach, and a nested region. */
    def app(r: Refs)(using Frame): UI =
      div(
        span(r.a.map(n => s"[a=$n]")),
        input.value(r.t).style(_.width(20.px)),
        div(r.l.foreach(n => span(s"[f=$n]"))),
        div(r.a.render(n => span(r.t.map(s => s"[n=$n:$s]")))),
      )

    /** The value `i` into every ref. */
    def write(r: Refs, i: Int)(using Frame): Unit < Sync =
      r.a.set(i).andThen(r.t.set(s"t$i;")).andThen(r.l.set(Chunk(i))).unit

    /** Polls `cond` every 2 ms: the ms until it was true, or -1 after
      * `timeout`.
      */
    def waitFor(timeout: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Long < Async =
      now.map { t0 =>
        Loop.foreach {
          cond.map { ok =>
            now.map { t =>
              if ok then Loop.done((t - t0) / 1_000_000)
              else if t - t0 > timeout.toNanos then Loop.done(-1L)
              else Async.sleep(2.millis).andThen(Loop.continue)
            }
          }
        }
      }

    "R1: 0 live fibers after an INTERRUPTED run" in {
      for
          r      <- refs
          inbox  <- Channel.initUnscoped[Msg](1024)
          canvas <- Sync.defer(Canvas(TestBackend(60, 12)))
          runner  = Runner(inbox, canvas, TuiOptions(), Absent)
          fiber  <- Fiber.initUnscoped(runner.run(app(r)))
          _      <- Kyo.foreachDiscard(1 to 5)(i =>
                      write(r, i).andThen(Async.sleep(20.millis)),
                    )
          live0  <- runner.liveFibers
          _      <- fiber.interrupt
          ms     <- waitFor(2.seconds)(runner.liveFibers.map(_ == 0))
      yield
          assert(live0 >= 4, s"watchers before the interrupt: $live0")
          assert(ms >= 0L, "live fibers stay after the interrupt")
    }

    "R1: 0 live fibers after a quit, and after the scope of the kit ends" in {
      for
          r  <- refs
          h  <- Scope.run(
                  Headless.start(60, 12)(_ => app(r)).map { h =>
                    Kyo
                      .foreachDiscard(1 to 5)(i => write(r, i))
                      .andThen(awaitAll(h, 2.seconds)("[a=5]", "t5;", "[f=5]"))
                      .andThen(h.quit)
                      .andThen(h.awaitEnd(10.seconds))
                      .map(ended => assert(ended))
                      .andThen(h)
                  },
                )
          ms <- waitFor(2.seconds)(h.liveFibers.map(_ == 0))
      yield assert(ms >= 0L, "live fibers stay after the quit")
    }
end WatcherTest
