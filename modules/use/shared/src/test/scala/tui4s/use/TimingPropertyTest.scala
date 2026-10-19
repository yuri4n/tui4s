package tui4s.use

import kyo.*
import kyo.test.prop.{Gen, PropertyTest}

/** The laws of the timing rules of `debounced` and `throttled`, on their pure
  * steps (`Pacing`). [[TimingPropertyTest.simulate]] drives a rule as its shell
  * does: the value at the start is a write at the epoch; a wake-up comes at its
  * time, unless a write comes first; a write cancels the wake-up of the value
  * before it.
  *
  * The laws: `debounced` shows at most one value per quiet window, each value
  * that it shows had a quiet window after it, and the last write always shows,
  * `quiet` after it came. `throttled` shows at most one value per window; the
  * value at the start opens a window, and a first write after that window shows
  * at once; the last write always shows, at most `every` after it came.
  */
// claude 2026-10-19: created (L2a); changed: the value at the start is a
// write, as in the shell; 200 samples (L2a review)
class TimingPropertyTest extends PropertyTest[Any]:
    import TimingPropertyTest.*

    override protected def numSamples: Int = 200

    "debounced" - {
      forAll(writes, period) { (ws, quiet) =>
        val out   = simulate(Pacing.debounce[Int](quiet), initial, ws)
        val times = (Instant.Epoch, initial) +: writeTimes(ws)
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
        val out   = simulate(Pacing.throttle[Int](every), initial, ws)
        val times = (Instant.Epoch, initial) +: writeTimes(ws)
        // At most one value per window.
        assert(spaced(out, every))
        // The value at the start shows at once and opens a window.
        assert(out.headOption == Some((Instant.Epoch, initial)))
        // A first write after that window shows at once.
        assert(
          times
            .lift(1)
            .forall((w, a) =>
              w < Instant.Epoch + every || out.lift(1) == Some((w, a)),
            ),
        )
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

    /** The value at the start of the source (the writes have 0 to 4). */
    val initial: Int = 9

    /** The values that `rule` shows for a source that starts with `first` (a
      * write at the epoch, as in the shell) and then has the writes `ws`, with
      * their times.
      */
    // claude 2026-10-19: changed: a Rule, and the value at the start is the
    // first write (L2a review)
    def simulate[S, A](
        rule: Pacing.Rule[S, A],
        first: A,
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
                val step = rule.wake(sim.s, w)
                drain(
                  Sim(step.state, step.wake, add(sim.out, w, step.emit)),
                  until,
                  fuel - 1,
                )
              case _                                              => sim
        val writes                                                       =
          (Instant.Epoch, first) +: writeTimes(ws)
        val last                                                         =
          writes.foldLeft(Sim(rule.start, Absent, Chunk.empty)) {
            case (sim, (t, a)) =>
              val ready = drain(sim, Present(t))
              val step  = rule.write(ready.s, a, t)
              Sim(step.state, step.wake, add(ready.out, t, step.emit))
          }
        drain(last, Absent).out
    end simulate
end TimingPropertyTest
