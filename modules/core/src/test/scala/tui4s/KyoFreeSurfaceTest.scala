package tui4s

import kyo.*
import kyo.test.*

/** SPEC 2.2 / 6.5: the shared surface (everything in `tui4s` and
  * `tui4s.testkit` that the facades re-export) names no Kyo type, so ZIO and
  * Cats Effect users never see one. The Kyo-typed members that SPEC 2.2 lists
  * are left out.
  */
class KyoFreeSurfaceTest extends Test[Any]:
    /** SPEC 2.2: the Kyo-typed part. A class excludes its members. */
    private val kyoTyped = Seq(
      // the Source and Source.For constructors, Dispatch
      "tui4s.Source$.apply",
      "tui4s.Source$.every",
      "tui4s.Source$.poll",
      "tui4s.Source$.task",
      "tui4s.Source$.stream",
      "tui4s.Source$.signal",
      "tui4s.Source$.For",
      "tui4s.Dispatch",
      // the Kyo runner, main class and handle
      "tui4s.Tui",
      "tui4s.TuiApp",
      "tui4s.TuiHandle",
      // the live test kit
      "tui4s.testkit.TuiTest$.live",
      "tui4s.testkit.TuiTest$.liveBlocking",
      "tui4s.testkit.LiveTest",
    )

    private def excluded(name: String): Boolean =
      kyoTyped.exists(e =>
        name == e || name.startsWith(e + ".") || name.startsWith(e + "$"),
      )

    "the shared surface names no kyo type".timeout(60.seconds) in {
      val leaks = SurfaceCheck.leaks(Seq("tui4s", "tui4s.testkit"), excluded)
      assert(leaks.isEmpty, leaks.mkString("\n"))
    }

    "the check finds the Kyo-typed members it leaves out".timeout(
      60.seconds,
    ) in {
      val all   = SurfaceCheck.leaks(Seq("tui4s", "tui4s.testkit"), _ => false)
      val where = all.map(_.where)
      assert(where.contains("tui4s.Tui$.run"), where.mkString("\n"))
      assert(where.contains("tui4s.testkit.TuiTest$.live"))
      assert(where.exists(_.startsWith("tui4s.Source$.For.task")))
      assert(where.forall(excluded), where.filterNot(excluded).mkString("\n"))
    }
end KyoFreeSurfaceTest
