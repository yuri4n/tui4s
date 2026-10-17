package tui4s

import kyo.*
import kyo.test.*
import tui4s.FrameStats
import tui4s.testkit.{Headless, Screen}

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

    /** Drains the frames of `h` until one satisfies `p` or `timeout` of
      * wall-clock time has passed (a poll every 2 ms on the live clock);
      * returns every frame drained. A test that saw the screen change with
      * `awaitScreen` calls this before it counts frames: the runner posts the
      * stats of a frame after its flush, so the stats of the frame that changed
      * the screen can still be in flight.
      */
    // claude 2026-10-12: created (review R2-3)
    def framesUntil(h: Headless, timeout: Duration)(p: FrameStats => Boolean)(
        using Frame,
    ): Vector[FrameStats] < Async =
      now.map { t0 =>
        Loop(Vector.empty[FrameStats]) { acc =>
          h.drainFrames.map { fs =>
            val all = acc ++ fs
            now.map { t =>
              if fs.exists(p) || t - t0 > timeout.toNanos then Loop.done(all)
              else
                  Clock
                    .let(Clock.live)(Async.sleep(2.millis))
                    .andThen(Loop.continue(all))
            }
          }
        }
      }

    /** Spins for `ms` on the calling worker thread: the scheduler probe then
      * sees a busy worker (the settle canary, the interrupt-inside-a-settle
      * test).
      */
    // claude 2026-10-12: created (moved here from SettleTest; review R2-1)
    def spin(ms: Long): Unit =
        val t0 = java.lang.System.nanoTime()
        while java.lang.System.nanoTime() - t0 < ms * 1_000_000L do ()
end RunnerSuite
