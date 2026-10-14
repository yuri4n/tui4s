package tui4s.spi

/** Kyo scheduler settings for an app that mostly waits for keys.
  *
  * With its default settings, the Kyo scheduler uses about 13% of one CPU core
  * while the app is idle. These settings make its timers slower, which cuts the
  * idle cost to about 4-8%. They are JVM system properties that Kyo reads once,
  * when its scheduler starts, so they must be set before the first Kyo class
  * loads. [[tui4s.TuiApp]] calls [[applyIfUnset]] first in its constructor; an
  * app with a main of its own can call it first in its main, or give the values
  * as JVM flags.
  *
  * This object touches no Kyo class, so a call never starts the scheduler.
  */
// claude 2026-10-13: changed (T2, review R1-5): the Scaladoc only (no SPEC
// section; TuiApp, not the facades' classes, which do not exist yet); the code
// is carried unchanged (RC-25)
object SchedulerDefaults:
    /** The property that turns the defaults off:
      * `-Dtui4s.schedulerDefaults=false`.
      */
    val OptOut: String = "tui4s.schedulerDefaults"

    /** The scheduler properties and the values that tui4s sets. */
    val defaults: Seq[(String, String)] = Seq(
      // 100 us -> 10 ms
      "kyo.scheduler.cycleIntervalNs"                        -> "10000000",
      // 2 ms -> 20 ms
      "kyo.scheduler.blockingMonitorIntervalNs"              -> "20000000",
      // 10 ms -> 100 ms
      "kyo.scheduler.regulator.concurrencyCollectIntervalMs" -> "100",
    )

    /** The environment variable that Kyo also reads for the property `key`:
      * `kyo.scheduler.cycleIntervalNs` is `KYO_SCHEDULER_CYCLEINTERVALNS`.
      */
    /* claude 2026-10-14: changed (PR #16 review: Codex,
     * SchedulerDefaults.scala:37): */
    // Locale.ROOT, so that a Turkish default locale does not turn the i of
    // blockingMonitorIntervalNs into a dotted capital I
    def envName(key: String): String =
      key.toUpperCase(java.util.Locale.ROOT).nn.replace('.', '_').nn

    /** Sets each property of [[defaults]] that the user did not set, neither as
      * a system property nor as an environment variable. Does nothing when the
      * system property `tui4s.schedulerDefaults` is `false`. Idempotent.
      */
    def applyIfUnset(): Unit =
        val _ = applyTo(
          java.lang.System.getProperty,
          java.lang.System.getenv,
          (key, value) =>
              val _ = java.lang.System.setProperty(key, value),
        )

    /** [[applyIfUnset]] on the given property and environment lookups; returns
      * the keys that it set.
      */
    private[tui4s] def applyTo(
        property: String => String | Null,
        env: String => String | Null,
        set: (String, String) => Unit,
    ): Seq[String] =
      if property(OptOut) == "false" then Nil
      else
          defaults.collect {
            case (key, value)
                if property(key) == null && env(envName(key)) == null =>
              set(key, value)
              key
          }
end SchedulerDefaults
