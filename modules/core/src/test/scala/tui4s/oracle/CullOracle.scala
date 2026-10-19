package tui4s.oracle

import tui4s.internal.*

/** The oracle of the row part of F3, as it was at commit 95bcf2a: a text node
  * painted each of its first `rect.h` lines, and `Canvas.text` wrote a line
  * only when its row was inside the clip (`area`, the rect of the node inside
  * the clip of its owners). This gives the rows that the old code wrote. The
  * two pieces are copied without a change.
  *
  * The rest of the old painter is not kept: `Paint.paintNode` writes into a
  * mutable `Canvas`, so it is not a pure function. The node part of the cull is
  * checked by a property of the clip instead (`CullPropertyTest`).
  */
// claude 2026-10-19: created (PF-a, F3): a private copy of the old code
private[tui4s] object CullOracle:
    def rows(rect: Rect, clip: Rect, lineCount: Int): Vector[Int] =
        val area = rect.intersect(clip)
        Vector
          .range(0, lineCount)
          .take(rect.h)
          .zipWithIndex
          .map(_._2)
          .filterNot { i =>
            val y = rect.y + i
            y < area.y || y >= area.bottom
          }
    end rows
end CullOracle
