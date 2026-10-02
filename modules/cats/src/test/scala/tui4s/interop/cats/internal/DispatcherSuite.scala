package tui4s.interop.cats.internal

import _root_.cats.effect.IO
import _root_.cats.effect.std.Dispatcher
import _root_.cats.effect.unsafe.implicits.global
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.*
import scala.concurrent.duration.Duration as SDuration

/** The F10 regression. With cats-effect 3.7.1, a `Dispatcher.parallel` with
  * `await = true` can lose a cancel that a foreign (non-CE) thread sends right
  * after the task started: the task's `onCancel` never runs, and the release of
  * the Dispatcher waits forever (3 hangs in 10 rounds in the probe
  * `.work/scratch/kyo-rc7/dbg/PureDispatcher.scala`). The facade creates every
  * Dispatcher with `await = false`.
  */
class DispatcherSuite extends munit.FunSuite:
    override def munitTimeout = SDuration(120, "s")

    private val rounds = 100

    test(
      "Dispatcher(await = false): 100 cancels from a foreign thread, no hang",
    ) {
      val failed = (1 to rounds).count { _ =>
        val started   = new CountDownLatch(1)
        val finalized = new CountDownLatch(1)
        val io        = (IO(started.countDown()) *> IO.never[Int])
          .onCancel(IO(finalized.countDown()))
        val prog      = Dispatcher.parallel[IO](await = false).use { d =>
          IO.async_[Unit] { cb =>
            // A foreign thread starts the task, waits until it runs, then
            // cancels it.
            val t = new Thread(() =>
                val (_, cancel) = d.unsafeToFutureCancelable(io)
                started.await()
                val _           = cancel()
                cb(Right(())),
            )
            t.setDaemon(true)
            t.start()
          }
        }
        val ended     = prog.unsafeRunTimed(SDuration(5, "s")).isDefined
        !(ended && finalized.await(1, TimeUnit.SECONDS))
      }
      assertEquals(failed, 0)
    }

    test("the bridge: 100 interrupts of a Kyo fiber in getF cancel the F") {
      // The same race through the facade's path: a source fiber (Kyo, a
      // foreign thread for cats-effect) starts an F through the Dispatcher of
      // runWithDispatcher and is interrupted right after the F started.
      val prog: IO[Int] = CatsBridge.runWithDispatcher[IO, Int] {
        Kyo
          .foreach(1 to rounds) { _ =>
            val started   = new CountDownLatch(1)
            val finalized = new CountDownLatch(1)
            val io        = (IO(started.countDown()) *> IO.never[Int])
              .onCancel(IO(finalized.countDown()))
            Fiber.initUnscoped(CatsBridge.getF(io)).map { fiber =>
              Sync
                .defer(started.await())
                .andThen(fiber.interrupt)
                .andThen(fiber.getResult)
                .andThen(Sync.defer(finalized.await(2, TimeUnit.SECONDS)))
                .map(ok => if ok then 0 else 1)
            }
          }
          .map(_.sum)
      }
      assertEquals(prog.timeout(SDuration(60, "s")).unsafeRunSync(), 0)
    }
end DispatcherSuite
