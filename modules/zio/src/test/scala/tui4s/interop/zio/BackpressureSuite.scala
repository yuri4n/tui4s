package tui4s.interop.zio

import _root_.zio.ZIO
import _root_.zio.stream.ZStream
import tui4s.testkit.*

/** SPEC 8.3 Z2: a ZStream source through a small inbox folds every element,
  * with backpressure (no element lost, no unbounded buffer).
  */
class BackpressureSuite extends ZioSuite:
    /** Counts and sums the elements; quits after `n`. */
    private def counting(stream: ZStream[Any, Throwable, Int], n: Int) =
      App[(Int, Long)](
        init = (0, 0L),
        view = s => text(s"${s._1} ${s._2}"),
        update = (s, _) => s,
        sources = _ =>
          Seq(Source.For[(Int, Long)].stream(stream) { (s, i) =>
            val next = (s._1 + 1, s._2 + i)
            if next._1 == n then Quit(next) else next
          }),
        options = TuiOptions(inboxCapacity = 16),
      )

    private val n        = 10_000
    private val expected = (n, n.toLong * (n + 1) / 2)

    test("10 000 elements, one per chunk, through an inbox of 16") {
      val stream = ZStream.range(1, n + 1, chunkSize = 1)
      val r      = run(
        Tui.runWith(
          counting(stream, n),
          RecordingControl(),
          ScriptedInput.empty,
        ),
      )
      assertEquals(r, expected)
    }

    test("10 000 elements in large chunks fold element by element") {
      val stream = ZStream.fromIterable(1 to n)
      val r      = run(
        Tui.runWith(
          counting(stream, n),
          RecordingControl(),
          ScriptedInput.empty,
        ),
      )
      assertEquals(r, expected)
    }

    test("the stream waits for the inbox: it never runs far ahead") {
      // A fold that is slow at first: the inbox (16) fills, and the stream
      // must wait instead of pulling all elements.
      val pulled = java.util.concurrent.atomic.AtomicInteger(0)
      val stream = ZStream
        .range(1, 1001, chunkSize = 1)
        .tap(_ => ZIO.succeed(pulled.incrementAndGet()))
      val seen   = java.util.concurrent.atomic.AtomicInteger(0)
      val ahead  = java.util.concurrent.atomic.AtomicInteger(0)
      val app    = App[Int](
        init = 0,
        view = c => text(c.toString),
        update = (c, _) => c,
        sources = _ =>
          Seq(Source.For[Int].stream(stream) { (c, _) =>
            if c < 5 then Thread.sleep(20)
            val _ = seen.incrementAndGet()
            val _ = ahead.accumulateAndGet(pulled.get - seen.get, math.max)
            if c + 1 == 1000 then Quit(c + 1) else c + 1
          }),
        options = TuiOptions(inboxCapacity = 16),
      )

      val r = run(Tui.runWith(app, RecordingControl(), ScriptedInput.empty))
      assertEquals(r, 1000)
      // Pulled but not folded: the batch that the render fiber took out of the
      // inbox and folds now (the 16 items, plus puts that waited and were
      // drained with them), the inbox again (16), the puts that wait for room.
      // Bounded by the inbox, not by the stream: without backpressure the
      // stream would pull all 1000 elements during the slow folds.
      assert(ahead.get <= 3 * 16, s"the stream ran ${ahead.get} elements ahead")
    }
end BackpressureSuite
