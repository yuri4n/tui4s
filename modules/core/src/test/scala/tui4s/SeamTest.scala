package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.Knobs
import tui4s.testkit.Headless

/** R2 (D-16, armkey): a write that lands in the window of a young watcher
  * reaches the screen through the verify pass, not through the 1 s repair. The
  * seam holds the window of every watcher open for 200 ms after the start
  * (spike S2, `r2-external` is the model).
  */
// claude 2026-10-12: created (RC-9)
class SeamTest extends RunnerSuite:

    /** Every watcher sleeps 500 ms after its arm step (n = 0): longer than a
      * cold first frame, so the write below is inside the window.
      */
    private val seam =
      Knobs(seam = n => if n == 0 then 500.millis else Duration.Zero)

    final case class Refs(
        a: SignalRef[Int],
        t: SignalRef[String],
        l: SignalRef[Chunk[Int]],
    )

    private def refs(using Frame): Refs < Sync =
      for
          a <- Signal.initRef(0)
          t <- Signal.initRef("t0;")
          l <- Signal.initRef(Chunk(0))
      yield Refs(a, t, l)

    /** A Reactive span, a bound input, a Foreach, and a nested region. */
    private def app(r: Refs)(using Frame): UI =
      div(
        span(r.a.map(n => s"[a=$n]")),
        input.value(r.t).style(_.width(20.px)),
        div(r.l.foreach(n => span(s"[f=$n]"))),
        div(r.a.render(n => span(r.t.map(s => s"[n=$n:$s]")))),
      )

    "R2: an EXTERNAL write in the arm window is on the screen when the window ends (the verify pass), not at the 1 s repair" in {
      Knobs.let(seam) {
        for
            r  <- refs
            t0 <- now
            h  <- Headless.start(60, 12)(_ => app(r))
            // every watcher has started and sleeps in its window
            _  <- Async.sleep(50.millis)
            _  <- r.a.set(1).andThen(r.t.set("t1;")).andThen(r.l.set(Chunk(1)))
            ok <- awaitAll(h, 3.seconds)("[a=1]", "t1;", "[f=1]", "[n=1:t1;]")
            t1 <- now
            ms  = (t1 - t0) / 1_000_000
        yield assert(
          ok && ms <= 700,
          s"the screen was right after $ms ms (ok=$ok)",
        )
      }
    }

    "R2 cascade: one write in the window gives at most 2 frames in 900 ms" in {
      Knobs.let(seam) {
        for
            r  <- refs
            h  <- Headless.start(60, 12)(_ => app(r))
            _  <- Async.sleep(50.millis)
            _  <- h.drainFrames
            _  <- r.a.set(1)
            _  <- Async.sleep(900.millis)
            fs <- h.drainFrames
            s  <- h.screen
        yield assert(
          s.contains("[a=1]") && s.contains("[n=1:t0;]") && fs.size <= 2,
          s"frames after the write: ${fs.size} (${fs
              .map(f => (f.cause, f.updates, f.repaired))}); screen:\n$s",
        )
      }
    }
end SeamTest
