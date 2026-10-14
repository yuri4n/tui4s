package tui4s

import java.time.Duration
import kyo.test.*
import scala.language.strictEquality
import tui4s.spi.Settle

/** `TuiOptions` and `Settle` are Kyo-free and compare with `==` under
  * `-language:strictEquality` (this file does not compile when one of them
  * loses its `CanEqual`).
  */
// claude 2026-10-12: created (RC-3, D-14; OS-16 lesson)
class TuiOptionsTest extends Test[Any]:

    "the defaults: autoFocus, Quiescent(100 ms, confirm = 2)" in {
      val o = TuiOptions()
      assert(o.autoFocus)
      assert(o.settle == Settle.Quiescent(Duration.ofMillis(100), 2))
      assert(o == TuiOptions(settle = Settle.Quiescent(Duration.ofMillis(100))))
    }

    "a java.time.Duration builds every Settle; the cases compare with ==" in {
      val q              = Settle.Quiescent(Duration.ofMillis(50), confirm = 3)
      val h: Settle.Hops = Settle.Hops(3, Duration.ofNanos(250_000))
      assert(q != h && q == Settle.Quiescent(Duration.ofMillis(50), 3))
      assert(TuiOptions(autoFocus = false, settle = h) != TuiOptions())
      assert(h.window.toNanos == 250_000L)
    }
end TuiOptionsTest
