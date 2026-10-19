package tui4s.testkit

/** The screen of a test: an alias of [[tui4s.Screen]]. The type lives in the
  * runner (artifact tui4s-runner, package `tui4s`), because the runner makes
  * it. This alias is the compatible path: `import tui4s.testkit.*` keeps
  * compiling, and both names are the same type.
  */
// claude 2026-10-19: created (D25): the alias of the type that moved to the
// runner
type Screen = tui4s.Screen

/** The companion of [[tui4s.Screen]], under its kit name. */
// claude 2026-10-19: created (D25): the forwarder of the companion
val Screen: tui4s.Screen.type = tui4s.Screen

/** The style of one screen cell: an alias of [[tui4s.CellStyle]] (package
  * `tui4s`, artifact tui4s-runner). This alias is the compatible path.
  */
// claude 2026-10-19: created (D25): the alias of the type that moved to the
// runner
type CellStyle = tui4s.CellStyle

/** The companion of [[tui4s.CellStyle]], under its kit name. */
// claude 2026-10-19: created (D25): the forwarder of the companion
val CellStyle: tui4s.CellStyle.type = tui4s.CellStyle

/** The colour of a screen cell: an alias of [[tui4s.CellColor]] (package
  * `tui4s`, artifact tui4s-runner). This alias is the compatible path.
  */
// claude 2026-10-19: created (D25): the alias of the type that moved to the
// runner
type CellColor = tui4s.CellColor

/** The companion of [[tui4s.CellColor]] (its cases), under its kit name. */
// claude 2026-10-19: created (D25): the forwarder of the companion
val CellColor: tui4s.CellColor.type = tui4s.CellColor

/** `None` when the screen shows `expected`; otherwise a row-by-row diff.
  * Trailing spaces and trailing empty rows are ignored on both sides.
  * Framework-neutral.
  */
// claude 2026-10-12: created (from the old PR 3)
def diffScreen(actual: Screen, expected: String): Option[String] =
    def clean(rows: Seq[String]): Vector[String] =
      rows.iterator
        .map(_.replaceAll("\\s+$", ""))
        .toVector
        .reverse
        .dropWhile(_.isEmpty)
        .reverse
    val act                                      = clean(actual.rows)
    val exp                                      = clean(expected.linesIterator.toSeq)
    if act == exp then None
    else
        val rows = (0 until act.size.max(exp.size)).collect {
          case i if act.lift(i) != exp.lift(i) =>
            s"row $i\n  actual:   |${act.lift(i).getOrElse("")}|\n" +
              s"  expected: |${exp.lift(i).getOrElse("")}|"
        }
        Some(rows.mkString("\n"))
    end if
end diffScreen

/** Throws `java.lang.AssertionError` with the diff when the screen does not
  * show `expected` (munit, zio-test, weaver, JUnit). In kyo-test write
  * `assert(diffScreen(screen, expected).isEmpty)`: kyo-test counts only its own
  * assertions.
  */
// claude 2026-10-12: created (from the old PR 3)
def assertScreen(actual: Screen, expected: String): Unit =
  diffScreen(actual, expected).foreach { diff =>
    throw new AssertionError( // scalafix:ok DisableSyntax.throw; KEEP: test frameworks need AssertionError
      s"screen differs:\n$diff\n--- actual screen:\n${actual.text}",
    )
  }
