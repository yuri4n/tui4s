//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7
//> using dep io.getkyo::kyo-zio:1.0.0-RC7
//> using dep dev.zio::zio:2.1.26
//> using dep dev.zio::zio-streams:2.1.26
//> using mainClass tui4s.interop.zioBridgeProbe

// kyo-zio RC7 + ZIO 2.1.26 experiments for tui4s-zio. Run:
//   source .work/env.sh && export JDK_JAVA_OPTIONS=-Xss16m
//   scala-cli run --server=false .work/scratch/kyo-rc7/zio/ZioBridge.scala
package tui4s.interop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import zio.Cause
import zio.Exit
import zio.FiberId
import zio.FiberRef
import zio.Runtime
import zio.StackTrace
import zio.Trace
import zio.Unsafe
import zio.ZIO
import zio.ZLayer
import zio.stream.ZStream

/** Prototype of what tui4s-zio could ship: kyo-zio's ZIOs, but
  *   - `run` captures the CALLER's runtime (`ZIO.runtime`) into a Kyo `Local`, and
  *   - `get` forks the ZIO on that runtime (falls back to Runtime.default outside `run`), and
  *   - `get` cancels through a `Sync.ensure` region (robust to an interrupt before the join; see the stress test).
  */
object AmbientZIOs:
    private val callerRuntime: Local[Maybe[Runtime[Any]]] = Local.init(Maybe.empty[Runtime[Any]])

    private inline def discard[A](inline v: => A): Unit =
        val _ = v
        ()

    def run[A, E](v: => A < (Abort[E] & Async))(using Frame, Trace): ZIO[Any, E, A] =
        ZIO.runtime[Any].flatMap(rt => ZIOs.run(callerRuntime.let(Present(rt))(v)))

    def get[E, A](v: => ZIO[Any, E, A])(using Frame, Trace): A < (Abort[E] & Async) =
        callerRuntime.use(rt => getOn(rt.getOrElse(Runtime.default))(v))

    def getOn[E, A](rt: Runtime[Any])(v: => ZIO[Any, E, A])(using frame: Frame, t: Trace): A < (Abort[E] & Async) =
        Sync.Unsafe.defer {
            given CanEqual[E, E] = CanEqual.derived
            import ZIOs.toResult
            val p        = Promise.Unsafe.init[A, Abort[E]]()
            val fiberRef = new AtomicReference[zio.Fiber.Runtime[E, A]](null)
            val settled  = new AtomicBoolean(false)
            // `Sync.ensure` with an outcome needs a ConcreteTag for the Abort[E] of its body, and E is generic here, so the
            // body joins with `getResult` (no Abort in its row) and the error is re-raised after the region.
            Sync.ensure { (outcome: Maybe[Result.Error[Any]]) =>
                if outcome.nonEmpty && !settled.get() then
                    val f = fiberRef.get()
                    if f ne null then
                        Unsafe.unsafely(discard(f.unsafe.interrupt(Cause.interrupt(FiberId.None, StackTrace(FiberId.None, zio.Chunk(t))))))
                end if
            } {
                Sync.Unsafe.defer {
                    Unsafe.unsafely {
                        val f = rt.unsafe.fork(v)
                        fiberRef.set(f)
                        f.unsafe.addObserver { (exit: Exit[E, A]) =>
                            settled.set(true)
                            p.completeDiscard(exit.toResult.map(Kyo.lift))
                        }
                    }
                    p.safe.getResult
                }
            }.map(r => Abort.get(r))
        }
    end getOn
end AmbientZIOs

final case class AppConfig(title: String)

