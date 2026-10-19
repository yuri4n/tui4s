package tui4s.use

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

/** `useSelection`: the moves, the edge rule, and a selection that follows its
  * item when the list changes.
  */
// claude 2026-10-19: created (L2a)
class SelectionTest extends Test[Any]:

    final case class Item(id: String) derives CanEqual

    private def items(ids: String*) = Chunk.from(ids.map(Item(_)))

    "the moves; Clamp stops at the edge" in {
      for
          list <- Signal.initRef(items("a", "b", "c", "d"))
          sel  <- useSelection(list, _.id, page = 2)
          i0   <- sel.index.current
          _    <- sel.moveUp
          i1   <- sel.index.current
          _    <- sel.pageDown
          i2   <- sel.index.current
          _    <- sel.pageDown
          i3   <- sel.index.current
          _    <- sel.moveDown
          i4   <- sel.index.current
          _    <- sel.first
          i5   <- sel.index.current
          _    <- sel.last
          k6   <- sel.key.current
          _    <- sel.select("b")
          k7   <- sel.key.current
          _    <- sel.select("zz")
          k8   <- sel.key.current
      yield
          assert(Chunk(i0, i1, i2, i3, i4, i5) == Chunk(0, 0, 2, 3, 3, 0))
          assert(k6 == Present("d") && k7 == Present("b") && k8 == Present("b"))
    }

    "Wrap: a step goes on from the other edge" in {
      for
          list <- Signal.initRef(items("a", "b", "c"))
          sel  <- useSelection(list, _.id, Edge.Wrap)
          _    <- sel.moveUp
          up   <- sel.key.current
          _    <- sel.moveDown
          down <- sel.key.current
      yield assert(up == Present("c") && down == Present("a"))
    }

    "the selection follows its key when the list changes; when its item goes, it takes the index of its last move" in {
      for
          list   <- Signal.initRef(items("a", "b", "c"))
          sel    <- useSelection(list, _.id)
          _      <- sel.moveDown
          _      <- list.set(items("x", "c", "b", "a"))
          moved  <- sel.key.current
          at     <- sel.index.current
          _      <- list.set(items("x", "c", "a"))
          gone   <- sel.key.current
          _      <- list.set(Chunk.empty)
          empty  <- sel.key.current
          emptyI <- sel.index.current
      yield
          assert(moved == Present("b") && at == 2)
          // "b" was at index 1 at its last move: "c" is at index 1 now.
          assert(gone == Present("c"))
          assert(empty == Absent && emptyI == 0)
    }
end SelectionTest

/** The laws of the selection: the index stays in the list, and a selection
  * whose item stays in the list keeps its key, whatever the change.
  */
// claude 2026-10-19: created (L2a)
class SelectionPropertyTest extends PropertyTest[Any]:

    /** 0 to 30 distinct keys, in a random order. */
    private val keys: Gen[Chunk[Int]] =
      Gen
        .listOfN(30, Gen.int)
        .map(xs => Chunk.from(xs.distinct))
        .flatMap(xs => Gen.int.map(n => xs.take(Math.floorMod(n, xs.size + 1))))

    private val moves: Gen[Chunk[Int]] = Gen.listOfN(10, Gen.int.map(_ % 12))

    private val edge: Gen[Edge] = Gen.oneOf(Edge.Clamp, Edge.Wrap)

    "the index stays in the list" - {
      forAll(keys, moves, edge) { (xs, ds, e) =>
        val n    = xs.size
        val last = ds.foldLeft(0) { (i, d) =>
          val j = Selection.step(n, i, d, e)
          assert(j >= 0 && j <= (n - 1).max(0))
          j
        }
        val c    = Selection.Cursor(Present(-1), last + 7)
        val r    = Selection.resolve(xs, identity[Int], c)
        assert(r >= 0 && r <= (n - 1).max(0))
      }
    }

    "a selection follows its key through a change of the list" - {
      forAll(keys, keys, Gen.int) { (before, other, pick) =>
        if before.isEmpty then assert(true)
        else
            val i     = Math.floorMod(pick, before.size)
            val k     = before(i)
            val c     = Selection.Cursor(Present(k), i)
            // The new list: the selected key among other keys, in another order.
            val after = (other.filterNot(_ == k) :+ k).reverse
            val r     = Selection.resolve(after, identity[Int], c)
            assert(Selection.keyAt(after, identity[Int], r) == Present(k))
        end if
      }
    }
end SelectionPropertyTest
