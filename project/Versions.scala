/** Versions that more than one module uses.
  *
  * Every published module watches this file (see ModuleVersioning): a change here marks all
  * modules as unreleased, because it can change their bytecode or their POM. A version that only
  * one module uses goes in that module's own `modules/<m>/build.sbt`.
  */
object Versions {
  val scala   = "3.9.0"     // Scala 3 LTS. Kyo 1.0.0-RC7 is built with 3.9.0.
  val kyo     = "1.0.0-RC7" // Needs JDK 25. kyo-zio must use the same version.
  val jatatui = "0.31.0"    // Java port of ratatui 0.30, successor of tui-scala.
  val munit   = "1.3.6"
}
