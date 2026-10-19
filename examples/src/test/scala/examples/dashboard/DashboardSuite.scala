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

    /** The numbers of the log lines that `s` shows, top first. */
    // claude 2026-10-19: created (L1a)
    private def events(s: Screen): Vector[Int] =
      """event #(\d+)""".r
        .findAllMatchIn(s.text)
        .map(_.group(1).nn.toInt)
        .toVector

    // claude 2026-10-19: created (L1a)
    test(
      "the log pane scrolls: Tab after the last tab reaches it, PageDown shows older lines, the indicator moves, Home goes back",
    ) {
      val (focus, before, after, home)        = run {
        for
            s      <- Dashboard.init
            _      <- s.logs.set(
                        Chunk.from(
                          (1 to 30).map(i => (i, s"event #$i: request served")),
                        ),
                      )
            h      <- Headless.start(80, 30)(_ => Dashboard.view(s))
            fs     <- Kyo.foreach(1 to 3)(_ => h.key(Keyboard.Tab))
            before <- h.screen
            _      <- h.key(Keyboard.PageDown)
            after  <- h.screen
            _      <- h.key(Keyboard.Home)
            home   <- h.screen
        yield (fs.lastOption.flatMap(_.focus), before, after, home)
      }
      assert(focus.contains("log"), s"$focus")
      // 7 rows, the newest line first
      assert(events(before) == (30 to 24 by -1).toVector, before.text)
      assert(events(after) == (23 to 17 by -1).toVector, after.text)
      assert(events(home) == events(before), home.text)
      // the thumb: on the row of the newest line before, not after
      def thumbOn(s: Screen, n: Int): Boolean =
        s.text.linesIterator
          .find(_.contains(s"event #$n:"))
          .exists(_.contains("█"))
      assert(thumbOn(before, 30) && !thumbOn(after, 23), after.text)
    }

    // claude 2026-10-19: created the three leaves below (L1b)
    test(
      "? opens the help: a centered box over the screen, its Close button has the focus, Tab stays in it, Esc closes it and the focus goes back",
    ) {
      val (moved, opened, screen, tab, esc, closed) = run {
        for
            h      <- start
            moved  <- h.key(Keyboard.ArrowRight) // the Memory tab
            opened <- h.char('?')
            screen <- h.screen
            tab    <- h.key(Keyboard.Tab)
            esc    <- h.key(Keyboard.Escape)
            closed <- h.screen
        yield (moved, opened, screen, tab, esc, closed)
      }
      assert(
        screen.contains("Keys") && screen.contains("[ Close ]"),
        screen.text,
      )
      // the box is in the middle: its title row is not at the left edge
      val (x, y)                                    = screen.find("Keys").getOrElse((0, 0))
      assert(x > 20 && y > 3, screen.text)
      assert(opened.focus.contains("help-close"), opened.toString)
      assert(tab.focus.contains("help-close"), tab.toString)
      assert(esc.focus == moved.focus && moved.focus.nonEmpty, s"$esc $moved")
      assert(!closed.contains("Keys"), closed.text)
    }

    test("p shows a toast at the bottom right; it goes away after its time") {
      val (shown, gone) = run {
        for
            h     <- Headless.start(80, 24)(_ =>
                       Dashboard.app(50.millis, 50.millis, 300.millis),
                     )
            _     <- h.char('p')
            shown <- h.screen
            gone  <- h.awaitScreen(!_.contains("sampler paused"), 10.seconds)
        yield (shown, gone)
      }
      val (x, y)        = shown.find("sampler paused").getOrElse((0, 0))
      assert(x > 50 && y > 18, shown.text)
      assert(gone, "the toast did not go away")
    }

    test(
      "the layout follows the width of the terminal: the log is beside the tabs at 120 columns, under them at 80",
    ) {
      def log(s: Screen): (Int, Int) =
        s.find("Log").getOrElse((-1, -1))
      val (wide, narrow)             = run {
        for
            h      <-
              Headless.start(120, 30)(_ => Dashboard.app(50.millis, 50.millis))
            wide   <- h.screen
            _      <- h.resize(80, 30)
            narrow <- h.screen
        yield (wide, narrow)
      }
      val (wx, wy)                   = log(wide)
      val (nx, ny)                   = log(narrow)
      assert(wx >= 50 && wy <= 2, wide.text)
      assert(nx == 0 && ny > 5, narrow.text)
    }
    // claude 2026-10-19: created (L2a): the stamp (useNow, timeAgo) and
    // the help from the keymap
    test(
      "the status line says how long ago the last sample came; the help lists the keys of the keymap",
    ) {
      val (fresh, old, help) = run {
        for
            box   <- AtomicRef.init(Option.empty[Dashboard.State])
            h     <- Headless.start(80, 24)(_ =>
                       Dashboard
                         .init(2.seconds)
                         .map(s => box.set(Some(s)).andThen(Dashboard.view(s))),
                     )
            fresh <- h.awaitScreen(_.contains("updated just now"), 20.seconds)
            s     <- box.get.map(_.get)
            t     <- Clock.now
            _     <- s.updated.set(t - 2.minutes)
            old   <-
              h.awaitScreen(_.contains("updated 2 minutes ago"), 20.seconds)
            _     <- h.char('?')
            help  <- h.screen
        yield (fresh, old, help)
      }
      assert(fresh, "the stamp never said 'updated just now'")
      assert(old, "the stamp never said 'updated 2 minutes ago'")
      assert(help.contains("p    pause or resume the sampler"), help.text)
      assert(help.contains("?    this help (Esc closes it)"), help.text)
      assert(help.contains("q    quit"), help.text)
    }
end DashboardSuite
