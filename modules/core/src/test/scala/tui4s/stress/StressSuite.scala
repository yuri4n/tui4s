package tui4s.stress

import kyo.*
import kyo.test.*
import tui4s.RunnerSuite

/** A stress probe: ignored unless the test JVM has `-Dtui4s.stress=<rounds>`
  * (RC-30). Run one by hand with, for example, `sbt 'set core / Test /
  * javaOptions += "-Dtui4s.stress=150"' 'core/testOnly
  * tui4s.stress.LeakProbe'`. The probes print `[probe]` lines with their
  * numbers; the PR body cites them.
  */
// claude 2026-10-12: created
abstract class StressSuite extends RunnerSuite:
    /** The rounds, when the probe is on. */
    protected val rounds: Option[Int] =
      Option(java.lang.System.getProperty("tui4s.stress")).map(_.trim.toInt)

    /** A leaf that runs only with the property, with a long timeout. */
    protected def probe(name: String): TestBuilder =
      rounds match
          case Some(_) => name.timeout(30.minutes)
          case None    =>
            name.ignore("set -Dtui4s.stress=<rounds> in the test JVM")

    /** The used heap in KB after three GCs. */
    protected def usedKb(): Long =
        val rt = java.lang.Runtime.getRuntime
        var i  = 0
        while i < 3 do
            java.lang.System.gc()
            Thread.sleep(100)
            i += 1
        (rt.totalMemory - rt.freeMemory) / 1024
    end usedKb

    protected def report(line: String): Unit =
      java.lang.System.out.println(s"[probe] $line")
end StressSuite
