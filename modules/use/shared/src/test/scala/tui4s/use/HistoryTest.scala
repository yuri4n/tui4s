package tui4s.use

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

/** `useHistory` over a `SignalRef`: the steps, undo, redo and the limit. */
// claude 2026-10-19: created (L2a)
class HistoryTest extends Test[Any]:

    "the writes are steps; undo and redo move between them; the ref follows" in {
      for
          ref   <- Signal.initRef(0)
          h     <- useHistory(ref, 10)
          none  <- h.canUndo.current
          _     <- h.set(1)
          _     <- h.update(_ + 1)
          two   <- ref.get
          _     <- h.undo
          one   <- ref.get
          redo  <- h.canRedo.current
          _     <- h.undo
          _     <- h.undo
          zero  <- ref.get
          _     <- h.redo
          back  <- ref.get
          _     <- h.set(5)
          gone  <- h.canRedo.current
          _     <- h.set(5)
          _     <- h.undo
          after <- ref.get
      yield
          assert(!none && two == 2 && one == 1 && redo)
          assert(zero == 0 && back == 1)
          assert(!gone)
          // The second write of 5 was not a step: one undo goes to 1.
          assert(after == 1)
    }

    "the past keeps at most `limit` values" in {
      for
          ref  <- Signal.initRef(0)
          h    <- useHistory(ref, 2)
          _    <- Kyo.foreach(1 to 5)(h.set)
          _    <- h.undo
          _    <- h.undo
          _    <- h.undo
          last <- ref.get
      yield assert(last == 3)
    }
end HistoryTest

/** The laws of the timeline: an undo after a write restores the value before
  * it, a redo after an undo restores the value that the undo replaced, a write
  * clears the redo values, and the past keeps at most `limit` values.
  */
// claude 2026-10-19: created (L2a)
class HistoryPropertyTest extends PropertyTest[Any]:

    // claude 2026-10-19: created (L2a review): 200 samples, as the core's
    // oracle suites
    override protected def numSamples: Int = 200
    import History.Timeline

    /** A step: a write of a value (Some) or an undo (None, odd) or a redo. */
    private enum Op derives CanEqual:
        case Write(a: Int)
        case Undo
        case Redo

    private val op: Gen[Op] =
      Gen.int.map(i =>
        Math.floorMod(i, 4) match
            case 0 => Op.Undo
            case 1 => Op.Redo
            case _ => Op.Write(Math.floorMod(i, 7)),
      )

    private val limit: Gen[Int] = Gen.int.map(i => 1 + Math.floorMod(i, 5))

    private def run(ops: Chunk[Op], limit: Int): Timeline[Int] =
      ops.foldLeft(History.start(0)) {
        case (t, Op.Write(a)) => t.write(a, limit)
        case (t, Op.Undo)     => t.undo
        case (t, Op.Redo)     => t.redo
      }

    "the laws of undo, redo and the limit" - {
      forAll(Gen.listOfN(12, op), limit, Gen.int) { (ops, lim, v) =>
        val t = run(ops, lim)
        val a = Math.floorMod(v, 7) + 100 // never the present value
        assert(t.past.size <= lim)
        assert(t.write(a, lim).undo.present == t.present)
        assert(t.write(a, lim).future.isEmpty)
        assert(t.past.isEmpty || t.undo.redo == t)
      }
    }
end HistoryPropertyTest
