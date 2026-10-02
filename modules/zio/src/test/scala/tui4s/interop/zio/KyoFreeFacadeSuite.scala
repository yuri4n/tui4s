package tui4s.interop.zio

import munit.FunSuite
import tui4s.SurfaceCheck

/** SPEC 2.12 / 6.5: a ZIO user never sees a Kyo type. No public or protected
  * signature of the facade (`tui4s.interop.zio` and its test kit) names a type
  * of package `kyo`. The check reads the TASTy with core's SurfaceCheck.
  */
class KyoFreeFacadeSuite extends FunSuite:
    override def munitTimeout = scala.concurrent.duration.Duration(120, "s")

    private val facade = Seq("tui4s.interop.zio", "tui4s.interop.zio.testkit")

    test("the facade's API names no kyo type") {
      val leaks = SurfaceCheck.leaks(facade, _ => false)
      assert(leaks.isEmpty, leaks.mkString("\n"))
    }

    test("the check sees the classes of the facade and of core") {
      // Without this, an empty class path would pass the test above.
      val all = SurfaceCheck.leaks(Seq("tui4s"), _ => false).map(_.where)
      assert(all.contains("tui4s.Tui$.run"), all.mkString("\n"))

      given tastyquery.Contexts.Context = SurfaceCheck.context
      val names                         = SurfaceCheck.context
        .findPackage("tui4s.interop.zio")
        .declarations
        .map(_.name.toString)
      assert(names.contains("Source"), names.mkString(", "))
      assert(names.contains("TuiApp"), names.mkString(", "))
    }
end KyoFreeFacadeSuite
