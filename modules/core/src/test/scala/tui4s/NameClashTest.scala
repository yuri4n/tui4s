package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.*
import tui4s.spi.*
import tui4s.testkit.*

/** A file with `import kyo.*`, `import kyo.UI.*`, `import kyo.test.*`,
  * `import tui4s.*`, `import tui4s.spi.*` and `import tui4s.testkit.*` that
  * uses the public names of the core: no name is ambiguous (OS-16 lesson).
  */
// claude 2026-10-12: created
class NameClashTest extends Test[Any]:

    "kyo.*, kyo.UI.*, kyo.test.*, tui4s.*, tui4s.spi.* and tui4s.testkit.* together" in {
      val options: TuiOptions =
        TuiOptions(settle = Settle.Quiescent(java.time.Duration.ofMillis(100)))
      val cause: FrameCause   = FrameCause.Background
      val stats: FrameStats   = FrameStats(1L, cause, 0, 0, 0L, 0)
      val style: CellStyle    = CellStyle.plain
      val copy: Screen        = Screen(Vector(""), Vector(Vector(style)), None)
      val ui: UI              = div(span("x"))
      for
          h <- Headless.start(10, 2, options)(_ => ui)
          s <- h.screen
          _  = assertScreen(s, "x")
      yield assert(
        diffScreen(s, "x").isEmpty && stats.seq == 1L && copy.rows.size == 1 &&
          options.autoFocus && s.styleAt(0, 0) == CellStyle.plain,
      )
      end for
    }
end NameClashTest
