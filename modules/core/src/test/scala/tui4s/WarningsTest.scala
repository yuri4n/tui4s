package tui4s

import kyo.*
import kyo.UI.*
import tui4s.testkit.Headless

/** The warnings of the style properties that the terminal cannot show: one log
  * line for each name in a run, in the log of the run.
  */
// claude 2026-10-19: created (L1b)
class WarningsTest extends RunnerSuite:

    "two uses of an ignored property give one warning; a value that the runner does not honor and a variable with no value give one each" in {
      for
          h     <- Headless.start(30, 4)(_ =>
                     div(
                       span("a").style(_.fontSize(12.px)),
                       span("b").style(_.fontSize(14.px)),
                       div("t").style(_.display(_.table)),
                       span("c").style(_.color(Style.Color.variable("accent"))),
                       span("d").style(_.bold),
                     ),
                   )
          lines <- h.awaitLog
      yield
          def count(s: String): Int = lines.count(_.contains(s))
          assert(
            count(
              "tui4s: the style property FontSizeProp is not supported in the terminal (first seen at 0.0)",
            ) == 1,
            lines.mkString("\n"),
          )
          assert(count("DisplayProp(table)") == 1, lines.mkString("\n"))
          assert(count("var(--accent) has no value") == 1, lines.mkString("\n"))
          assert(lines.size == 3, lines.mkString("\n"))
      end for
    }

    "a property that appears in a later frame warns then, once; an entry of the stylesheet that the runner ignores warns at the start" in {
      for
          on     <- Signal.initRef(false)
          h      <- Headless.start(
                      20,
                      3,
                      stylesheet = Stylesheet.rule("x", Style.bold),
                    )(_ =>
                      div(
                        on.map(v =>
                          if v then span("x").style(_.cursor(_.pointer))
                          else span("y"),
                        ),
                      ),
                    )
          before <- h.awaitLog
          _      <- on.set(true)
          _      <- h.awaitScreen(_.contains("x"), 5.seconds)
          _      <- on.set(false)
          _      <- h.awaitScreen(_.contains("y"), 5.seconds)
          _      <- on.set(true)
          _      <- h.awaitScreen(_.contains("x"), 5.seconds)
          after  <- h.awaitLog
      yield
          assert(
            before.exists(_.contains("the rule .x of the stylesheet")),
            before.mkString("\n"),
          )
          assert(
            !before.exists(_.contains("CursorProp")),
            before.mkString("\n"),
          )
          assert(
            after.count(_.contains("CursorProp")) == 1,
            after.mkString("\n"),
          )
      end for
    }
end WarningsTest
