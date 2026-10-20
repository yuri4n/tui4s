package tui4s.stress

import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** The K29 measurement (ADR 0005; issue #8): the bytes that one watcher keeps
  * per value change, (1) with `Signal.observe` (the prototype's watcher, Kyo
  * 1.0.0-RC7), (2) with the runner's own loop inside a headless run (one
  * Reactive region), (3) with a bare `currentWith`/`nextWith` loop. The writes
  * come every 200 us.
  */
// claude 2026-10-12: created; 2026-10-20: changed: the Scaladoc cites ADR 0005
// and issue #8, not a path of the working notes
class ObserveLeakProbe extends StressSuite:

    /** `n` writes with `write`, then `settle`; the used heap per write. */
    private def measure(name: String, n: Int)(write: Int => Unit < Async)(
        settle: Unit < Async = Kyo.unit,
    )(using Frame): Double < Async =
      for
          k0 <- Sync.defer(usedKb())
          t0 <- now
          _  <- Kyo.foreachDiscard(1 to n)(i =>
                  write(i).andThen(Async.sleep(200.micros)),
                )
          t1 <- now
          _  <- settle
          k1 <- Sync.defer(usedKb())
          per = (k1 - k0) * 1024.0 / n
          _  <-
            Sync.defer(
              report(
                f"observe-leak $name%-26s writes=$n heapKB before=$k0%7d after=$k1%7d bytesPerWrite=$per%7.0f wallMs=${(t1 - t0) / 1_000_000}%6d",
              ),
            )
      yield per

    probe(
      "K29: bytes per write with Signal.observe, with the runner loop, with a bare loop",
    ) in {
      val n = rounds.getOrElse(150) * 200
      for
          ref1 <- Signal.initRef(0)
          seen <- AtomicInt.init(0)
          w1   <- Fiber.initUnscoped(ref1.observe(v => seen.set(v)))
          // observe needs its `nextWith` registered before the next write: wait
          // until the watcher saw the value (the ObserveBound protocol)
          obs  <- measure("Signal.observe (before)", n)(i =>
                    ref1
                      .set(i)
                      .andThen(
                        Loop.foreach(
                          seen.get.map(s =>
                            if s >= i then Loop.done(())
                            else Async.sleep(20.micros).andThen(Loop.continue),
                          ),
                        ),
                      ),
                  )()
          _    <- w1.interrupt
          ref2 <- Signal.initRef(0)
          h    <- Headless.start(40, 2)(_ => div(span(ref2.map(v => s"v=$v"))))
          // the frames of the run are drained before the heap is read: the
          // stats channel of the kit is not the watcher
          run  <- measure("runner loop (after)", n)(i => ref2.set(i))(
                    h.awaitScreen(_.contains(s"v=$n"), 10.seconds)
                      .andThen(h.drainFrames)
                      .unit,
                  )
          ref3 <- Signal.initRef(0)
          w3   <- Fiber.initUnscoped(
                    Loop(-1)(last =>
                      ref3.currentWith(cur =>
                        (if cur == last then Kyo.unit else seen.set(cur))
                          .andThen(ref3.nextWith(_ => ()))
                          .andThen(Loop.continue(cur)),
                      ),
                    ),
                  )
          bare <- measure("bare currentWith/nextWith", n)(i => ref3.set(i))()
          _    <- w3.interrupt
      yield assert(
        run < obs / 4 && bare < obs / 4,
        f"observe $obs%.0f B/write; runner loop $run%.0f; bare loop $bare%.0f",
      )
      end for
    }
end ObserveLeakProbe
