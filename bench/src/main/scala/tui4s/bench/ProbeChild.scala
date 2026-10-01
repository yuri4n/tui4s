package tui4s.bench

import jatatui.core.backend.TestBackend
import kyo.*
import tui4s.spi.Headless
import tui4s.testkit.ScriptedInput

/** The measured JVM of [[IdleProbe]] and [[DashboardProbe]]: it starts one
  * workload, waits `warmup` seconds, then measures the CPU time and the
  * allocation of the whole process for `window` seconds and prints
  * `RESULT cpu=<percent of one core> alloc=<bytes per second>`.
  *
  * Workloads: "kyo" (Kyo alone, one fiber asleep), "counter" (the Counter on
  * `spi.Headless`, no input, no sources), "dashboard" (the Dashboard, real
  * time). The input port waits in `poll` like the terminal.
  */
object ProbeChild:
    def main(args: Array[String]): Unit =
        val mode   = args(0)
        val warmup = args(1).toInt
        val window = args(2).toInt
        mode match
            case "kyo"       =>
              Probes.block(10.seconds)(
                Fiber.initUnscoped(Async.sleep(1.hour)).unit,
              )
            case "counter"   =>
              val app = examples.counter.CounterApp
              Probes.block(10.seconds)(
                Fiber
                  .initUnscoped(
                    Headless.run(app, TestBackend(120, 40), ScriptedInput.never),
                  )
                  .unit,
              )
            case "dashboard" =>
              val app = examples.dashboard.DashboardApp
              Probes.block(10.seconds)(
                Fiber
                  .initUnscoped(
                    Headless.run(app, TestBackend(120, 40), ScriptedInput.never),
                  )
                  .unit,
              )
            case other       => sys.error(s"unknown workload $other")
        end match
        Thread.sleep(warmup * 1000L)
        val c0     = Probes.processCpuNanos
        val a0     = Probes.allocatedBytes
        val t0     = java.lang.System.nanoTime()
        Thread.sleep(window * 1000L)
        val c1     = Probes.processCpuNanos
        val a1     = Probes.allocatedBytes
        val t1     = java.lang.System.nanoTime()
        val cpu    = 100.0 * (c1 - c0) / (t1 - t0)
        val alloc  = (a1 - a0) / ((t1 - t0) / 1e9)
        println(f"RESULT cpu=$cpu%.3f alloc=$alloc%.0f")
        java.lang.System.exit(0)
    end main

    /** Runs a child and reads its result: (CPU percent, bytes per second). */
    def measure(
        mode: String,
        flags: Seq[String],
        warmup: Int,
        window: Int,
    ): (Double, Double) =
        val out  = Probes.fork(
          "tui4s.bench.ProbeChild",
          flags,
          Seq(mode, warmup.toString, window.toString),
        )
        val line = out
          .find(_.startsWith("RESULT"))
          .getOrElse(sys.error(s"no result from $mode:\n${out.mkString("\n")}"))
        val kv   = line
          .drop("RESULT ".length)
          .split(' ')
          .map(_.split('='))
          .map(a => a(0) -> a(1).toDouble)
          .toMap
        (kv("cpu"), kv("alloc"))
    end measure
end ProbeChild
