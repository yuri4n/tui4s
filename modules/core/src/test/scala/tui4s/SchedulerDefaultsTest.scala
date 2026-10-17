package tui4s

import kyo.*
import kyo.test.*
import tui4s.spi.SchedulerDefaults

import scala.collection.mutable

/** The scheduler values (RC-25) are set only where the user set nothing, and
  * the constructor of `TuiApp` sets them before the app's own fields.
  * Sequential: two leaves change the JVM's properties for a moment (and put
  * them back).
  */
// claude 2026-10-13: changed (T2, RC-25, RC-10 part): sequential; the
// Scaladoc names no SPEC section; two leaves on the JVM's properties
class SchedulerDefaultsTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    private val keys = SchedulerDefaults.defaults.map(_._1)

    /** Runs `f` with the scheduler properties removed (and the opt-out set to
      * `optOut`, when given), then puts every property back as it was.
      */
    // claude 2026-10-13: created (T2)
    private def withoutProperties[A](optOut: Option[String])(f: => A): A =
        val all   = SchedulerDefaults.OptOut +: keys
        val saved = all.map(k => k -> java.lang.System.getProperty(k))
        try
            keys.foreach(java.lang.System.clearProperty)
            optOut match
                case Some(v) =>
                  val _ =
                    java.lang.System.setProperty(SchedulerDefaults.OptOut, v)
                case None    =>
                  val _ =
                    java.lang.System.clearProperty(SchedulerDefaults.OptOut)
            f
        finally
            saved.foreach {
              case (k, null) => val _ = java.lang.System.clearProperty(k)
              case (k, v)    => val _ = java.lang.System.setProperty(k, v)
            }
        end try
    end withoutProperties

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

    /* claude 2026-10-14: created (PR #16 review: Codex,
     * SchedulerDefaults.scala:37) */
    "the environment names do not depend on the default locale (Turkish i)" in {
      val saved = java.util.Locale.getDefault.nn
      try
          java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
          assert(
            SchedulerDefaults.envName(
              "kyo.scheduler.blockingMonitorIntervalNs",
            ) ==
              "KYO_SCHEDULER_BLOCKINGMONITORINTERVALNS",
          )
      finally java.util.Locale.setDefault(saved)
      end try
    }

    // claude 2026-10-13: changed (T2, review R1-9): without the properties of
    // the test JVM (its javaOptions set all three), so that the leaf fails
    // when applyIfUnset sets nothing; a key that the environment sets stays
    // unset
    "applyIfUnset sets the JVM properties (idempotent)" in {
      val after = withoutProperties(None) {
        SchedulerDefaults.applyIfUnset()
        SchedulerDefaults.applyIfUnset()
        keys.map(k => java.lang.System.getProperty(k))
      }
      val want  = SchedulerDefaults.defaults.map((k, v) =>
        if java.lang.System.getenv(SchedulerDefaults.envName(k)) == null then v
        else null,
      )
      assert(after == want, after.toString)
    }

    // claude 2026-10-13: created (T2, RC-25)
    "-Dtui4s.schedulerDefaults=false: applyIfUnset leaves the JVM properties alone" in {
      val after = withoutProperties(Some("false")) {
        SchedulerDefaults.applyIfUnset()
        keys.map(k => k -> java.lang.System.getProperty(k))
      }
      assert(after.forall(_._2 == null), after.toString)
    }

    // claude 2026-10-13: created (T2, RC-10 part, RC-24)
    "the constructor of TuiApp sets the values before the app's own fields" in {
      val seen = withoutProperties(None)(SchedulerProbeApp.seen)
      assert(seen == SchedulerDefaults.defaults.map(_._2), seen.toString)
    }
end SchedulerDefaultsTest

/** A `TuiApp` whose field reads the scheduler properties when the object is
  * made: the constructor of `TuiApp` has set them by then.
  */
// claude 2026-10-13: created (T2, RC-10 part)
object SchedulerProbeApp extends TuiApp:
    val seen: Seq[String | Null] =
      SchedulerDefaults.defaults.map((k, _) => java.lang.System.getProperty(k))
    def ui: UI < (Async & Scope) = UI.div
