package tui4s

import kyo.test.*
import scala.collection.mutable
import tui4s.spi.SchedulerDefaults

/** SPEC 5.14: the scheduler settings are set only where the user set nothing. */
class SchedulerDefaultsTest extends Test[Any]:
    private val keys = SchedulerDefaults.defaults.map(_._1)

    /** Runs `applyTo` on maps; returns (set keys, the properties after). */
    private def applyOn(
        props: Map[String, String],
        env: Map[String, String] = Map.empty,
    ): (Seq[String], Map[String, String]) =
        val now = mutable.Map.from(props)
        val set = SchedulerDefaults.applyTo(
          k => now.getOrElse(k, null),
          k => env.getOrElse(k, null),
          (k, v) => now(k) = v,
        )
        (set, now.toMap)
    end applyOn

    "with nothing set, all three keys get their defaults" in {
      val (set, props) = applyOn(Map.empty)
      assert(set == keys)
      assert(props == SchedulerDefaults.defaults.toMap)
      assert(props("kyo.scheduler.cycleIntervalNs") == "10000000")
    }

    "a system property of the user is kept" in {
      val (set, props) = applyOn(Map("kyo.scheduler.cycleIntervalNs" -> "5"))
      assert(!set.contains("kyo.scheduler.cycleIntervalNs"))
      assert(props("kyo.scheduler.cycleIntervalNs") == "5")
      assert(set.size == 2)
    }

    "an environment variable of the user is respected" in {
      val env          = Map("KYO_SCHEDULER_BLOCKINGMONITORINTERVALNS" -> "7")
      val (set, props) = applyOn(Map.empty, env)
      assert(!set.contains("kyo.scheduler.blockingMonitorIntervalNs"))
      assert(!props.contains("kyo.scheduler.blockingMonitorIntervalNs"))
      assert(set.size == 2)
    }

    "-Dtui4s.schedulerDefaults=false turns it off" in {
      val (set, props) = applyOn(Map("tui4s.schedulerDefaults" -> "false"))
      assert(set.isEmpty)
      assert(props == Map("tui4s.schedulerDefaults" -> "false"))
    }

    "the environment names are Kyo's" in {
      assert(
        keys.map(SchedulerDefaults.envName) == Seq(
          "KYO_SCHEDULER_CYCLEINTERVALNS",
          "KYO_SCHEDULER_BLOCKINGMONITORINTERVALNS",
          "KYO_SCHEDULER_REGULATOR_CONCURRENCYCOLLECTINTERVALMS",
        ),
      )
    }

    "applyIfUnset sets the JVM properties (idempotent)" in {
      SchedulerDefaults.applyIfUnset()
      SchedulerDefaults.applyIfUnset()
      val unset = keys.filter(k =>
        java.lang.System.getProperty(k) == null &&
          java.lang.System.getenv(SchedulerDefaults.envName(k)) == null,
      )
      assert(unset.isEmpty, unset.toString)
    }
end SchedulerDefaultsTest
