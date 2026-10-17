package examples.dashboard

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}

/** The Dashboard on the headless test kit, with short periods (a sample every
  * 50 ms, a log line every 50 ms) and the kit's waits with wide margins: the
  * sampler moves the sparkline, `p` pauses it while the log goes on, the arrows
  * and Tab with Space change the tab (the bold tab moves), `q` quits.
  */
// claude 2026-10-13: created (T2, RC-33; the test ideas of PR 5's
// DashboardSuite)
class DashboardSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 60 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(60.seconds)(v).getOrThrow

    private def start(using Frame): Headless < (Async & Scope) =
      Headless.start(80, 24)(_ => Dashboard.app(50.millis, 50.millis))

    /** The samples in the sparkline: its block characters (the row under the
      * gauge, the row with the percentage).
      */
    private def samples(s: Screen): Int =
        val rows  = s.text.linesIterator.toVector
        val gauge = rows.indexWhere(_.contains("%"))
        if gauge < 0 || gauge + 1 >= rows.size then -1
        else rows(gauge + 1).count(c => "▁▂▃▄▅▆▇█".indexOf(c.toInt) >= 0)

    /** The row of the sparkline (the row under the gauge), or "". */
    // claude 2026-10-13: created (T2, review R1-2)
    private def sparkRow(s: Screen): String =
        val rows  = s.text.linesIterator.toVector
        val gauge = rows.indexWhere(_.contains("%"))
        if gauge < 0 || gauge + 1 >= rows.size then "" else rows(gauge + 1)

    /** Waits until the sparkline is the same in two screens 300 ms apart (a
      * tick that read `paused` before the key has landed by then), at most 40
      * times; the second screen, or None. The row text, not the count of its
      * samples: a full sparkline (40 samples) keeps its count while it moves.
      */
    // claude 2026-10-13: created (T2, review R1-2): a wait for a steady screen
    // in place of a fixed sleep
    private def awaitSteady(h: Headless)(using Frame): Option[Screen] < Async =
      Loop(1) { i =>
        for
            a <- h.screen
            _ <- Async.sleep(300.millis)
            b <- h.screen
        yield
          if sparkRow(a) == sparkRow(b) then Loop.done(Some(b))
          else if i >= 40 then Loop.done(None)
          else Loop.continue(i + 1)
      }

    /** True when the cell where `text` starts is bold. */
    private def bold(s: Screen, text: String): Boolean =
      s.find(text).exists((x, y) => s.styleAt(x, y).bold)

    test("the first screen: the title, the tabs, the CPU tab, the log pane") {
      val s = run(start.map(_.screen))
      assert(s.contains("Dashboard"), s.text)
      assert(
        s.contains("CPU") && s.contains("Memory") && s.contains("Network"),
        s.text,
      )
      assert(s.contains("CPU usage"), s.text)
      assert(s.contains("Log"), s.text)
      assert(s.contains("sampler running (p pauses)"), s.text)
      assert(bold(s, "CPU") && !bold(s, "Memory"), s.text)
    }

    test(
      "live: the sampler adds samples to the sparkline, the log gets lines",
    ) {
      val (sampled, logged) = run {
        for
            h       <- start
            sampled <- h.awaitScreen(samples(_) >= 5, 20.seconds)
            logged  <- h.awaitScreen(_.contains("event #3"), 20.seconds)
        yield (sampled, logged)
      }
      assert(sampled, "the sparkline never had 5 samples")
      assert(logged, "the log never showed event #3")
    }

    test(
      "p pauses the sampler (the sparkline stays), the log goes on; p resumes",
    ) {
      val (steady, before, after, logGrew, resumed, help) = run {
        for
            h       <- start
            _       <- h.awaitScreen(samples(_) >= 3, 20.seconds)
            _       <- h.char('p')
            help    <- h.screen
            // claude 2026-10-13: changed (T2, review R1-2): wait until a
            // tick that read `paused` before the key has landed (a steady
            // sparkline), not a fixed 200 ms
            steady  <- awaitSteady(h)
            s1       = steady.getOrElse(help)
            _       <- Async.sleep(500.millis)
            s2      <- h.screen
            logGrew <- h.awaitScreen(
                         _.text != s2.text,
                         20.seconds,
                       ) // the log moves on
            _       <- h.char('p')
            // claude 2026-10-13: changed (T2, review R1-2): no wait when the
            // sparkline never stopped (a full sparkline keeps its count)
            resumed <-
              if steady.isEmpty then Sync.defer(false)
              else h.awaitScreen(samples(_) > samples(s2), 20.seconds)
        yield (
          steady.isDefined,
          samples(s1),
          samples(s2),
          logGrew,
          resumed,
          help,
        )
      }
      assert(help.contains("sampler PAUSED (p resumes)"), help.text)
      assert(steady, "the sparkline did not stop moving after p")
      assert(before >= 3 && before == after, s"paused: $before then $after")
      assert(logGrew, "the log did not go on while the sampler was paused")
      assert(resumed, "p did not resume the sampler")
    }

    test("the arrows change the tab, and the bold tab moves") {
      val (right, left, wrap) = run {
        for
            h     <- start
            _     <- h.key(Keyboard.ArrowRight)
            right <- h.screen
            _     <- h.key(Keyboard.ArrowLeft)
            left  <- h.screen
            _     <- h.key(Keyboard.ArrowLeft)
            wrap  <- h.screen
        yield (right, left, wrap)
      }
      assert(right.contains("Memory usage"), right.text)
      assert(bold(right, "Memory") && !bold(right, "CPU"), right.text)
      assert(left.contains("CPU usage"), left.text)
      assert(wrap.contains("Network usage"), wrap.text)
      assert(bold(wrap, "Network"), wrap.text)
    }

    test("Tab moves to the next tab, and Space or Enter selects it") {
      val (bySpace, byEnter) = run {
        for
            h       <- start // the focus starts on the CPU tab
            _       <- h.key(Keyboard.Tab)
            _       <- h.key(Keyboard.Space)
            bySpace <- h.screen
            _       <- h.key(Keyboard.Tab)
            _       <- h.key(Keyboard.Enter)
            byEnter <- h.screen
        yield (bySpace, byEnter)
      }
      assert(bySpace.contains("Memory usage"), bySpace.text)
      assert(byEnter.contains("Network usage"), byEnter.text)
    }

    test("q ends the run") {
      val ended =
        run(start.map(h => h.char('q').andThen(h.awaitEnd(10.seconds))))
      assert(ended, "q did not end the run")
    }
end DashboardSuite
