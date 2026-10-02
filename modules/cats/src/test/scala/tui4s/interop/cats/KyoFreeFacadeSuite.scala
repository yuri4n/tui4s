package tui4s.interop.cats

import scala.concurrent.duration.*
import tui4s.SurfaceCheck

/** SPEC 2.2 / 6.5: no public signature of the Cats Effect facade names a Kyo
  * type, so a Cats Effect app never sees one. The check reads the TASTy of the
  * facade with core's SurfaceCheck.
  */
class KyoFreeFacadeSuite extends munit.FunSuite:
    override def munitTimeout = 120.seconds

    private val facade = Seq("tui4s.interop.cats", "tui4s.interop.cats.testkit")

    test("the public API of tui4s.interop.cats names no kyo type") {
      val leaks = SurfaceCheck.leaks(facade, _ => false)
      assert(leaks.isEmpty, leaks.mkString("\n"))
    }

    test("the check reads the facade and finds Kyo types where they are") {
      given ctx: tastyquery.Contexts.Context = SurfaceCheck.context
      val names                              = ctx
        .findPackage("tui4s.interop.cats")
        .declarations
        .map(_.name.toString)
      assert(
        Seq("Tui", "TuiApp", "TuiHandle", "Source").forall(names.contains),
        names.mkString(", "),
      )
      // The core runner is Kyo-typed: the same check sees it.
      val core                               = SurfaceCheck.leaks(Seq("tui4s"), _ => false).map(_.where)
      assert(core.contains("tui4s.Tui$.run"), core.mkString("\n"))
    }
end KyoFreeFacadeSuite
