package parity

import munit.FunSuite
import tui4s.*
import tui4s.testkit.*

/** The Counter body, compiled under each ecosystem's import, behaves like the
  * Kyo example (SPEC 6.5, facade parity).
  */
class ParitySuite extends FunSuite:
    private def run(app: App[Int]): (Int, String) =
        val t = TuiTest(app, 40, 5).press('+', '+', '-', '+')
        (t.state, t.screen.text)

    private val expected = run(examples.counter.CounterApp)

    /** The ecosystems that must have a parity app. The ZIO and Cats Effect pull
      * requests add "zio" and "cats" here and in the build (see
      * project/FacadeParity.scala): a name in only one place makes this fail.
      */
    private val ecosystems = Set("kyo", "cats")

    test("the parity apps are exactly the expected ecosystems") {
      assertEquals(Parity.apps.map(_._1).toSet, ecosystems)
    }

    Parity.apps.foreach { (name, app) =>
      test(s"$name: the same states and screens as the Kyo example") {
        assertEquals(run(app), expected)
        val t = TuiTest(app).press('+', '0')
        assertEquals(t.state, 0)
        assert(TuiTest(app).press('q').quit)
      }
    }
end ParitySuite
