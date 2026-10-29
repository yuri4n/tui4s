package tui4s.spi

/** Kyo scheduler settings for an app that mostly waits for keys.
  *
  * With its default settings, the Kyo scheduler uses about 13% of one CPU core
  * while the app is idle. These settings make its timers slower, which cuts the
  * idle cost to about 4-8%. They are JVM system properties that Kyo reads once,
  * when its scheduler starts, so they must be set before the first Kyo class
  * loads. `tui4s.TuiApp` (artifact tui4s-core) calls [[applyIfUnset]] first in
  * its constructor; an app with a main of its own can call it first in its
  * main, or give the values as JVM flags.
  *
  * This object touches no Kyo class, so a call never starts the scheduler.
  *
  * `kyo.scheduler.blockingMonitorMinIntervalNs` is the minimum time between two
  * scans of Kyo's blocking monitor. When it is not set, Kyo measures the
  * resolution of the CPU-time counter of the threads when its scheduler starts.
  * This measurement is a loop of sleeps, and the start of the app waits for it:
  * about 90 ms (on Linux it gives 12.8 ms). tui4s sets 20 ms, so there is no
  * measurement (measured: the first frame comes about 70 to 100 ms sooner). The
  * reasons for 20 ms:
  *   - It is the scan interval of tui4s (`blockingMonitorIntervalNs`), so on
  *     Linux the periodic scans do not change. With a counter of 15.6 ms
  *     (Windows), the measurement gives 25.6 ms, so the scans now come more
  *     often: every 20 ms, not every 25.6 ms.
  *   - It is more than the known counter resolutions (10 ms on Linux, 15.6 ms
  *     on Windows). A value below the resolution can make a running worker look
  *     blocked, because two scans that are too near see the same CPU time.
  *   - A value above the scan interval makes all scans less frequent, so the
  *     monitor finds a blocked worker later.
  *
  * The cost: when the monitor is woken (a fiber on a blocked worker is
  * interrupted), the next scan comes at least 20 ms after the last one, not
  * after the measured value (12.8 ms on Linux; 2 ms when the counter is finer
  * than 2 ms).
  */
// claude 2026-10-13: changed (T2, review R1-5): the Scaladoc only (no SPEC
// section; TuiApp, not the facades' classes, which do not exist yet); the code
// is carried unchanged (RC-25)
// claude 2026-10-19: changed: the Scaladoc of blockingMonitorMinIntervalNs;
// TuiApp is in tui4s-app (D25); cursor 2026-10-29: changed: TuiApp ships in tui4s-core (ADR 0024)
// (C2)
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
      // claude 2026-10-19: created (C2): a measurement at start (about 90 ms)
      // -> 20 ms
      "kyo.scheduler.blockingMonitorMinIntervalNs"           -> "20000000",
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
    // claude 2026-10-19: changed: Option on the Java lines (purity plan, P1;
    // Option, not Maybe: no Kyo class may load here)
    def applyIfUnset(): Unit =
        val _ = applyTo(
          k => Option(java.lang.System.getProperty(k)), // scalafix:ok DisableSyntax.jvmGlobal; KEEP: it runs before Kyo loads
          k => Option(java.lang.System.getenv(k)),      // scalafix:ok DisableSyntax.jvmGlobal; KEEP: it runs before Kyo loads
          (key, value) =>
              val _ = java.lang.System.setProperty(key, value), // scalafix:ok DisableSyntax.jvmGlobal; KEEP: it runs before Kyo loads
        )

    /** [[applyIfUnset]] on the given property and environment lookups; returns
      * the keys that it set.
      */
    // claude 2026-10-19: changed: the lookups give an Option (purity plan, P1)
    private[tui4s] def applyTo(
        property: String => Option[String],
        env: String => Option[String],
        set: (String, String) => Unit,
    ): Seq[String] =
      if property(OptOut).contains("false") then Nil
      else
          defaults.collect {
            case (key, value)
                if property(key).isEmpty && env(envName(key)).isEmpty =>
              set(key, value)
              key
          }
end SchedulerDefaults