@main def zioBridgeProbe(): Unit =
    import AllowUnsafe.embrace.danger
    def runZ[E, A](z: ZIO[Any, E, A]): Exit[E, A] =
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(z))
    def runK[A](v: A < (Async & Scope & Abort[Any]), timeout: Duration = 10.seconds): A =
        KyoApp.Unsafe.runAndBlock(timeout)(v).getOrThrow

    // ---------- 1. ZIOs.get / ZIOs.run basics ----------
    val basic = runZ(ZIOs.run(ZIOs.get(ZIO.succeed(20)).map(_ + 1)))
    println(s"[zio] get/run value: $basic")
    val typedFail = runZ(ZIOs.run(Abort.run[String](ZIOs.get(ZIO.fail("boom"))).map(_.toString)))
    println(s"[zio] get of ZIO.fail -> Kyo: $typedFail")
    val kyoFail = runZ(ZIOs.run(Abort.fail("kyo-error"): Int < Abort[String]))
    println(s"[zio] run of Abort.fail -> ZIO: $kyoFail")
    val die = runZ(ZIOs.run(Abort.run[Nothing](ZIOs.get(ZIO.die(new RuntimeException("died")))).map(_.toString)))
    println(s"[zio] get of ZIO.die -> Kyo: $die")

    // ---------- 2. ZStreams ----------
    val zsToKyo = runK(ZStreams.get(ZStream(1, 2, 3).map(_ * 10)).run)
    println(s"[zio] ZStreams.get(ZStream(1,2,3)*10).run = $zsToKyo")
    val kyoToZs = runZ(ZStreams.run(Stream.init(Seq("a", "b", "c")).map(_.toUpperCase)).runCollect)
    println(s"[zio] ZStreams.run(Stream).runCollect = $kyoToZs")

    // ---------- 3. ZLayers ----------
    val zLayer: ZLayer[Any, Nothing, AppConfig] =
        ZLayer.scoped(ZIO.acquireRelease(ZIO.succeed(AppConfig("from-zlayer")))(_ => ZIO.succeed(println("[zio] zlayer released"))))
    val kyoLayer = ZLayers.get(zLayer)
    val viaLayer = runK(Memo.run(Env.runLayer(kyoLayer)(Env.use[AppConfig](_.title))))
    println(s"[zio] ZLayers.get -> Env.runLayer: $viaLayer")
    val kyoLayer2: Layer[AppConfig, Async] = Layer(Async.sleep(1.millis).andThen(AppConfig("from-kyo-layer")))
    val zl2      = ZLayers.run(kyoLayer2)
    val viaZLayer = runZ(ZIO.serviceWith[AppConfig](_.title).provide(zl2))
    println(s"[zio] ZLayers.run -> provide: $viaZLayer")

    // ---------- 4. ZIO interrupt -> Kyo fiber interrupted, which finalizers have run when interrupt returns ----------
    for kind <- Seq("sync-ensure", "scope-ensure-sync", "scope-ensure-async") do
        val finalized = new AtomicBoolean(false)
        val started   = new CountDownLatch(1)
        val body: Unit < Async = Sync.defer(started.countDown()).andThen(Async.never[Unit])
        val kyoProg: Unit < Async = kind match
            case "sync-ensure"       => Sync.ensure(Sync.defer(finalized.set(true)))(body)
            case "scope-ensure-sync" => Scope.run(Scope.ensure(Sync.defer(finalized.set(true))).andThen(body))
            case _ =>
                Scope.run(Scope.ensure(Async.sleep(50.millis).andThen(Sync.defer(finalized.set(true)))).andThen(body))
        val prog =
            for
                f           <- ZIOs.run(kyoProg).fork
                _           <- ZIO.attemptBlocking(started.await())
                exit        <- f.interrupt
                atInterrupt <- ZIO.succeed(finalized.get)
            yield (atInterrupt, exit.isInterrupted)
        val r = runZ(prog)
        Thread.sleep(300)
        println(s"[zio] interrupt ZIO -> Kyo ($kind): (finalizedWhenInterruptReturned, zioInterrupted)=$r finalizedAfter300ms=${finalized.get}")
    end for

    // ---------- 5. Kyo interrupt -> ZIO interrupted ----------
    {
        val zStarted   = new CountDownLatch(1)
        val zFinalized = new CountDownLatch(1)
        val z          = (ZIO.succeed(zStarted.countDown()) *> ZIO.never).onInterrupt(ZIO.succeed(zFinalized.countDown()))
        val r = runK {
            for
                fiber <- Fiber.initUnscoped(ZIOs.get(z))
                _     <- Sync.defer(zStarted.await())
                _     <- fiber.interrupt
                res   <- fiber.getResult
            yield res
        }
        println(s"[zio] interrupt Kyo -> ZIO: kyoResult=$r zioFinalizerRan=${zFinalized.await(2, TimeUnit.SECONDS)}")
    }

    // ---------- 6. Which runtime does ZIOs.get use? ----------
    val fr   = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val pool = Executors.newFixedThreadPool(2, r => { val t = new Thread(r, "caller-zio-" + r.hashCode); t.setDaemon(true); t })
    val callerExec = zio.Executor.fromJavaExecutor(pool)
    val probeZ: ZIO[Any, Nothing, (Int, String)] = fr.get.zip(ZIO.succeed(Thread.currentThread().getName))
    val upstream = runZ(fr.locally(42)(ZIOs.run(ZIOs.get(probeZ))).onExecutor(callerExec))
    println(s"[zio] upstream ZIOs.get under caller FiberRef=42 + caller executor: (fiberRef, thread)=$upstream")
    val ambient = runZ(fr.locally(42)(AmbientZIOs.run(AmbientZIOs.get(probeZ))).onExecutor(callerExec))
    println(s"[zio] AmbientZIOs.get (caller runtime via ZIO.runtime + Local):          (fiberRef, thread)=$ambient")
    // nested fiber inside the Kyo program still sees the caller runtime (Local is inheritable)
    val ambientForked = runZ(fr.locally(7)(AmbientZIOs.run(Fiber.initUnscoped(AmbientZIOs.get(probeZ)).map(_.get))).onExecutor(callerExec))
    println(s"[zio] AmbientZIOs.get from a forked Kyo fiber:                          (fiberRef, thread)=$ambientForked")

    // ---------- 7. Stress: interrupt right after the ZIO started (does the started ZIO leak?) ----------
    def stress(name: String, rounds: Int)(get: ZIO[Any, Nothing, Int] => Int < Async): Unit =
        val leaked = runK {
            Kyo.foreach(1 to rounds) { _ =>
                val zStarted   = new CountDownLatch(1)
                val zFinalized = new CountDownLatch(1)
                val z          = (ZIO.succeed(zStarted.countDown()) *> ZIO.never).onInterrupt(ZIO.succeed(zFinalized.countDown()))
                for
                    fiber <- Fiber.initUnscoped(get(z))
                    _     <- Sync.defer(zStarted.await())
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    ok    <- Sync.defer(zFinalized.await(2, TimeUnit.SECONDS))
                yield if ok then 0 else 1
            }.map(_.sum)
        }
        println(s"[zio] stress $name: rounds=$rounds leakedZioFibers=$leaked")
    end stress
    // Interrupt IMMEDIATELY (no wait for start): the ZIO may or may not have started. A ZIO that started must be
    // interrupted too; count "started but never finalized".
    def stressImmediate(name: String, rounds: Int)(get: ZIO[Any, Nothing, Int] => Int < Async): Unit =
        val program: (Int, Int) < Async =
            Kyo.foreach(1 to rounds) { _ =>
                val zStarted   = new CountDownLatch(1)
                val zFinalized = new CountDownLatch(1)
                val z          = (ZIO.succeed(zStarted.countDown()) *> ZIO.never).onInterrupt(ZIO.succeed(zFinalized.countDown()))
                for
                    fiber <- Fiber.initUnscoped(get(z))
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    st    <- Sync.defer(zStarted.await(50, TimeUnit.MILLISECONDS))
                    fin   <- Sync.defer(if st then zFinalized.await(2, TimeUnit.SECONDS) else true)
                yield (if st then 1 else 0, if st && !fin then 1 else 0)
            }.map(rs => (rs.map(_._1).sum, rs.map(_._2).sum))
        val (started, leaked) = runK(program, 180.seconds)
        println(s"[zio] stress-immediate $name: rounds=$rounds started=$started startedButLeaked=$leaked")
    end stressImmediate
    stressImmediate("upstream ZIOs.get", 500)(z => ZIOs.get(z))
    stressImmediate("AmbientZIOs.get (Sync.ensure)", 500)(z => AmbientZIOs.get(z))
    stress("upstream ZIOs.get", 300)(z => ZIOs.get(z))
    stress("AmbientZIOs.get (Sync.ensure)", 300)(z => AmbientZIOs.get(z))

    pool.shutdown()
end zioBridgeProbe
