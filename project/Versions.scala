/** Versions that more than one module uses.
  *
  * Every published module watches this file (see ModuleVersioning): a change
  * here marks all modules as unreleased, because it can change their bytecode
  * or their POM. A version that only one module uses goes in that module's own
  * `modules/<m>/build.sbt`.
  */
object Versions {
  val scala      = "3.9.0"     // Scala 3 LTS. Kyo 1.0.0-RC7 is built with it.
  // claude 2026-10-12: changed the comment (D-19: kyo-ui uses this version)
  val kyo        = "1.0.0-RC7" // Needs JDK 25. kyo-ui and kyo-zio use it too.
  val jatatui    = "0.31.0"    // Java port of ratatui 0.30; replaces tui-scala.
  val munit      = "1.3.6"     // zio and cats tests. core: kyo-test.
  // claude 2026-10-12: created: TASTy reader of the core tests (surface guard)
  val tastyQuery = "1.9.0"
}
