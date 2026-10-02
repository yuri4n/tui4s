package tui4s.interop.zio.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import munit.FunSuite

/** The bridge of SPEC 4.2 (the probe `zio/ZioBridge.scala`, sections 1 and 4-7,
  * F11, F12): values, typed errors, defects, interruption both ways, the
  * caller's FiberRefs and executor (also from a forked Kyo fiber), and a race
  * between an interrupt and the start of the ZIO.
  */
class AmbientZIOsSuite extends FunSuite:
    import AllowUnsafe.embrace.danger

    override def munitTimeout = scala.concurrent.duration.Duration(120, "s")

    private def runZ[E, A](
        z: _root_.zio.ZIO[Any, E, A],
    ): _root_.zio.Exit[E, A] =
      _root_.zio.Unsafe.unsafe(implicit u =>
        _root_.zio.Runtime.default.unsafe.run(z),
      )

    private def runK[A](
        v: A < (Async & Abort[Throwable]),
        timeout: Duration = 60.seconds,
    ): A =
      KyoApp.Unsafe.runAndBlock(timeout)(v).getOrThrow

    private def await(latch: CountDownLatch, millis: Long = 5000): Boolean =
      latch.await(millis, TimeUnit.MILLISECONDS)

    test("a value goes both ways") {
      val r = runZ(
        AmbientZIOs.run(AmbientZIOs.get(_root_.zio.ZIO.succeed(20)).map(_ + 1)),
      )
      assertEquals(r, _root_.zio.Exit.succeed(21))
    }

    test("a typed ZIO failure is a typed Kyo failure, and back") {
      val inKyo = runZ(
        AmbientZIOs.run(
          Abort.run[String](AmbientZIOs.get(_root_.zio.ZIO.fail("boom"))),
        ),
      )
      assertEquals(inKyo.map(_.failure), _root_.zio.Exit.succeed(Maybe("boom")))
      val inZio =
        runZ(AmbientZIOs.run(Abort.fail("kyo-error"): Int < Abort[String]))
      assertEquals(inZio, _root_.zio.Exit.fail("kyo-error"))
    }

    test("a ZIO defect is a Kyo panic, and back") {
      val boom  = RuntimeException("died")
      val inKyo = runZ(
        AmbientZIOs.run(
          Abort.run[Nothing](AmbientZIOs.get(_root_.zio.ZIO.die(boom))),
        ),
      )
      assertEquals(inKyo.map(_.panic), _root_.zio.Exit.succeed(Maybe(boom)))
      val inZio = runZ(AmbientZIOs.run[Int, Nothing](Abort.panic(boom)))
      assertEquals(inZio, _root_.zio.Exit.die(boom))
    }

    test("a ZIO interrupt waits for the Kyo fiber's Sync.ensure (F11)") {
      val finalized = AtomicBoolean(false)
      val started   = CountDownLatch(1)
      val body      = Sync
        .ensure(Sync.defer(finalized.set(true)))(
          Sync.defer(started.countDown()).andThen(Async.never[Unit]),
        )
      val r         = runZ(
        AmbientZIOs.run(body).fork.flatMap { f =>
          _root_.zio.ZIO.attemptBlocking(started.await()) *>
            f.interrupt.map(e => (e.isInterrupted, finalized.get))
        },
      )
      assertEquals(r, _root_.zio.Exit.succeed((true, true)))
    }

    test("a Kyo interrupt interrupts the ZIO; its onInterrupt runs") {
      val started   = CountDownLatch(1)
      val finalized = CountDownLatch(1)
      val z         =
        (_root_.zio.ZIO.succeed(started.countDown()) *> _root_.zio.ZIO.never)
          .onInterrupt(_root_.zio.ZIO.succeed(finalized.countDown()))
      val r         = runK(
        Fiber.initUnscoped(AmbientZIOs.get(z)).map { fiber =>
          Sync
            .defer(started.await())
            .andThen(fiber.interrupt)
            .andThen(fiber.getResult)
        },
      )
      assert(r.isPanic, r)
      assert(await(finalized), "the ZIO's onInterrupt did not run")
    }

    test("a ZIO runs on the caller's runtime: FiberRef and executor (F12)") {
      val ref  = _root_.zio.Unsafe.unsafe(implicit u =>
        _root_.zio.FiberRef.unsafe.make(0),
      )
      val pool = Executors.newFixedThreadPool(
        2,
        r =>
            val t = Thread(r, "caller-zio")
            t.setDaemon(true)
            t,
      )
      try
          val probe   =
            ref.get.zip(_root_.zio.ZIO.succeed(Thread.currentThread().getName))
          val exec    = _root_.zio.Executor.fromJavaExecutor(pool)
          val here    = runZ(
            ref
              .locally(42)(AmbientZIOs.run(AmbientZIOs.get(probe)))
              .onExecutor(exec),
          )
          assertEquals(here, _root_.zio.Exit.succeed((42, "caller-zio")))
          // A Kyo fiber forked inside the run (a source fiber) inherits it.
          val forked  = runZ(
            ref
              .locally(7)(
                AmbientZIOs.run(
                  Fiber.initUnscoped(AmbientZIOs.get(probe)).map(_.get),
                ),
              )
              .onExecutor(exec),
          )
          assertEquals(forked, _root_.zio.Exit.succeed((7, "caller-zio")))
          // Outside `run`: Runtime.default, the FiberRef's initial value.
          val outside = runK(AmbientZIOs.get(probe))
          assertEquals(outside._1, 0)
      finally pool.shutdown()
      end try
    }

    test("a runtime captured in an uninterruptible region still interrupts") {
      val started   = CountDownLatch(1)
      val finalized = CountDownLatch(1)
      val z         =
        (_root_.zio.ZIO.succeed(started.countDown()) *> _root_.zio.ZIO.never)
          .onInterrupt(_root_.zio.ZIO.succeed(finalized.countDown()))
      // The acquire of a ZIO bracket is uninterruptible, and ZIO.runtime
      // captures that: a source started there must still stop.
      val r         = runZ(
        _root_.zio.ZIO.uninterruptible(
          AmbientZIOs.run(
            Fiber.initUnscoped(AmbientZIOs.get(z)).map { fiber =>
              Sync
                .defer(started.await())
                .andThen(fiber.interrupt)
                .andThen(fiber.getResult)
            },
          ),
        ),
      )
      assert(r.isSuccess, r)
      assert(await(finalized), "the ZIO's onInterrupt did not run")
    }

    /** Busy-waits `micros` microseconds on the calling thread. */
    private def spin(micros: Int): Unit =
        val end = System.nanoTime() + micros * 1000L
        while System.nanoTime() < end do Thread.onSpinWait()

    test("500 rounds: an interrupt at any point of the start leaks no ZIO") {
      // Each round interrupts the Kyo fiber after a different delay (0 to
      // 180 us), so some interrupts land before the ZIO started, some while
      // it starts, some after. A ZIO that started must be interrupted.
      val rounds            = 500
      val (started, leaked) = runK(
        Kyo
          .foreach(0 until rounds) { i =>
            val zStarted   = CountDownLatch(1)
            val zFinalized = CountDownLatch(1)
            val z          =
              (_root_.zio.ZIO
                .succeed(zStarted.countDown()) *> _root_.zio.ZIO.never)
                .onInterrupt(_root_.zio.ZIO.succeed(zFinalized.countDown()))
            Fiber.initUnscoped(AmbientZIOs.get(z)).map { fiber =>
              Sync
                .defer(spin((i % 10) * 20))
                .andThen(fiber.interrupt)
                .andThen(fiber.getResult)
                .andThen(Sync.defer {
                  val st = zStarted.await(50, TimeUnit.MILLISECONDS)
                  val ok = !st || zFinalized.await(5, TimeUnit.SECONDS)
                  (if st then 1 else 0, if ok then 0 else 1)
                })
            }
          }
          .map(rs => (rs.map(_._1).sum, rs.map(_._2).sum)),
        timeout = 110.seconds,
      )
      assertEquals(leaked, 0, s"$leaked of $started started ZIOs leaked")
      // Not an assertion (it depends on the machine), but worth seeing.
      println(s"[AmbientZIOsSuite] race: $started of $rounds ZIOs had started")
    }

    test("300 rounds: an interrupt after the ZIO started leaks no ZIO") {
      val leaked = runK(
        Kyo
          .foreach(1 to 300) { _ =>
            val zStarted   = CountDownLatch(1)
            val zFinalized = CountDownLatch(1)
            val z          =
              (_root_.zio.ZIO
                .succeed(zStarted.countDown()) *> _root_.zio.ZIO.never)
                .onInterrupt(_root_.zio.ZIO.succeed(zFinalized.countDown()))
            Fiber.initUnscoped(AmbientZIOs.get(z)).map { fiber =>
              Sync
                .defer(zStarted.await())
                .andThen(fiber.interrupt)
                .andThen(fiber.getResult)
                .andThen(
                  Sync.defer(if zFinalized.await(5, TimeUnit.SECONDS) then 0
                  else 1),
                )
            }
          }
          .map(_.sum),
        timeout = 110.seconds,
      )
      assertEquals(leaked, 0)
    }
end AmbientZIOsSuite
