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

    test("every ecosystem has a parity app; Kyo is one of them") {
      assert(Parity.apps.map(_._1).contains("kyo"), Parity.apps.toString)
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
