package tui4s

import kyo.test.*

class FocusTest extends Test[Any]:
    enum Part derives CanEqual:
        case A, B, C

    "next and prev cycle and wrap around" in {
      val f = Focus(Part.A, Part.B, Part.C)
      assert(f.current == Part.A && f.is(Part.A) && !f.is(Part.B))
      assert(f.next.current == Part.B)
      assert(f.next.next.next == f)
      assert(f.prev.current == Part.C)
      assert(f.prev.next == f)
    }

    "to moves the focus; an unknown or current part returns the same object" in {
      val f = Focus(Part.A, Part.B)
      assert(f.to(Part.B).is(Part.B))
      assert(f.to(Part.A) eq f)
      assert(f.to(Part.C) eq f)
    }

    "equality is structural" in {
      val f = Focus(Part.A, Part.B)
      assert(f.next == Focus(Part.A, Part.B).to(Part.B))
      assert(f.next.hashCode == Focus(Part.A, Part.B).to(Part.B).hashCode)
      assert(f != Focus(Part.B, Part.A))
      assert(f.toString == "Focus(A of A, B)")
    }

    "a single part keeps the focus" in {
      val f = Focus(Part.A)
      assert(f.next == f && f.prev == f)
    }
end FocusTest
