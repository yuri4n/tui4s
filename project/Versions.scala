/** Versions that more than one module uses.
  *
  * Every published module watches this file (see ModuleVersioning): a change
  * here marks all modules as unreleased, because it can change their bytecode
  * or their POM. A version that only one module uses goes in that module's own
  * `modules/<m>/build.sbt`.
  */
object Versions {
  val scala      = "3.9.0"     // Scala 3 LTS. Kyo 1.0.0-RC7 is built with it.
  // claude 2026-10-12: changed the comment (D-19: kyo-ui uses this version);
  // 2026-10-20: changed: kyo-zio is not used (ADR 0014)
  val kyo        = "1.0.0-RC7" // Needs JDK 25. kyo-ui uses it too.
  val jatatui    = "0.31.0"    // Java port of ratatui 0.30; replaces tui-scala.
  // claude 2026-10-20: changed: the examples use munit (no zio and cats tests)
  val munit      = "1.3.6"     // The example tests. The modules: kyo-test.
  // TASTy reader of the runner and core tests (the surface guard and the
  // public API test of the four jars of the split).
  // claude 2026-10-12: created; changed: the reason (review R1-3, R2-5);
  // 2026-10-20: changed: no zio and cats coverage tests (ADR 0014)
  val tastyQuery = "1.9.0"
}
