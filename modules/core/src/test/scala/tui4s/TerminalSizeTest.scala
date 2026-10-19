package tui4s

import kyo.*
import kyo.UI.*
import tui4s.Hooks.component
import tui4s.testkit.Headless

/** [[Tui.size]]: the size of the terminal of the run as a signal. */
// claude 2026-10-19: created (L1b)
class TerminalSizeTest extends RunnerSuite:

    private def shown(s: Signal[TerminalSize])(using Frame): UI =
      span(s.map(t => s"${t.width}x${t.height}"))

    "a resize writes the size signal in the frame of the resize" in {
      for
          h  <- Headless.start(20, 3)(_ => Tui.size.map(shown))
          s0 <- h.screen
          f  <- h.resize(30, 5)
          s1 <- h.screen // the kit does not wait: this is the resize frame
          fs <- h.drainFrames
      yield
          assert(s0.contains("20x3"), s0.text)
          assert(s1.contains("30x5"), s1.text)
          assert(f.cause == FrameCause.Input, f.toString)
          assert(fs.forall(_.updates == 0), fs.toString)
      end for
    }

    "a hook component reads it too, and it changes the layout of the frame" in {
      for
          h  <- Headless.start(40, 4)(_ =>
                  div(
                    component(
                      Tui.size.map(s =>
                        div("left", "right").style(
                          s.map(t =>
                            if t.width >= 30 then Style.row.gap(1.px)
                            else Style.column,
                          ),
                        ),
                      ),
                    ),
                  ),
                )
          _  <- h.awaitScreen(_.contains("left right"), 5.seconds)
          _  <- h.resize(20, 4)
          s1 <- h.screen
      yield assert(
        s1.row(0).trim == "left" && s1.row(1).trim == "right",
        s1.text,
      )
    }

    "outside a run it stays at 0 x 0" in {
      for
          s <- Tui.size
          v <- s.current
      yield assert(v == TerminalSize(0, 0))
    }
end TerminalSizeTest
