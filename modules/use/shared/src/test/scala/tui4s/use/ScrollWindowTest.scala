package tui4s.use

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

/** `useScrollWindow` follows the selection and the list. */
// claude 2026-10-19: created (L2a)
class ScrollWindowTest extends Test[Any]:
    import Controlled.eventually

    "the window shows the selection and moves the least distance" in {
      for
          list <- Signal.initRef(Chunk.range(0, 100))
          size <- Signal.initRef(10)
          sel  <- Signal.initRef(0)
          win  <- useScrollWindow(list, size, sel)
          w0   <- win.current
          _    <- sel.set(15)
          down <- eventually(win)(_.offset == 6)
          // claude 2026-10-19: changed (L2a review): a new size after the
          // selection is a sentinel: when the window has it, the fiber has
          // seen the selection too
          _    <- sel.set(8)
          _    <- size.set(11)
          stay <- eventually(win)(_.slice.size == 11).andThen(win.current)
          _    <- sel.set(2)
          up   <- eventually(win)(_.offset == 2)
          _    <- list.set(Chunk.range(0, 5))
          cut  <- eventually(win)(_ == Window(0, Chunk.range(0, 5)))
      yield
          assert(w0 == Window(0, Chunk.range(0, 10)))
          assert(down && up && cut)
          assert(stay.offset == 6 && stay.slice.head == 6)
    }
end ScrollWindowTest

/** The law of the window: the offset shows the selection, stays in the list,
  * and is the nearest such offset to the old one (an oracle tries every
  * offset).
  */
// claude 2026-10-19: created (L2a)
class ScrollWindowPropertyTest extends PropertyTest[Any]:

    // claude 2026-10-19: created (L2a review): 200 samples, as the core's
    // oracle suites
    override protected def numSamples: Int = 200

    private def small(max: Int): Gen[Int] =
      Gen.int.map(Math.floorMod(_, max + 1))

    "follow is the nearest offset that shows the selection" - {
      forAll(small(60), small(20), small(80), small(70)) {
        (total, size, old, sel) =>
          val o   = ScrollWindow.follow(old, size, total, sel)
          val top = (total - size).max(0)
          assert(o >= 0 && o <= top)
          if total > 0 && size > 0 then
              val s      = sel.min(total - 1)
              val shows  = (c: Int) => s >= c && s < c + size
              val oracle =
                (0 to top).filter(shows).minBy(c => (Math.abs(c - old), c))
              assert(shows(o))
              assert(Math.abs(o - old) == Math.abs(oracle - old))
      }
    }
end ScrollWindowPropertyTest
