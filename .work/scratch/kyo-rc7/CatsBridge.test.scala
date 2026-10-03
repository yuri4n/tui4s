//> using test.dep org.scalameta::munit:1.3.6

// Tests for CatsBridge (Kyo RC7 <-> cats-effect 3.7.1). Run:
//   source .work/env.sh && export JDK_JAVA_OPTIONS=-Xss16m
//   scala-cli test --server=false .work/scratch/kyo-rc7/CatsBridge.scala .work/scratch/kyo-rc7/CatsBridge.test.scala
package tui4s.interop

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.concurrent.ExecutionContext

class CatsBridgeTest extends munit.FunSuite:

    override def munitTimeout = scala.concurrent.duration.Duration(20, "s")
    private val ioTimeout     = scala.concurrent.duration.Duration(10, "s")

    // Simplest way to run Kyo in munit: block at the edge with KyoApp.Unsafe.runAndBlock.
    def runKyo[A](v: A < (Async & Scope & Abort[Any]))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(10.seconds)(v).getOrThrow

    // A custom IORuntime whose compute threads are named, to prove which runtime ran an IO.
    def namedRuntime(prefix: String): (IORuntime, () => Unit) =
        val pool = Executors.newFixedThreadPool(2, r => { val t = new Thread(r, s"$prefix-${r.hashCode}"); t.setDaemon(true); t })
        val ec   = ExecutionContext.fromExecutor(pool)
        val rt   = IORuntime.builder().setCompute(ec, () => pool.shutdown()).build()
        (rt, () => rt.shutdown())

    test("run: Kyo -> IO, value") {
        import cats.effect.unsafe.implicits.global
        assertEquals(CatsBridge.run(Async.sleep(5.millis).andThen(42)).unsafeRunSync(), 42)
    }

    test("run: Kyo Abort[Throwable] failure becomes an IO error") {
        import cats.effect.unsafe.implicits.global
        val boom = new RuntimeException("boom")
        val r    = CatsBridge.run(Abort.fail(boom): Int < Abort[Throwable]).attempt.unsafeRunSync()
        assertEquals(r, Left(boom))
    }

    test("get: IO -> Kyo with the global runtime given") {
        import cats.effect.unsafe.implicits.global
        assertEquals(runKyo(CatsBridge.get(IO.pure(20)).map(_ + 1)), 21)
    }

    test("get: failed IO becomes a typed Abort[Throwable] failure") {
        import cats.effect.unsafe.implicits.global
        val boom = new IllegalStateException("io failed")
        val r    = runKyo(Abort.run[Throwable](CatsBridge.get(IO.raiseError[Int](boom))))
        assertEquals(r, Result.fail(boom))
    }

    test("get: IO that cancels itself becomes an Interrupted panic") {
        import cats.effect.unsafe.implicits.global
        val r = runKyo(Abort.run[Throwable](CatsBridge.get(IO.canceled *> IO.pure(1))))
        assert(r.isPanic, r.toString)
        assert(r.panic.exists(_.isInstanceOf[Interrupted]), r.toString)
    }

    test("get: uses the GIVEN IORuntime, not the global one") {
        val (rt, shutdown) = namedRuntime("custom-ce")
        try
            given IORuntime = rt
            val thread      = runKyo(CatsBridge.get(IO(Thread.currentThread().getName)))
            assert(thread.startsWith("custom-ce-"), thread)
        finally shutdown()
        end try
    }

    // Which Kyo finalizers have completed at the moment the IO cancel returns?
    //  - Sync.ensure: synchronous release, runs before the Kyo fiber's result -> must be done.
    //  - Scope.ensure: drained by a detached fiber on abnormal exit (Scope.scala "Open:" note) -> maybe not done.
    def cancelProbe(kind: String): (Boolean, Boolean, Boolean) =
        import cats.effect.unsafe.implicits.global
        val finalized = new AtomicBoolean(false)
        val started   = new CountDownLatch(1)
        val body: Unit < (Abort[Throwable] & Async) = Sync.defer(started.countDown()).andThen(Async.never[Unit])
        val kyoProg: Unit < (Abort[Throwable] & Async) = kind match
            case "sync-ensure"        => Sync.ensure(Sync.defer(finalized.set(true)))(body)
            case "scope-ensure-sync"  => Scope.run(Scope.ensure(Sync.defer(finalized.set(true))).andThen(body))
            case "scope-ensure-async" =>
                Scope.run(Scope.ensure(Async.sleep(50.millis).andThen(Sync.defer(finalized.set(true)))).andThen(body))
        val prog: IO[(Boolean, Boolean)] =
            for
                f        <- CatsBridge.run(kyoProg).start
                _        <- IO.blocking(started.await())
                _        <- f.cancel
                atCancel <- IO(finalized.get)
                outcome  <- f.join
            yield (atCancel, outcome.isCanceled)
        val (atCancel, canceled) = prog.timeout(ioTimeout).unsafeRunSync()
        Thread.sleep(300)
        val later = finalized.get
        println(s"[cancel-probe] $kind: finalizedWhenCancelReturned=$atCancel ioCanceled=$canceled finalizedAfter300ms=$later")
        (atCancel, canceled, later)
    end cancelProbe

    test("cancel IO -> Kyo interrupted; Sync.ensure finalizer has run when cancel returns (cancel-and-await)") {
        assertEquals(cancelProbe("sync-ensure"), (true, true, true))
    }

    test("cancel IO -> Kyo interrupted; Scope.ensure finalizers run eventually (not awaited on interrupt)") {
        val (_, c1, later1) = cancelProbe("scope-ensure-sync")
        val (_, c2, later2) = cancelProbe("scope-ensure-async")
        assert(c1 && c2 && later1 && later2)
    }

    test("interrupt Kyo -> IO cancelled, IO finalizer runs") {
        import cats.effect.unsafe.implicits.global
        val ioStarted   = new CountDownLatch(1)
        val ioFinalized = new CountDownLatch(1)
        val io          = (IO(ioStarted.countDown()) *> IO.never[Int]).onCancel(IO(ioFinalized.countDown()))
        val r = runKyo {
            for
                fiber  <- Fiber.initUnscoped(CatsBridge.get(io))
                _      <- Sync.defer(ioStarted.await())
                _      <- fiber.interrupt
                result <- fiber.getResult
            yield result
        }
        assert(r.isPanic && r.panic.exists(_.isInstanceOf[Interrupted]), r.toString)
        assert(ioFinalized.await(2, TimeUnit.SECONDS), "IO finalizer did not run")
    }

    test("runF / getF polymorphic, via runWithDispatcher on the caller's runtime") {
        val (rt, shutdown) = namedRuntime("caller-ce")
        try
            val prog: IO[(Int, String)] =
                CatsBridge.runWithDispatcher[IO, (Int, String)] {
                    for
                        n      <- CatsBridge.getF(IO.pure(41))
                        thread <- CatsBridge.getF(IO(Thread.currentThread().getName))
                    yield (n + 1, thread)
                }
            val (n, thread) = prog.timeout(ioTimeout).unsafeRunSync()(using rt)
            assertEquals(n, 42)
            assert(thread.startsWith("caller-ce-"), thread)
        finally shutdown()
        end try
    }

    test("getF: interrupting Kyo cancels the F through the Dispatcher") {
        import cats.effect.unsafe.implicits.global
        val ioStarted   = new CountDownLatch(1)
        val ioFinalized = new CountDownLatch(1)
        val io          = (IO(ioStarted.countDown()) *> IO.never[Int]).onCancel(IO(ioFinalized.countDown()))
        val prog: IO[Boolean] =
            CatsBridge.runWithDispatcher[IO, Boolean] {
                for
                    fiber <- Fiber.initUnscoped(CatsBridge.getF(io))
                    _     <- Sync.defer(ioStarted.await())
                    _     <- fiber.interrupt
                    r     <- fiber.getResult
                yield r.isPanic
            }
        assert(prog.timeout(ioTimeout).unsafeRunSync())
        assert(ioFinalized.await(2, TimeUnit.SECONDS), "F finalizer did not run")
    }

    // Stress the race "Kyo fiber interrupted after the IO started but before it joined the promise".
    // With kyo-cats RC5's p.onInterrupt the IO leaked (and a Dispatcher(await = true) release then hung forever).
    test("stress: interrupt right after the IO starts never leaks the IO (300 rounds, IORuntime)") {
        import cats.effect.unsafe.implicits.global
        val rounds = 300
        val leaked = runKyo {
            Kyo.foreach(1 to rounds) { _ =>
                val ioStarted   = new CountDownLatch(1)
                val ioFinalized = new CountDownLatch(1)
                val io          = (IO(ioStarted.countDown()) *> IO.never[Int]).onCancel(IO(ioFinalized.countDown()))
                for
                    fiber <- Fiber.initUnscoped(CatsBridge.get(io))
                    _     <- Sync.defer(ioStarted.await())
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    ok    <- Sync.defer(ioFinalized.await(2, TimeUnit.SECONDS))
                yield if ok then 0 else 1
            }.map(_.sum)
        }
        assertEquals(leaked, 0)
    }

    test("stress: same race through a Dispatcher(await = true) (100 rounds)") {
        import cats.effect.unsafe.implicits.global
        val prog: IO[Int] =
            CatsBridge.runWithDispatcher[IO, Int] {
                Kyo.foreach(1 to 100) { _ =>
                    val ioStarted = new CountDownLatch(1)
                    val io        = IO(ioStarted.countDown()) *> IO.never[Int]
                    for
                        fiber <- Fiber.initUnscoped(CatsBridge.getF(io))
                        _     <- Sync.defer(ioStarted.await())
                        _     <- fiber.interrupt
                        r     <- fiber.getResult
                    yield if r.isPanic then 1 else 0
                }.map(_.sum)
            }
        assertEquals(prog.timeout(ioTimeout).unsafeRunSync(), 100)
    }

end CatsBridgeTest
