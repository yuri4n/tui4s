//> using mainClass tui4s.interop.dispatcherDebug
package tui4s.interop

import cats.effect.IO
import cats.effect.std.Dispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.concurrent.duration.Duration as SDuration

@main def dispatcherDebug(): Unit =
    import cats.effect.unsafe.implicits.global
    val stage        = new AtomicReference[String]("")
    val cancelCalled = new java.util.concurrent.atomic.AtomicBoolean(false)
    val cancelDone   = new AtomicReference[String]("")
    def round(i: Int, awaitTasks: Boolean): Either[Throwable, Boolean] =
        cancelCalled.set(false); cancelDone.set("")
        val ioStarted   = new CountDownLatch(1)
        val ioFinalized = new CountDownLatch(1)
        val io          = (IO(ioStarted.countDown()) *> IO.never[Int]).onCancel(IO(ioFinalized.countDown()))
        val prog: IO[Boolean] =
            Dispatcher.parallel[IO](await = awaitTasks).use { d0 =>
                given Dispatcher[IO] = new Dispatcher[IO]:
                    def unsafeToFutureCancelable[A](fa: IO[A]) =
                        val (f, c) = d0.unsafeToFutureCancelable(fa)
                        (f, () => {
                            cancelCalled.set(true)
                            val cf = c()
                            cf.onComplete(r => cancelDone.set(r.toString))(using scala.concurrent.ExecutionContext.parasitic)
                            cf
                        })
                CatsBridge.runF[IO, Boolean] {
                    for
                        fiber <- Fiber.initUnscoped(CatsBridge.getF(io))
                        _     <- Sync.defer(stage.set("A:waiting-io-start"))
                        _     <- Sync.defer(ioStarted.await())
                        _     <- Sync.defer(stage.set("B:interrupting"))
                        _     <- fiber.interrupt
                        _     <- Sync.defer(stage.set("C:getResult"))
                        r     <- fiber.getResult
                        _     <- Sync.defer(stage.set("D:kyo-done"))
                    yield r.isPanic
                }.flatTap(_ => IO(stage.set("E:runF-returned")))
            }.flatTap(_ => IO(stage.set("F:dispatcher-released")))
        val r = prog.unsafeRunTimed(SDuration(5, "s"))
        r match
            case Some(v) => Right(v && ioFinalized.await(1, TimeUnit.SECONDS))
            case None    => Left(new Exception(s"round $i hung at stage ${stage.get} ioFinalized=${ioFinalized.getCount == 0} cancelCalled=${cancelCalled.get} cancelDone=${cancelDone.get}"))
    end round
    for awaitTasks <- Seq(true) do
        var fails = 0
        var i     = 0
        while i < 1000 && fails < 3 do
            round(i, awaitTasks) match
                case Right(true) => ()
                case Right(false) => fails += 1; println(s"await=$awaitTasks round $i: not finalized")
                case Left(e)      => fails += 1; println(s"await=$awaitTasks ${e.getMessage}")
            i += 1
        println(s"await=$awaitTasks: rounds=$i fails=$fails")
    end for
    sys.exit(0)
end dispatcherDebug
