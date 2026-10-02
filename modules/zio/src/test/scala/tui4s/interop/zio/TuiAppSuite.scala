package tui4s.interop.zio

import _root_.zio.Chunk
import _root_.zio.Exit
import tui4s.spi.SchedulerDefaults

/** The ZIO app class (SPEC 4.2, 5.14): the constructor applies the scheduler
  * defaults, and `run` is `Tui.run` on the real terminal.
  */
class TuiAppSuite extends ZioSuite:
    private final class Counter extends TuiApp[Int]:
        def init: Int                               = 0
        def view(n: Int): View                      = text(n.toString)
        def update(n: Int, event: Event): Next[Int] = n

    test("the constructor sets the scheduler settings that are unset") {
      val keys  = SchedulerDefaults.defaults.map(_._1)
      // Kyo read them long ago in this JVM; only the properties are checked.
      val unset =
        keys.filter(k => System.getenv(SchedulerDefaults.envName(k)) == null)
      unset.foreach(System.clearProperty)
      val _     = Counter()
      unset.foreach { k =>
        assertEquals(
          Option(System.getProperty(k)),
          SchedulerDefaults.defaults.toMap.get(k),
          k,
        )
      }
    }

    test("run without a terminal fails with NotATerminal") {
      // The forked test JVM has no terminal on stdin (sbt connects none).
      assume(
        Option(System.console()).forall(c => !c.isTerminal),
        "stdin is a terminal here",
      )
      exit(Counter().invoke(Chunk.empty)) match
          case Exit.Failure(cause) =>
            assert(
              cause.failureOption.exists(_.isInstanceOf[TuiError.NotATerminal]),
              cause.prettyPrint,
            )
          case other               =>
            fail(s"unexpected $other")
      end match
    }
end TuiAppSuite
