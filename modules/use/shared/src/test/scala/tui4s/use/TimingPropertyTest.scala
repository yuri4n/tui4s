package tui4s.use

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}

/** The laws of the timing rules of `debounced` and `throttled`, on their pure
  * steps (`Pacing`). [[TimingPropertyTest.simulate]] drives a rule as its shell
  * does: a wake-up at its time, unless a write comes first; a write cancels the
  * wake-up of the value before it.
  *
  * The laws: `debounced` shows at most one value per quiet window, each value
  * that it shows had a quiet window after it, and the last write always shows,
  * `quiet` after it came. `throttled` shows at most one value per window, the
  * first write shows at once, and the last write always shows, at most `every`
  * after it came.
  */
// claude 2026-10-19: created (L2a)
class TimingPropertyTest extends PropertyTest[Any]:
    import TimingPropertyTest.*

    "debounced" - {
      forAll(writes, period) { (ws, quiet) =>
        val out   = simulate[Pacing.Debounce.State[Int], Int](
          Pacing.Debounce.start[Int],
          (_, a, now) => Pacing.Debounce.write(a, now, quiet),
          Pacing.Debounce.wake,
          ws,
        )
        val times = writeTimes(ws)
        // At most one value per quiet window.
        assert(spaced(out, quiet))
        // Each value had a quiet window after its write (a write at the
        // time of a wake-up comes after it).
        assert(out.forall { (e, v) =>
          times.exists((w, a) =>
            a == v && w + quiet == e && !times.exists((t, _) => t > w && t < e),
          )
        })
        // The last write shows, `quiet` after it came.
        assert(out.lastOption == times.lastOption.map((w, a) => (w + quiet, a)))
      }
    }

    "throttled" - {
      forAll(writes, period) { (ws, every) =>
        val out   = simulate[Pacing.Throttle.State[Int], Int](
          Pacing.Throttle.start[Int],
          (s, a, now) => Pacing.Throttle.write(s, a, now, every),
          (s, now) => Pacing.Throttle.wake(s, now, every),
          ws,
        )
        val times = writeTimes(ws)
        // At most one value per window.
        assert(spaced(out, every))
        // The first write shows at once.
        assert(out.headOption == times.headOption)
        // The last write shows, at most `every` after it came.
        assert(out.lastOption.map(_._2) == times.lastOption.map(_._2))
        assert(
          out.lastOption.zip(times.lastOption).forall { case ((e, _), (w, _)) =>
            e >= w && e <= w + every
          },
        )
      }
    }
end TimingPropertyTest

object TimingPropertyTest:

    /** 1 to 20 writes: the time since the write before (0 to 300 ms) and a
      * value (0 to 4).
      */
    val writes: Gen[Chunk[(Duration, Int)]] =
      Gen.int
        .map(i => 1 + Math.floorMod(i, 20))
        .flatMap(n =>
          Gen.listOfN(
            n,
            Gen.zip(
              Gen.int.map(i => Math.floorMod(i, 301).millis),
              Gen.int.map(i => Math.floorMod(i, 5)),
            ),
          ),
        )

    /** The quiet time or the window: 50, 100 or 150 ms. */
    val period: Gen[Duration] = Gen.oneOf(50.millis, 100.millis, 150.millis)

    /** The times of the writes, from the epoch. */
    def writeTimes[A](ws: Chunk[(Duration, A)]): Chunk[(Instant, A)] =
      ws.foldLeft((Instant.Epoch, Chunk.empty[(Instant, A)])) {
        case ((t, acc), (gap, a)) => (t + gap, acc :+ ((t + gap, a)))
      }._2

    /** True when two values in a row are at least `d` apart. */
    def spaced[A](out: Chunk[(Instant, A)], d: Duration): Boolean =
      out.zip(out.drop(1)).forall { case ((a, _), (b, _)) => b >= a + d }

    /** The values that a rule shows for the writes `ws`, with their times. */
    def simulate[S, A](
        start: S,
        onWrite: (S, A, Instant) => Pacing.Step[S, A],
        onWake: (S, Instant) => Pacing.Step[S, A],
        ws: Chunk[(Duration, A)],
    ): Chunk[(Instant, A)] =
        final case class Sim(
            s: S,
            wake: Maybe[Instant],
            out: Chunk[(Instant, A)],
        )
        def add(out: Chunk[(Instant, A)], t: Instant, emit: Maybe[A])    =
          emit match
              case Present(v) => out :+ ((t, v))
              case Absent     => out
        // The wake-ups up to `until` (all of them when Absent).
        def drain(sim: Sim, until: Maybe[Instant], fuel: Int = 100): Sim =
          sim.wake match
              case Present(w) if fuel > 0 && until.forall(w <= _) =>
                val step = onWake(sim.s, w)
                drain(
                  Sim(step.state, step.wake, add(sim.out, w, step.emit)),
                  until,
                  fuel - 1,
                )
              case _                                              => sim
        val last                                                         = writeTimes(ws).foldLeft(Sim(start, Absent, Chunk.empty)) {
          case (sim, (t, a)) =>
            val ready = drain(sim, Present(t))
            val step  = onWrite(ready.s, a, t)
            Sim(step.state, step.wake, add(ready.out, t, step.emit))
        }
        drain(last, Absent).out
    end simulate
end TimingPropertyTest
