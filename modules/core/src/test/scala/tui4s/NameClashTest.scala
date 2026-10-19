package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.*
import tui4s.Hooks.*
import tui4s.spi.*
import tui4s.testkit.*

/** A file with `import kyo.*`, `import kyo.UI.*`, `import kyo.test.*`,
  * `import tui4s.*`, `import tui4s.Hooks.*`, `import tui4s.spi.*` and
  * `import tui4s.testkit.*` that uses the public names of the core: no name is
  * ambiguous (OS-16 lesson).
  */
// claude 2026-10-13: changed (C4): tui4s.Hooks.* too
// claude 2026-10-12: created; changed (review R1-7): a RunnerSuite, as the
// rule of AGENTS.md says for a suite that runs the runner
class NameClashTest extends RunnerSuite:

    "kyo.*, kyo.UI.*, kyo.test.*, tui4s.*, tui4s.spi.* and tui4s.testkit.* together" in {
      val options: TuiOptions =
        TuiOptions(settle = Settle.Quiescent(java.time.Duration.ofMillis(100)))
      val cause: FrameCause   = FrameCause.Background
      val stats: FrameStats   = FrameStats(1L, cause, 0, 0, 0L, 0)
      val style: CellStyle    = CellStyle.plain
      val copy: Screen        = Screen(Vector(""), Vector(Vector(style)), None)
      val ui: UI              = div(span("x"))
      for
          h <- Headless.start(10, 2, options)(_ => ui)
          s <- h.screen
          _  = assertScreen(s, "x")
      yield assert(
        diffScreen(s, "x").isEmpty && stats.seq == 1L && copy.rows.size == 1 &&
          options.autoFocus && s.styleAt(0, 0) == CellStyle.plain,
      )
      end for
    }
    // claude 2026-10-13: created (C4): the hooks and the Component class with
    // the same imports and `import tui4s.Hooks.*`
    // claude 2026-10-19: changed: useHold and useFold, not the deprecated
    // useSignal (P1b, A5 f)
    "the same imports and tui4s.Hooks.*: the hooks and Component" in {
      final class Box extends Component:
          type State = Int
          def initial: Int                = 1
          def view(n: SignalRef[Int]): UI = span(n.map(v => s"box $v"))
      val app: UI < (Async & Scope) =
        for
            n <- useHold(2)(Stream.init(Seq.empty[Int]))
            _ <- useEffect(Kyo.unit)
            _ <- useInterval(1.hour)(Kyo.unit)
        yield div(
          span(n.map(v => s"hook $v")),
          component("k")(
            useFold(3)(Stream.init(Seq.empty[Int]))((s, _) => s)
              .map(m => span(m.map(v => s"node $v"))),
          ),
          Box(),
        )
      for
          h  <- Headless.start(20, 3)(_ => app)
          ok <- h.awaitScreen(
                  s =>
                    s.contains("hook 2") && s.contains("node 3") &&
                      s.contains("box 1"),
                  2.seconds,
                )
      yield assert(ok)
      end for
    }
end NameClashTest
