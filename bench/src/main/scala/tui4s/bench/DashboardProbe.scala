package tui4s.bench

/** B6 (SPEC 7.3): the Dashboard running (4 samples a second, about 1.4 log
  * lines a second, real time) against the idle Counter, both with the settings
  * of `spi.SchedulerDefaults`, one JVM each. Budget: <= 2% of one core above
  * idle, and <= 15 MiB/s allocated.
  *
  * `sbt "bench/runMain tui4s.bench.DashboardProbe [windowSeconds]"`
  */
object DashboardProbe:
    def main(args: Array[String]): Unit =
        val window                  = args.headOption.map(_.toInt).getOrElse(20)
        val (idleCpu, idleA)        =
          ProbeChild.measure("counter", Probes.tuiFlags, 5, window)
        val (dashCpu, dashA)        =
          ProbeChild.measure("dashboard", Probes.tuiFlags, 5, window)
        val mib                     = 1024.0 * 1024.0
        val above                   = dashCpu - idleCpu
        Probes.record(
          "dashboard",
          "idleCpu"        -> idleCpu,
          "dashboardCpu"   -> dashCpu,
          "cpuAboveIdle"   -> above,
          "idleMiBps"      -> idleA / mib,
          "dashboardMiBps" -> dashA / mib,
          "budget"         -> "cpu above idle <= 2, alloc <= 15 MiB/s",
          "withinBudget"   -> (above <= 2.0 && dashA / mib <= 15.0),
          "window"         -> window,
        )
    end main
end DashboardProbe
