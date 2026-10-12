package tui4s.stress

import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** The split probe (RC-30; the IR SplitProbe is the model). In C2 the event is
  * a write, not a key: (a) "first write": a fresh app, a write at once after
  * the start; (b) "steady": one app, many writes. The probe measures the time
  * until the screen shows the value and sorts a round into: at once (the first
  * frame after the write shows it), late (within 300 ms), repaired (within 1500
  * ms: the 1 s repair tick) or lost.
  */
// claude 2026-10-12: created
class SplitProbe extends StressSuite:

    final case class Round(ms: Long, firstFrameShows: Boolean)

    private def report(name: String, rs: Seq[Round]): Unit < Sync = Sync.defer {
      val atOnce   = rs.count(_.firstFrameShows)
      val late     = rs.count(r => !r.firstFrameShows && r.ms >= 0 && r.ms <= 300)
      val repaired =
        rs.count(r => !r.firstFrameShows && r.ms > 300 && r.ms <= 1500)
      val lost     = rs.count(r => r.ms < 0 || r.ms > 1500)
      report(
        f"split $name%-12s rounds=${rs.size}%4d atOnce=$atOnce%4d late300ms=$late%3d repaired1500ms=$repaired%3d lost=$lost%3d maxMs=${rs.map(_.ms).max}%5d",
      )
    }

    /** One write of `v` to `n`; the ms until the screen shows it. */
    private def round(h: Headless, n: SignalRef[Int], v: Int)(using
        Frame,
    ): Round < Async =
      for
          _  <- h.drainFrames
          t0 <- now
          _  <- n.set(v)
          f  <- h.nextFrame
          s0 <- h.screen
          ok <- h.awaitScreen(_.contains(s"n=$v"), 2.seconds)
          t1 <- now
      yield Round(
        if ok then (t1 - t0) / 1_000_000 else -1L,
        s0.contains(s"n=$v"),
      )

    probe("first write after the start, and steady writes") in {
      val rounds0 = rounds.getOrElse(200)
      for
          first  <- Kyo.foreach(1 to rounds0)(_ =>
                      Scope.run(
                        for
                            n <- Signal.initRef(0)
                            h <- Headless.start(40, 3)(_ =>
                                   div(span(n.map(v => s"n=$v"))),
                                 )
                            r <- round(h, n, 1)
                        yield r,
                      ),
                    )
          _      <- report("first write", first)
          steady <- Scope.run(
                      for
                          n  <- Signal.initRef(0)
                          h  <- Headless.start(40, 3)(_ =>
                                  div(span(n.map(v => s"n=$v"))),
                                )
                          rs <- Kyo.foreach(1 to rounds0)(i => round(h, n, i))
                      yield rs,
                    )
          _      <- report("steady", steady)
      yield assert(
        first.forall(_.ms >= 0) && steady.forall(_.ms >= 0),
        "a lost write",
      )
      end for
    }
end SplitProbe
