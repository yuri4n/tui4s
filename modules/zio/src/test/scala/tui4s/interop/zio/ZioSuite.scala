package tui4s.interop.zio

import _root_.zio.Exit
import _root_.zio.Runtime
import _root_.zio.Unsafe
import _root_.zio.ZIO
import munit.FunSuite

/** A munit suite that runs ZIO effects on `Runtime.default` (SPEC 6.4: the
  * facade tests check the bridge, not ZIO features).
  */
abstract class ZioSuite extends FunSuite:
    override def munitTimeout = scala.concurrent.duration.Duration(60, "s")

    /** Runs `z`; a failure throws (a `FiberFailure`). */
    protected def run[E, A](z: ZIO[Any, E, A]): A =
      Unsafe.unsafe(implicit u =>
        Runtime.default.unsafe.run(z).getOrThrowFiberFailure(),
      )

    /** Runs `z` and returns its exit. */
    protected def exit[E, A](z: ZIO[Any, E, A]): Exit[E, A] =
      Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(z))

    /** Waits in real time, at most `maxMillis`, until `cond` holds; fails the
      * test with `what` otherwise.
      */
    protected def eventually(what: => String, maxMillis: Long = 10_000)(
        cond: => Boolean,
    ): Unit =
        val deadline = System.nanoTime() + maxMillis * 1_000_000
        while !cond && System.nanoTime() < deadline do Thread.sleep(5)
        assert(cond, what)
end ZioSuite
