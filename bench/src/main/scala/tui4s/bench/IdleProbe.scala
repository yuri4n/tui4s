package tui4s.bench

/** B5 (SPEC 7.3): idle CPU. One JVM per case: Kyo alone (one fiber asleep) and
  * the Counter (no sources, input polled every 25 ms), each with Kyo's default
  * scheduler settings and with the settings of `spi.SchedulerDefaults`. Budget:
  * the Counter with the settings <= Kyo alone with the same settings + 0.5
  * points of one core, and <= 8% of one core.
  *
  * `sbt "bench/runMain tui4s.bench.IdleProbe [windowSeconds] [repeats]"`
  */
object IdleProbe:
    def main(args: Array[String]): Unit =
        val window    = args.headOption.map(_.toInt).getOrElse(20)
        val repeats   = args.lift(1).map(_.toInt).getOrElse(2)
        val cases     = Seq(
          ("kyo", "kyo-defaults", Probes.kyoDefaults),
          ("kyo", "tui4s-flags", Probes.tuiFlags),
          ("counter", "kyo-defaults", Probes.kyoDefaults),
          ("counter", "tui4s-flags", Probes.tuiFlags),
        )
        val results   = cases.map { (mode, flagsName, flags) =>
          // The median of the repeats: idle CPU is noisy.
          val cpus   = (1 to repeats).map(_ =>
            ProbeChild.measure(mode, flags, warmup = 5, window = window)._1,
          )
          val cpu    = Probes.quantile(cpus, 0.5)
          Probes.record(
            "idle",
            "workload"   -> mode,
            "flags"      -> flagsName,
            "cpuPercent" -> cpu,
            "runs"       -> cpus.map(c => f"$c%.2f").mkString("/"),
            "window"     -> window,
          )
          ((mode, flagsName), cpu)
        }.toMap
        val kyo       = results(("kyo", "tui4s-flags"))
        val counter   = results(("counter", "tui4s-flags"))
        Probes.record(
          "idle-budget",
          "counterCpu"   -> counter,
          "kyoAloneCpu"  -> kyo,
          "budget"       -> "counter <= kyo + 0.5 and <= 8",
          "withinBudget" -> (counter <= kyo + 0.5 && counter <= 8.0),
        )
    end main
end IdleProbe
