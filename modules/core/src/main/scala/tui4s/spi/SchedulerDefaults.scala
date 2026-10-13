package tui4s.spi

/** Kyo scheduler settings for an app that mostly waits for keys (SPEC 5.14).
  *
  * With its default settings, the Kyo scheduler uses about 13% of one CPU core
  * while the app is idle. These settings make its timers slower, which cuts the
  * idle cost to about 4-8%. They are JVM system properties that Kyo reads once,
  * when its scheduler starts, so they must be set before the first Kyo class
  * loads. The `TuiApp` classes of tui4s and of its facades call
  * [[applyIfUnset]] first in their constructors.
  *
  * This object touches no Kyo class, so a call never starts the scheduler.
  */
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
    def envName(key: String): String = key.toUpperCase.nn.replace('.', '_').nn

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
