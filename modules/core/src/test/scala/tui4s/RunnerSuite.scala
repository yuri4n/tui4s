package tui4s

import kyo.*
import kyo.test.*
import tui4s.testkit.Headless
import tui4s.testkit.Screen

/** A kyo-test suite for runner tests. Its leaves run one at a time: the runner
  * settles on the JVM-wide Kyo scheduler, so a leaf that runs next to another
  * one sees its load (RC-19).
  */
// claude 2026-10-12: created
abstract class RunnerSuite extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    /** Wall-clock nanoseconds. */
    def now(using Frame): Long < Sync = Sync.defer(java.lang.System.nanoTime())

    /** True when the screen of `h` shows `text` now. */
    def shows(h: Headless, text: String)(using Frame): Boolean < Sync =
      h.screen.map(_.contains(text))

    /** Waits (at most `timeout`) until the screen of `h` shows all `texts`. */
    def awaitAll(h: Headless, timeout: Duration)(texts: String*)(using
        Frame,
    ): Boolean < Async =
      h.awaitScreen(s => texts.forall(s.contains), timeout)

    /** A predicate on the screen, for `awaitScreen`. */
    def showing(text: String): Screen => Boolean = _.contains(text)
end RunnerSuite
