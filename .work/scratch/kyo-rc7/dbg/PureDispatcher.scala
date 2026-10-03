//> using scala 3.9.0
//> using dep org.typelevel::cats-effect:3.7.1
//> using mainClass pureDispatcher
// No Kyo: does Dispatcher.parallel(await = true) lose a cancel issued from a foreign thread right after the task starts?
import cats.effect.IO
import cats.effect.std.Dispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration as SDuration

@main def pureDispatcher(): Unit =
    import cats.effect.unsafe.implicits.global
    for awaitTasks <- Seq(true, false) do
        var fails = 0
        var i     = 0
        while i < 1000 && fails < 3 do
            val started   = new CountDownLatch(1)
            val finalized = new CountDownLatch(1)
            val io        = (IO(started.countDown()) *> IO.never[Int]).onCancel(IO(finalized.countDown()))
            val prog = Dispatcher.parallel[IO](await = awaitTasks).use { d =>
                IO.async_[Unit] { cb =>
                    // a foreign (non-CE) thread starts the task, waits for it to start, then cancels it
                    val t = new Thread(() => {
                        val (_, cancel) = d.unsafeToFutureCancelable(io)
                        started.await()
                        cancel()
                        cb(Right(()))
                    })
                    t.setDaemon(true)
                    t.start()
                }
            }
            val ok = prog.unsafeRunTimed(SDuration(5, "s")).isDefined && finalized.await(1, TimeUnit.SECONDS)
            if !ok then
                fails += 1
                println(s"await=$awaitTasks round $i: hung or not finalized (finalized=${finalized.getCount == 0})")
            i += 1
        end while
        println(s"await=$awaitTasks: rounds=$i fails=$fails")
    end for
    sys.exit(0)
end pureDispatcher
