package tui4s

import kyo.*
import kyo.UI.*
import tui4s.internal.Knobs
import tui4s.testkit.Headless

/** R2 (D-16, armkey): a write that lands in the window of a young watcher
  * reaches the screen through the verify pass, not through the 1 s repair. The
  * seam holds the window of every watcher open for 500 ms after the start, and
  * the repair tick is off (1 h), so only the verify pass can carry the write to
  * the screen (spike S2, `r2-external` is the model).
  */
// claude 2026-10-12: created (RC-9); changed (review R3-1, R2-9, K2-1, X-1):
// the tick is off, the leaf has no wall-clock bound, the Scaladoc says 500 ms
class SeamTest extends RunnerSuite:

    /** Every watcher sleeps 500 ms after its arm step (n = 0): longer than a
      * cold first frame, so the write below is inside the window. The repair
      * tick is off: a right screen then proves the verify pass.
      */
    private val seam = Knobs(
      seam = n => if n == 0 then 500.millis else Duration.Zero,
      repairInterval = 1.hour,
    )

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

    "R2: an EXTERNAL write in the arm window is on the screen when the window ends (the verify pass; the repair tick is off)" in {
      // No wall-clock bound: under CPU contention the first frame and the arm
      // step take longer, and with the tick off only the verify pass can make
      // the screen right (review R3-1, K2-1). The time of the leaf is in the
      // log.
      Knobs.let(seam) {
        for
            r  <- refs
            h  <- Headless.start(60, 12)(_ => app(r))
            // every watcher has started and sleeps in its window
            _  <- Async.sleep(50.millis)
            _  <- r.a.set(1).andThen(r.t.set("t1;")).andThen(r.l.set(Chunk(1)))
            ok <- awaitAll(h, 3.seconds)("[a=1]", "t1;", "[f=1]", "[n=1:t1;]")
            s  <- h.screen
        yield assert(
          ok,
          s"the screen was not right within 3 s (the verify pass did not carry the write):\n$s",
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

    // claude 2026-10-13: created (C3): the R2 key variant
    "R2 key variant: a key whose handler writes a ref in the arm window reaches THAT key's frame (a key is an Input step: the verify pass re-reads the unarmed regions)" in {
      // The window of every watcher is open for 2 s after its mount. The first
      // key ('x', no write) is the first step after the start: it uses up the
      // "young" re-read of the initial regions, so only the re-read of the
      // unarmed regions can carry the write of the second key into its frame.
      val window = Knobs(
        seam = n => if n == 0 then 2.seconds else Duration.Zero,
        repairInterval = 1.hour,
      )
      Knobs.let(window) {
        for
            a     <- Signal.initRef(0)
            t0    <- now
            h     <- Headless.start(30, 3)(_ =>
                       div.onKeyDown {
                         case KeyboardEvent(Keyboard.Char('+'), _, _) =>
                           a.updateAndGet(_ + 1)
                         case _                                       => ()
                       }(span(a.map(n => s"[a=$n]"))),
                     )
            _     <- h.char('x')
            f     <- h.char('+')
            t1    <- now
            s     <- h.screen
            // when the value is not in the key's frame, when does it come?
            later <- h.awaitScreen(_.contains("[a=1]"), 5.seconds)
            t2    <- now
        yield
            val line =
              s"[r2-key] in the key's frame: ${s.contains("[a=1]")} (updates ${f.updates}, ${(t1 - t0) / 1000000} ms after the start); on the screen at all: $later after ${(t2 - t0) / 1000000} ms"
            java.lang.System.out.println(line)
            assert(
              (t1 - t0) < 2.seconds.toNanos,
              s"the keys came after the window: $line",
            )
            assert(s.contains("[a=1]") && f.updates >= 1, line)
      }
    }
end SeamTest
