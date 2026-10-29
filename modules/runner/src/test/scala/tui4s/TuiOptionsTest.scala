package tui4s

import kyo.test.*
import tui4s.spi.Settle

import java.time.Duration
import scala.language.strictEquality

/** `TuiOptions` and `Settle` are Kyo-free and compare with `==` under
  * `-language:strictEquality` (this file does not compile when one of them
  * loses its `CanEqual`).
  */
// claude 2026-10-12: created (RC-3, D-14; OS-16 lesson)
class TuiOptionsTest extends Test[Any]:

    // codex 2026-10-23: changed: assert the local default without changing existing constructor shapes.
    "the defaults: autoFocus, Local settlement" in {
      val o = TuiOptions()
      assert(o.autoFocus)
      assert(o.settle == Settle.Local())
      assert(o == TuiOptions(settle = Settle.Local()))
    }

    "a java.time.Duration builds every Settle; the cases compare with ==" in {
      val q              = Settle.Quiescent(Duration.ofMillis(50), confirm = 3)
      val h: Settle.Hops = Settle.Hops(3, Duration.ofNanos(250_000))
      assert(q != h && q == Settle.Quiescent(Duration.ofMillis(50), 3))
      assert(TuiOptions(autoFocus = false, settle = h) != TuiOptions())
      assert(h.window.toNanos == 250_000L)
    }
    // claude 2026-10-13: created (T1, RC-3 part)
    "the options of the real terminal: pollInterval 25 ms, inboxCapacity 1024, captureOutput, no log file" in {
      val o = TuiOptions()
      assert(o.pollInterval.toMillis == 25L)
      assert(o.inboxCapacity == 1024 && o.captureOutput && o.logFile.isEmpty)
      val f = java.nio.file.Path.of("tui4s.log")
      assert(
        TuiOptions(logFile = Some(f)) == TuiOptions(logFile = Some(f)) &&
          TuiOptions(pollInterval = Duration.ofMillis(5)) != o,
      )
    }
    // claude 2026-10-13: created (C4): the option of the pending frame
    "pendingTimeout: a java.time.Duration, 2 s by default (the prototype's value)" in {
      val o = TuiOptions()
      assert(o.pendingTimeout.toMillis == 2000L)
      assert(
        TuiOptions(pendingTimeout = Duration.ofMillis(50)) != o &&
          TuiOptions(pendingTimeout = Duration.ofMillis(50)) ==
          TuiOptions(pendingTimeout = Duration.ofMillis(50)),
      )
    }
end TuiOptionsTest
