// claude 2026-10-11: created. Minimal reproducer for the upstream issue "Signal.observe keeps memory per value change".
// Reduced from .work/spikes/s2-r1r2/probes/ObserveBound.scala (mode "observe"). One ref, one observe watcher, N writes.
// Each write waits until the watcher saw it, then pauses 200 us so the watcher re-arms before the next write.
// Run: timeout 300 scala-cli run --jvm system ObserveLeak.scala -J -Xmx48m -- 30000  (the JVM hangs after the OOM)
//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7
import kyo.*

object ObserveLeak:
    def usedKb(): Long =
        val rt = java.lang.Runtime.getRuntime
        (1 to 3).foreach(_ => { java.lang.System.gc(); Thread.sleep(50) })
        (rt.totalMemory - rt.freeMemory) / 1024

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val n = args.lift(0).map(_.toInt).getOrElse(30000)
        val r = Abort.run[Any](Sync.Unsafe.run(KyoApp.runAndBlock(1.hour) {
            for
                ref  <- Signal.initRef(0)
                seen <- AtomicInt.init(0)
                _    <- Fiber.initUnscoped(ref.observe(v => seen.set(v)))
                k0   <- Sync.defer(usedKb())
                _ <- Kyo.foreachDiscard(1 to n) { i =>
                    ref.set(i)
                        .andThen(Loop.foreach(seen.get.map(s => if s >= i then Loop.done(()) else Async.sleep(20.micros).andThen(Loop.continue))))
                        .andThen(Async.sleep(200.micros))
                        .andThen(
                            if i % (n / 10) == 0 then
                                Sync.defer {
                                    val k = usedKb()
                                    println(f"writes=$i heapKB=$k bytesPerWrite=${(k - k0) * 1024.0 / i}%.0f")
                                }
                            else Kyo.unit
                        )
                }
            yield ()
        })).eval
        println(s"end: $r")
        java.lang.System.exit(0)
end ObserveLeak
