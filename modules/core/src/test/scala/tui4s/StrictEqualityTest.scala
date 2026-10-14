package tui4s

import java.time.Duration as JDuration
import kyo.test.*
import scala.language.strictEquality
import tui4s.spi.Settle
import tui4s.testkit.CellStyle
import tui4s.testkit.Screen

/** Users who compile with `-language:strictEquality` can compare the public
  * types with `==`. This file does not compile when one of them has no
  * `CanEqual` (OS-16 lesson).
  */
// claude 2026-10-12: created
class StrictEqualityTest extends Test[Any]:

    "FrameStats, FrameCause, TuiOptions, Settle, Screen and CellStyle compare with ==" in {
      val f = FrameStats(1L, FrameCause.Input, 2, 3, 4L, 5)
      assert(f == f.copy() && f != f.copy(seq = 2L))
      assert(FrameCause.Input != FrameCause.Background)
      assert(TuiOptions() == TuiOptions(autoFocus = true))
      assert(
        Settle
          .Quiescent(JDuration.ofMillis(1)) != Settle.Hops(1, JDuration.ZERO),
      )
      val s = Screen(Vector("a"), Vector(Vector(CellStyle.plain)), None)
      assert(s == s.copy() && s != s.copy(cursor = Some((0, 0))))
      assert(CellStyle(bold = true) != CellStyle.plain)
    }
end StrictEqualityTest
