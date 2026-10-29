package examples.workflow

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}
import tui4s.widgets.Colors

/** The operator session on the headless kit. The live pace uses short periods.
  * The burst leaf uses the quiet pace, so only the burst writes.
  */
// cursor 2026-10-29: created: the workflow headless suite
class WorkflowSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 60 s). */
    // cursor 2026-10-29: created: run one effect on the scheduler
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(60.seconds)(v).getOrThrow

    /** The session on a 120x36 screen. */
    // cursor 2026-10-29: created: start the session on the kit
    private def start(
        pace: Workflow.Pace = Workflow.Pace.Live,
        metric: Duration = 40.millis,
        token: Duration = 25.millis,
        tool: Duration = 30.millis,
        status: Duration = 40.millis,
    )(using Frame): Headless < (Async & Scope) =
      Headless.start(120, 36, stylesheet = Colors.defaults)(_ =>
        Workflow.app(pace, metric, token, tool, status),
      )

    /** The block characters on the row under the first gauge. */
    // cursor 2026-10-29: created: count the sparkline blocks
    private def samples(s: Screen): Int =
        val rows  = s.text.linesIterator.toVector
        val gauge = rows.indexWhere(_.contains("%"))
        if gauge < 0 || gauge + 1 >= rows.size then -1
        else rows(gauge + 1).count(c => "▁▂▃▄▅▆▇█".indexOf(c.toInt) >= 0)

    // cursor 2026-10-29: created: the probe list stays a small crowded demo
    test("dozens of sources, and the probe list stays under 64 rows") {
      assertEquals(Workflow.probeNames.size, 24)
      assert(Workflow.probeNames.size > 16)
      assertEquals(Workflow.streamCount, 27)
      assertEquals(
        Workflow.probeNames.take(Workflow.cardCount).toList,
        List("ingest", "memory", "errors", "latency"),
      )
    }

    // cursor 2026-10-29: created: the flag parser
    test("--quiet waits, and an unknown flag is an error") {
      assertEquals(
        Workflow.Settings.parse(Vector.empty),
        Right(Workflow.Pace.Live),
      )
      assertEquals(
        Workflow.Settings.parse(Vector("--quiet")),
        Right(Workflow.Pace.Quiet),
      )
      assert(Workflow.Settings.parse(Vector("--fast")).isLeft)
    }

    // cursor 2026-10-29: created: token chunks and a sent line
    test("a token chunk joins the reply, and a sent line starts a new line") {
      val one   = Workflow.appendPiece(Chunk.empty, Workflow.Script.pieces(0))
      val said  = Workflow.say(one, "hi")
      val noted = Workflow.appendPiece(said, Workflow.Script.reply("hi"))
      assert(one.last.text.contains(Workflow.Script.opening))
      assertEquals(said.last.text, "You: hi")
      assertEquals(noted.last.text, "Noted: hi.")
      assertEquals(noted.size, 3)
    }

    // cursor 2026-10-29: created: the first screen of the live session
    test("the screen shows both sides, the script and the sampling note") {
      val s = run(start().map(_.screen))
      assert(s.contains("Operator session"), s.text)
      assert(s.contains("Metrics") && s.contains("Transcript"), s.text)
      assert(s.contains("ingest") && s.contains("27 sources"), s.text)
      assert(s.contains("scripted") && s.contains("no key"), s.text)
      assert(s.contains("These rows update by sampling."), s.text)
      assert(s.contains("Esc quit") && s.contains("Enter sends"), s.text)
    }

    // cursor 2026-10-29: created: the gauges move
    test("the sparkline grows while the probes tick") {
      val grown = run {
        for
            h    <- start()
            grew <- h.awaitScreen(samples(_) >= 4, 15.seconds)
        yield grew
      }
      assert(grown, "the sparkline never showed 4 samples")
    }

    // cursor 2026-10-29: created: the scripted transcript, a tool event and a status
    test("the scripted stream appends the reply and a tool event") {
      val (shown, text) = run {
        for
            h  <- start()
            ok <-
              h.awaitScreen(
                s =>
                  s.contains(Workflow.Script.opening) &&
                    Workflow.Script.tools.exists(t => s.contains(s"tool $t")) &&
                    Workflow.Script.statuses
                      .exists(st => s.contains(s"status $st")),
                15.seconds,
              )
            s  <- h.screen
        yield (ok, s.text)
      }
      assert(shown, text)
    }

    // cursor 2026-10-29: created: a burst keeps the focused composer
    test("a burst does not drop the focused input") {
      val (before, after, held, text, ended) = run {
        for
            box   <- AtomicRef.init(Option.empty[Workflow.State])
            h     <-
              Headless.start(120, 36, stylesheet = Colors.defaults)(_ =>
                Workflow
                  .init(Workflow.Pace.Quiet)
                  .map(s =>
                    box.set(Some(s)).andThen(Workflow.view(s, Kyo.unit)),
                  ),
              )
            typed <- h.typeText("keep")
            s     <- box.get.map(_.get)
            _     <- Loop(16) { n =>
                       if n == 0 then Loop.done(())
                       else Workflow.burst(s).map(_ => Loop.continue(n - 1))
                     }
            held  <- h.awaitScreen(
                       s => s.contains("keep") && s.contains("status burst"),
                       10.seconds,
                     )
            more  <- h.typeText("more")
            text  <- h.screen
            ended <- h.finished
        yield (typed.last.focus, more.last.focus, held, text, ended)
      }
      assertEquals(before, Some("composer"))
      assertEquals(after, Some("composer"))
      assert(held, "the burst hid the input or the status")
      assert(text.contains("keepmore"), text.text)
      assert(text.contains("Operator session"), text.text)
      assert(!ended, "the burst ended the run")
    }

    // cursor 2026-10-29: created: quiet holds the probes
    test("--quiet leaves the sparkline still and shows the idle pace") {
      val (idle, still) = run {
        for
            h <- start(Workflow.Pace.Quiet)
            a <- h.screen
            _ <- Async.sleep(400.millis)
            b <- h.screen
        yield (
          a.contains("idle · scripted · no key"),
          samples(a) >= 1 && samples(a) == samples(b),
        )
      }
      assert(idle, "the quiet pace did not show")
      assert(still, "the sparkline moved while the streams were held")
    }

    // cursor 2026-10-29: created: Esc quits
    test("Esc ends the run") {
      val ended =
        run(
          start(Workflow.Pace.Quiet).map(h =>
            h.key(Keyboard.Escape).andThen(h.awaitEnd(10.seconds)),
          ),
        )
      assert(ended, "Esc did not end the run")
    }
end WorkflowSuite
